"""Untrusted-document child. No network, bounded resources, plain text output only."""
import ctypes
import errno
import io
import json
import os
from pathlib import Path
import resource
import subprocess
import sys
import zipfile
from defusedxml import ElementTree as ET
from pypdf import PdfReader
import olefile

MAX_TEXT = 500_000
MAX_PART = 8 * 1024 * 1024
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

class Refused(Exception):
    pass

def sandbox():
    resource.setrlimit(resource.RLIMIT_AS, (512 * 1024**2, 512 * 1024**2))
    resource.setrlimit(resource.RLIMIT_CPU, (20, 20))
    resource.setrlimit(resource.RLIMIT_FSIZE, (4 * 1024**2, 4 * 1024**2))
    resource.setrlimit(resource.RLIMIT_NOFILE, (32, 32))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    # Fail closed if seccomp is absent; inherited by antiword and any descendants.
    lib = ctypes.CDLL('libseccomp.so.2')
    lib.seccomp_init.argtypes = [ctypes.c_uint32]
    lib.seccomp_init.restype = ctypes.c_void_p
    lib.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
    lib.seccomp_rule_add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]
    lib.seccomp_load.argtypes = [ctypes.c_void_p]
    lib.seccomp_release.argtypes = [ctypes.c_void_p]
    ctx = lib.seccomp_init(0x7fff0000)
    if not ctx:
        raise Refused('SANDBOX_UNAVAILABLE')
    try:
        for name in ('socket', 'socketpair', 'connect', 'bind', 'listen', 'accept', 'accept4',
                     'sendto', 'sendmsg', 'sendmmsg', 'recvfrom', 'recvmsg', 'recvmmsg',
                     'kill', 'tkill', 'tgkill', 'pidfd_send_signal',
                     'ptrace', 'process_vm_readv', 'process_vm_writev', 'io_uring_setup'):
            number = lib.seccomp_syscall_resolve_name(name.encode())
            if number >= 0 and lib.seccomp_rule_add(ctx, 0x00050000 | errno.EPERM, number, 0) != 0:
                raise Refused('SANDBOX_UNAVAILABLE')
        if lib.seccomp_load(ctx) != 0:
            raise Refused('SANDBOX_UNAVAILABLE')
    finally:
        lib.seccomp_release(ctx)


def extract(path, kind):
    blocks = []
    total = 0
    def add(source, text):
        nonlocal total
        text = text.replace('\x00', '').strip()
        total += len(text)
        if total > MAX_TEXT or len(blocks) >= 5000:
            raise Refused('TEXT_LIMIT')
        if text:
            blocks.append({'source': source, 'text': text})
    ocr = []
    if kind == 'text/markdown':
        add('document', Path(path).read_text(encoding='utf-8-sig'))
    elif kind == 'application/vnd.openxmlformats-officedocument.wordprocessingml.document':
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            if len(infos) > 1000 or len({i.filename for i in infos}) != len(infos):
                raise Refused('ARCHIVE_LIMIT')
            if sum(i.file_size for i in infos) > 40 * 1024**2:
                raise Refused('ARCHIVE_LIMIT')
            for item in infos:
                name = item.filename.lower()
                if item.file_size > MAX_PART or item.flag_bits & 1 or item.file_size > max(1, item.compress_size) * 100:
                    raise Refused('ARCHIVE_LIMIT')
                if any(s in name for s in ('vbaproject', '/embeddings/', 'activex')):
                    raise Refused('ACTIVE_CONTENT')
                if name.endswith('.rels'):
                    root = ET.fromstring(archive.read(item))
                    if any(e.get('TargetMode', '').lower() == 'external' for e in root):
                        raise Refused('EXTERNAL_REFERENCE')
            content_types = archive.read('[Content_Types].xml').lower()
            if b'macroenabled' in content_types or b'vbaproject' in content_types:
                raise Refused('ACTIVE_CONTENT')
            document = ET.fromstring(archive.read('word/document.xml'))
            if any(e.tag in (W+'altChunk', W+'object', W+'fldSimple', W+'instrText') for e in document.iter()):
                raise Refused('ACTIVE_CONTENT')
            def walk(parent, source):
                for index, node in enumerate(parent):
                    loc = f'{source}/{node.tag.split("}")[-1]}[{index+1}]'
                    if node.tag == W+'p':
                        add(loc, ''.join(e.text or '' for e in node.iter(W+'t')))
                    elif node.tag in (W+'tbl', W+'tr', W+'tc', W+'body', W+'sdt', W+'sdtContent'):
                        # Each physical cell is visited once, including merged-cell continuations.
                        walk(node, loc)
            walk(document, 'word/document.xml')
            # Headers/footers can hold contact details; preserve their own evidence addresses.
            for item in infos:
                if item.filename.startswith(('word/header', 'word/footer')) and item.filename.endswith('.xml'):
                    root = ET.fromstring(archive.read(item))
                    if any(e.tag in (W+'altChunk', W+'object', W+'fldSimple', W+'instrText') for e in root.iter()):
                        raise Refused('ACTIVE_CONTENT')
                    for index, p in enumerate(root.iter(W+'p')):
                        add(f'{item.filename}/p[{index+1}]', ''.join(e.text or '' for e in p.iter(W+'t')))
    elif kind == 'application/pdf':
        reader = PdfReader(path, strict=True)
        if reader.is_encrypted:
            raise Refused('ENCRYPTED_DOCUMENT')
        if len(reader.pages) > 100:
            raise Refused('PAGE_LIMIT')
        seen = set()
        def inspect(value, depth=0):
            if depth > 80 or len(seen) > 100000:
                raise Refused('DOCUMENT_LIMIT')
            if hasattr(value, 'get_object'):
                value = value.get_object()
            if isinstance(value, (dict, list)):
                if id(value) in seen:
                    return
                seen.add(id(value))
            if isinstance(value, dict):
                if any(k in value for k in ('/JS', '/JavaScript', '/AA', '/OpenAction', '/EmbeddedFiles', '/XFA')):
                    raise Refused('ACTIVE_CONTENT')
                if value.get('/S') in ('/URI', '/Launch', '/GoToR', '/SubmitForm', '/ImportData'):
                    raise Refused('EXTERNAL_REFERENCE')
                for item in value.values():
                    inspect(item, depth+1)
            elif isinstance(value, list):
                for item in value:
                    inspect(item, depth+1)
        inspect(reader.trailer)
        for index, page in enumerate(reader.pages, 1):
            text = page.extract_text() or ''
            add(f'page:{index}', text)
            if not text.strip():
                ocr.append(index)
    elif kind == 'application/msword':
        with olefile.OleFileIO(path) as doc:
            names = ['/'.join(parts).lower() for parts in doc.listdir()]
            if any(any(s in name for s in ('vba', 'macros', 'objectpool', 'encryptedpackage')) for name in names):
                raise Refused('ACTIVE_CONTENT')
            # Legacy fields (DDE, INCLUDETEXT, hyperlinks) are never evaluated by antiword.
            # Refuse all field-bearing documents rather than partially interpreting links.
            raw = doc.openstream('WordDocument').read()
            if len(raw) < 12 or int.from_bytes(raw[10:12], 'little') & 0x8100:
                raise Refused('ENCRYPTED_DOCUMENT')
            start = int.from_bytes(raw[24:28], 'little')
            end = int.from_bytes(raw[28:32], 'little')
            if not 32 <= start <= end <= len(raw):
                raise Refused('INVALID_DOCUMENT')
            if b'\x13' in raw[start:end]:
                raise Refused('LEGACY_FIELDS_UNSUPPORTED')
        converted = subprocess.run(['antiword', '-m', 'UTF-8.txt', path], stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, timeout=15, check=True)
        add('legacy-document', converted.stdout.decode('utf-8'))
    else:
        raise Refused('UNSUPPORTED_FORMAT')
    return {'state': 'OCR_REQUIRED' if ocr else 'EXTRACTED', 'blocks': blocks, 'ocrPages': ocr}

if __name__ == '__main__':
    try:
        try:
            sandbox()
        except Exception:
            print(json.dumps({'state': 'RETRY', 'code': 'SANDBOX_UNAVAILABLE', 'blocks': [], 'ocrPages': []}))
            sys.exit(0)
        if sys.argv[1] == '--check-sandbox':
            import socket
            try:
                socket.socket()
            except PermissionError:
                print('network-denied')
                sys.exit(0)
            sys.exit(1)
        result = extract(sys.argv[1], sys.argv[2])
    except Refused as error:
        result = {'state': 'REJECTED', 'code': str(error), 'blocks': [], 'ocrPages': []}
    except Exception:
        result = {'state': 'REJECTED', 'code': 'INVALID_DOCUMENT', 'blocks': [], 'ocrPages': []}
    print(json.dumps(result, ensure_ascii=True))
