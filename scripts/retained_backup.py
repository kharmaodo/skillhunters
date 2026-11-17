#!/usr/bin/env python3
"""Envelope for an operator-produced backup; never includes or restores the erasure ledger.

This does not produce a consistent PostgreSQL/S3 snapshot (SH-29). The input must be
that snapshot. Restore only stages bytes; API startup separately replays erasures.
"""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import uuid
import zipfile


def now():
    return datetime.now(timezone.utc)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as src:
        for block in iter(lambda: src.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def metadata(archive):
    if archive.is_symlink():
        raise ValueError('SYMLINK_FORBIDDEN')
    with zipfile.ZipFile(archive) as z:
        if sorted(z.namelist()) != ['manifest.json', 'snapshot.bin'] or z.getinfo('manifest.json').file_size > 4096:
            raise ValueError('INVALID_ENVELOPE')
        m = json.loads(z.read('manifest.json'))
        created = datetime.fromisoformat(m['createdAt'])
        expires = datetime.fromisoformat(m['expiresAt'])
        if (m['format'] != 'sh-backup-v1' or created.tzinfo is None or expires.tzinfo is None
                or not timedelta() < expires - created <= timedelta(days=30)
                or len(m['sha256']) != 64 or m['bytes'] != z.getinfo('snapshot.bin').file_size):
            raise ValueError('INVALID_ENVELOPE')
        return m, expires


def pack(source, directory, days):
    if not 1 <= days <= 30 or source.is_symlink() or not source.is_file():
        raise ValueError('INVALID_BACKUP_INPUT')
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    started = now()
    m = {'format': 'sh-backup-v1', 'createdAt': started.isoformat(),
         'expiresAt': (started + timedelta(days=days)).isoformat(), 'sha256': digest(source), 'bytes': source.stat().st_size}
    target = directory / (str(uuid.uuid4()) + '.shbackup')
    fd, temp = tempfile.mkstemp(dir=directory, prefix='.pending-')
    os.close(fd)
    try:
        with zipfile.ZipFile(temp, 'w', compression=zipfile.ZIP_STORED) as z:
            z.writestr('manifest.json', json.dumps(m))
            z.write(source, 'snapshot.bin')
        # Input must be frozen by the operator for the duration of packaging.
        with zipfile.ZipFile(temp) as z, z.open('snapshot.bin') as payload:
            h = hashlib.file_digest(payload, 'sha256').hexdigest()
        if h != m['sha256']:
            raise ValueError('BACKUP_CHANGED_DURING_PACK')
        os.rename(temp, target)
    finally:
        Path(temp).unlink(missing_ok=True)
    return target


def stage(archive, output):
    m, expires = metadata(archive)
    if expires <= now():
        raise ValueError('BACKUP_EXPIRED')
    # Exclusive output prevents overwriting an active database, object or journal.
    created = False
    try:
        with output.open('xb') as dst:
            created = True
            os.chmod(output, 0o600)
            with zipfile.ZipFile(archive) as z, z.open('snapshot.bin') as src:
                shutil.copyfileobj(src, dst, 1024 * 1024)
        if digest(output) != m['sha256'] or expires <= now():
            raise ValueError('BACKUP_INTEGRITY_OR_EXPIRY')
    except Exception:
        if created:
            output.unlink(missing_ok=True)
        raise
    return output


def expire(directory):
    removed = 0
    for archive in directory.glob('*.shbackup'):
        _, expires = metadata(archive)  # Corrupt/unknown files fail closed for operator review.
        if expires <= now():
            archive.unlink()
            removed += 1
    return removed


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    p = commands.add_parser('pack'); p.add_argument('source', type=Path); p.add_argument('directory', type=Path); p.add_argument('--days', type=int, required=True)
    p = commands.add_parser('stage'); p.add_argument('archive', type=Path); p.add_argument('output', type=Path)
    p = commands.add_parser('expire'); p.add_argument('directory', type=Path)
    args = parser.parse_args()
    if args.command == 'pack':
        print(pack(args.source, args.directory, args.days))
    elif args.command == 'stage':
        print(stage(args.archive, args.output))
    else:
        print(f'Expired envelopes removed: {expire(args.directory)}')
