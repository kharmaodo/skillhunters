"""Internal, authenticated, bounded HTTP adapter. No downloads or text logging at runtime."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import hmac
import json
import os
from engine import Engine


def handler(engine, token):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, *args):
            pass

        def reply(self, status, data):
            payload = json.dumps(data, allow_nan=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self):
            self.reply(200 if self.path == '/health' else 404, {'status': 'ready' if self.path == '/health' else 'not_found'})

        def do_POST(self):
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
                self.reply(401, {'error': 'UNAUTHORIZED'})
                return
            if self.path != '/embed':
                self.reply(404, {'error': 'NOT_FOUND'})
                return
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if self.headers.get('Transfer-Encoding') or not 0 < length <= 128 * 1024:
                    raise ValueError('INPUT_LIMIT')
                data = json.loads(self.rfile.read(length))
                if not isinstance(data, dict) or set(data) != {'texts'}:
                    raise ValueError('INPUT_SCHEMA')
                result = engine.encode(data['texts'])
            except (ValueError, TypeError, TimeoutError):
                self.reply(422, {'error': 'INVALID_INPUT'})
                return
            except Exception:
                self.reply(503, {'error': 'MODEL_UNAVAILABLE'})
                return
            self.reply(200, result)
    return Handler


if __name__ == '__main__':
    token = os.environ.get('WORKER_TOKEN', '')
    if len(token) < 32:
        raise SystemExit('WORKER_TOKEN_REQUIRED')
    engine = Engine(os.environ.get('MODEL_DIRECTORY', '/models'))
    HTTPServer(('0.0.0.0', 8091), handler(engine, token)).serve_forever()
