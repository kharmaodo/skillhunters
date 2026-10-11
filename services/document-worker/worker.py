"""Private synchronous worker. PostgreSQL jobs and leases are owned by the API."""
import ctypes
import datetime as dt
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import signal
import socket
import struct
import subprocess
import sys
import tempfile

MAX_FILE = 15 * 1024**2
VERSION = 'sh07-1;pypdf-6.19.0;olefile-0.47;defusedxml-0.7.1;antiword-0.37'

class Unavailable(Exception):
    pass

def receive(sock):
    reply = bytearray()
    while len(reply) < 1024:
        chunk = sock.recv(1)
        if not chunk:
            raise Unavailable()
        if chunk == b'\0':
            return reply.decode('ascii')
        reply.extend(chunk)
    raise Unavailable()

def connection():
    return socket.create_connection((os.environ.get('CLAMD_HOST', 'clamav'), int(os.environ.get('CLAMD_PORT', '3310'))), timeout=15)

def av_version():
    with connection() as sock:
        sock.sendall(b'zVERSION\0')
        version = receive(sock)
    try:
        engine, serial, stamp = version.split('/', 2)
        date = dt.datetime.strptime(stamp.strip(), '%a %b %d %H:%M:%S %Y').replace(tzinfo=dt.timezone.utc)
        age = (dt.datetime.now(dt.timezone.utc) - date).total_seconds()
        if not engine.startswith('ClamAV ') or not serial.isdigit() or age < -3600 or age > 72 * 3600:
            raise ValueError()
    except ValueError:
        raise Unavailable()
    return version

def scan(data):
    before = av_version()
    with connection() as sock:
        sock.sendall(b'zINSTREAM\0')
        for offset in range(0, len(data), 65536):
            chunk = data[offset:offset+65536]
            sock.sendall(struct.pack('!I', len(chunk)) + chunk)
        sock.sendall(struct.pack('!I', 0))
        reply = receive(sock)
    after = av_version()
    if before != after:
        raise Unavailable()
    if reply == 'stream: OK':
        return True, after
    if reply.startswith('stream: ') and reply.endswith(' FOUND'):
        return False, after
    raise Unavailable()

def analyze(data, kind, expected_hash):
    if hashlib.sha256(data).hexdigest() != expected_hash:
        return {'state': 'REJECTED', 'code': 'INTEGRITY_MISMATCH', 'blocks': [], 'ocrPages': [], 'parserVersion': VERSION, 'avVersion': None}
    try:
        clean, version = scan(data)
    except (Unavailable, OSError, ValueError):
        return {'state': 'RETRY', 'code': 'ANTIVIRUS_UNAVAILABLE', 'blocks': [], 'ocrPages': [], 'parserVersion': VERSION, 'avVersion': None}
    if not clean:
        return {'state': 'REJECTED', 'code': 'ANTIVIRUS_BLOCKED', 'blocks': [], 'ocrPages': [], 'parserVersion': VERSION, 'avVersion': version}
    with tempfile.TemporaryDirectory(prefix='sh07-') as directory:
        path = Path(directory) / 'document'
        path.write_bytes(data)
        output = Path(directory) / 'result.json'
        with output.open('wb') as out:
            process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('parser.py')), str(path), kind],
                stdout=out, stderr=subprocess.DEVNULL, cwd=directory,
                env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'HOME': directory}, start_new_session=True)
            try:
                process.wait(timeout=25)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        try:
            if process.returncode != 0 or output.stat().st_size > 4 * 1024**2:
                raise ValueError()
            result = json.loads(output.read_text())
        except (ValueError, OSError):
            result = {'state': 'REJECTED', 'code': 'PARSER_LIMIT', 'blocks': [], 'ocrPages': []}
    result.update(parserVersion=VERSION, avVersion=version)
    return result

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Never log a body, document name, token or parser exception.

    def do_GET(self):
        self.send_error(405)

    def do_POST(self):
        self.connection.settimeout(30)
        token = os.environ.get('WORKER_TOKEN', '')
        if self.path != '/analyze' or len(token) < 32 or not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
            self.send_error(403)
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= MAX_FILE or self.headers.get('Transfer-Encoding'):
                self.send_error(413)
                return
            data = self.rfile.read(size)
            if len(data) != size:
                self.send_error(400)
                return
            result = analyze(data, self.headers.get('Content-Type', ''), self.headers.get('X-Content-SHA256', ''))
            body = json.dumps(result).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (OSError, ValueError):
            self.close_connection = True

if __name__ == '__main__':
    if len(os.environ.get('WORKER_TOKEN', '')) < 32:
        sys.exit('WORKER_TOKEN must contain at least 32 characters')
    # Same-UID parser children must not read the supervisor's token via /proc.
    if ctypes.CDLL(None).prctl(4, 0, 0, 0, 0) != 0:  # PR_SET_DUMPABLE
        sys.exit('Supervisor isolation unavailable')
    HTTPServer(('0.0.0.0', 8090), Handler).serve_forever()
