#!/usr/bin/env python3
"""Offline, authenticated PostgreSQL + quarantine snapshots. Never restore the erasure ledger.
Run only against the dedicated local Compose installation; see docs/operations.md.
"""
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import zipfile
import retained_backup

ROOT = Path(__file__).resolve().parents[1]
DATABASES = ('skillhunters', 'keycloak')
MAX_OBJECT = 15728640


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def authenticate(manifest, key):
    if len(key) < 32:
        raise ValueError('BACKUP_SIGNING_KEY_REQUIRED')
    return hmac.new(key.encode(), b'SH29-SNAPSHOT-v1\0' + canonical(manifest), hashlib.sha256).hexdigest()


def load_env(path):
    values = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        name, sep, value = line.partition('=')
        if not sep or not re.fullmatch(r'[A-Z][A-Z0-9_]*', name) or not value or any(c in value for c in '\r\n\0'):
            raise ValueError('PLAIN_ENV_REQUIRED')
        values[name] = value
    return values


def validate_snapshot(path, key):
    """Validate every byte and archive member before any external mutation. No extractall."""
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [e.filename for e in entries]
        if (len(names) != len(set(names)) or len(names) > 1000002 or 'manifest.json' not in names
                or any(e.compress_type != zipfile.ZIP_STORED for e in entries)
                or archive.getinfo('manifest.json').file_size > 64 * 1024 * 1024):
            raise ValueError('INVALID_SNAPSHOT')
        signed = json.loads(archive.read('manifest.json'))
        manifest = signed['manifest']
        if not hmac.compare_digest(authenticate(manifest, key), signed['signature']):
            raise ValueError('BACKUP_SIGNATURE_INVALID')
        if manifest['format'] != 'sh-snapshot-v1' or set(manifest['databases']) != set(DATABASES):
            raise ValueError('INVALID_SNAPSHOT')
        created = datetime.fromisoformat(manifest['createdAt'])
        expires = datetime.fromisoformat(manifest['expiresAt'])
        if created.tzinfo is None or expires.tzinfo is None or not timedelta() < expires-created <= timedelta(days=30) or expires <= datetime.now(timezone.utc):
            raise ValueError('SNAPSHOT_EXPIRED_OR_INVALID')
        expected = {'manifest.json'}
        for name, info in manifest['files'].items():
            if not re.fullmatch(r'(?:db/(?:skillhunters|keycloak)\.dump|objects/[a-f0-9]{64})', name):
                raise ValueError('UNSAFE_SNAPSHOT_PATH')
            if name.startswith('objects/') and info['sha256'] != name[8:]:
                raise ValueError('OBJECT_DIGEST_NAME_MISMATCH')
            expected.add(name)
            if archive.getinfo(name).file_size != info['bytes']:
                raise ValueError('SNAPSHOT_SIZE_MISMATCH')
            with archive.open(name) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != info['sha256']:
                    raise ValueError('SNAPSHOT_DIGEST_MISMATCH')
        if expected != set(names) or not {'db/skillhunters.dump', 'db/keycloak.dump'} <= expected:
            raise ValueError('UNEXPECTED_SNAPSHOT_MEMBERS')
        latest = {}
        seen = set()
        for item in manifest['objects']:
            if (not isinstance(item['key'], str) or not 1 <= len(item['key'].encode()) <= 1024
                    or (item['key'], item['versionId']) in seen):
                raise ValueError('INVALID_OBJECT_MANIFEST')
            seen.add((item['key'], item['versionId']))
            if not item['delete']:
                blob = 'objects/' + item['sha256']
                if blob not in expected or not 0 <= item['bytes'] <= MAX_OBJECT or manifest['files'][blob]['bytes'] != item['bytes']:
                    raise ValueError('INVALID_OBJECT_PAYLOAD')
            if item['latest']:
                if item['key'] in latest:
                    raise ValueError('AMBIGUOUS_LATEST_OBJECT')
                latest[item['key']] = item
        for doc in manifest['documents']:
            item = latest.get(doc['object_key'])
            if not item or item['delete'] or item['sha256'] != doc['sha256'] or item['bytes'] != doc['byte_size']:
                raise ValueError('DOCUMENT_OBJECT_MISMATCH')
        return manifest


class Operations:
    def __init__(self, env_file, project):
        import boto3
        from botocore.config import Config
        self.values = load_env(env_file)
        self.key = self.values.get('BACKUP_SIGNING_KEY', '')
        authenticate({}, self.key)
        self.bucket = self.values.get('S3_BUCKET', 'skillhunters-quarantine')
        self.ledger = self.values.get('ERASURE_LEDGER_BUCKET', 'skillhunters-erasure-ledger')
        if self.bucket == self.ledger:
            raise ValueError('SEPARATE_LEDGER_BUCKET_REQUIRED')
        self.s3 = boto3.client('s3', endpoint_url=self.values['S3_ENDPOINT'],
            aws_access_key_id=self.values['S3_ACCESS_KEY'], aws_secret_access_key=self.values['S3_SECRET_KEY'],
            region_name=self.values.get('S3_REGION', 'us-east-1'),
            config=Config(signature_version='s3v4', s3={'addressing_style': 'path'}, connect_timeout=10, read_timeout=60, retries={'max_attempts': 2}))
        self.ledger_s3 = self.s3
        if self.values.get('ERASURE_LEDGER_ENDPOINT'):
            self.ledger_s3 = boto3.client('s3', endpoint_url=self.values['ERASURE_LEDGER_ENDPOINT'],
                aws_access_key_id=self.values['ERASURE_LEDGER_ACCESS_KEY'], aws_secret_access_key=self.values['ERASURE_LEDGER_SECRET_KEY'],
                region_name=self.values.get('S3_REGION', 'us-east-1'), config=Config(signature_version='s3v4', s3={'addressing_style':'path'}, connect_timeout=10, read_timeout=60))
        self.command = ['docker', 'compose', '--env-file', str(env_file.resolve()), '-f', str(ROOT / 'infrastructure/compose.yml'), '-p', project, '--profile', 'app']
        self.env = dict(os.environ, **self.values)

    def run(self, args, *, output=None, source=None):
        result = subprocess.run(args, cwd=ROOT, env=self.env, stdin=source, stdout=output or subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            # Provider/SQL diagnostics can contain secrets or personal data: no raw stderr.
            raise RuntimeError('OPERATION_COMMAND_FAILED')
        return result.stdout

    def compose(self, *args, **kwargs):
        return self.run(self.command + list(args), **kwargs)

    def sql(self, query, database='postgres'):
        data = self.compose('exec', '-T', 'postgres', 'psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'skillhunters', '-d', database, '-c', query)
        return data.decode().strip()

    def quiesce(self):
        self.compose('stop', 'api', 'keycloak')
        # Covers the documented host-Java mode as well as Docker. No process is killed.
        for port in (8080,):
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=1):
                    raise ValueError('STOP_LOCAL_API_BEFORE_BACKUP_OR_RESTORE')
            except (ConnectionRefusedError, TimeoutError):
                pass
        if self.sql("SELECT count(*) FROM pg_stat_activity WHERE datname IN ('skillhunters','keycloak') AND pid<>pg_backend_pid()") != '0':
            raise ValueError('DATABASE_WRITERS_STILL_CONNECTED')
        self.ledger_s3.head_bucket(Bucket=self.ledger)  # Never create or overwrite the current journal.

    def objects(self):
        result = []
        for page in self.s3.get_paginator('list_object_versions').paginate(Bucket=self.bucket):
            for group, deleted in [('Versions', False), ('DeleteMarkers', True)]:
                for obj in page.get(group, []):
                    result.append({'key': obj['Key'], 'versionId': obj['VersionId'], 'latest': obj['IsLatest'],
                        'modified': obj['LastModified'].isoformat(), 'delete': deleted})
        return result

    def documents(self):
        return json.loads(self.sql("SELECT coalesce(json_agg(x),'[]') FROM (SELECT object_key,sha256,byte_size FROM document_import WHERE state='QUARANTINED' ORDER BY object_key) x", 'skillhunters'))

    def migration_files(self):
        return {str(p.relative_to(ROOT)): retained_backup.digest(p) for folder in ('migration', 'vendor')
            for p in sorted((ROOT / 'services/api/src/main/resources/db' / folder).rglob('*.sql'))}

    def backup(self, directory, days):
        if not 1 <= days <= 30:
            raise ValueError('INVALID_RETENTION')
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.quiesce()
        with tempfile.TemporaryDirectory(prefix='.snapshot-', dir=directory) as work:
            work = Path(work)
            files = {}
            for database in DATABASES:
                dest = work / (database + '.dump')
                with dest.open('xb') as output:
                    self.compose('exec', '-T', 'postgres', 'pg_dump', '-U', 'skillhunters', '-Fc', '--no-owner', '--no-acl', database, output=output)
                files['db/' + database + '.dump'] = {'sha256': retained_backup.digest(dest), 'bytes': dest.stat().st_size}
            documents = self.documents()
            objects = self.objects()
            for item in objects:
                if item['delete']:
                    continue
                obj = self.s3.get_object(Bucket=self.bucket, Key=item['key'], VersionId=item['versionId'])
                if not 0 <= obj['ContentLength'] <= MAX_OBJECT:
                    raise ValueError('QUARANTINE_OBJECT_TOO_LARGE')
                dest = work / 'payload'
                with dest.open('wb') as output, obj['Body'] as stream:
                    shutil.copyfileobj(stream, output, 1024 * 1024)
                sha = retained_backup.digest(dest)
                item.update(sha256=sha, bytes=dest.stat().st_size)
                target = work / sha
                dest.replace(target)
                files['objects/' + sha] = {'sha256': sha, 'bytes': item['bytes']}
            containers = self.compose('ps', '-aq').decode().split()
            images = [self.run(['docker', 'inspect', '--format', '{{.Name}} {{.Image}}', c]).decode().strip() for c in containers]
            manifest = {'format': 'sh-snapshot-v1', 'createdAt': datetime.now(timezone.utc).isoformat(),
                'expiresAt': (datetime.now(timezone.utc)+timedelta(days=days)).isoformat(),
                'sourceRevision': self.run(['git', 'rev-parse', 'HEAD']).decode().strip(), 'imageIds': images,
                'databases': list(DATABASES), 'files': files, 'objects': objects, 'documents': documents,
                'migrationFiles': self.migration_files(),
                'migrations': json.loads(self.sql("SELECT coalesce(json_agg(x),'[]') FROM (SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank) x", 'skillhunters'))}
            snapshot = work / 'snapshot.zip'
            with zipfile.ZipFile(snapshot, 'w', compression=zipfile.ZIP_STORED) as archive:
                archive.writestr('manifest.json', canonical({'manifest': manifest, 'signature': authenticate(manifest, self.key)}))
                for name in files:
                    archive.write(work / (name[3:] if name.startswith('db/') else name[8:]), name)
            validate_snapshot(snapshot, self.key)
            archive = retained_backup.pack(snapshot, directory, days)
            self.report(archive.with_suffix('.report.json'), {'operation': 'backup', 'status': 'VERIFIED',
                'sourceRevision': manifest['sourceRevision'], 'documents': len(documents), 'objectVersions': len(objects),
                'migrations': manifest['migrations'], 'imageIds': images})
            return archive

    def restore(self, archive, ledger_current):
        if not ledger_current:
            raise ValueError('CURRENT_INDEPENDENT_LEDGER_REQUIRED')
        with tempfile.TemporaryDirectory(prefix='sh29-restore-') as work:
            work = Path(work)
            snapshot = retained_backup.stage(archive, work / 'snapshot.zip')
            manifest = validate_snapshot(snapshot, self.key)
            current = self.migration_files()
            if any(current.get(name) != sha for name, sha in manifest['migrationFiles'].items()):
                raise ValueError('MIGRATION_SOURCE_MISMATCH')
            self.quiesce()
            for database in DATABASES:
                if self.sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r','p','v','m','S','f')", database) != '0':
                    raise ValueError('RESTORE_REQUIRES_EMPTY_DATABASES')
            if self.objects():
                raise ValueError('RESTORE_REQUIRES_EMPTY_QUARANTINE')
            # Record only a fingerprint of the live ledger. It is never in the snapshot or writes below.
            ledger_before = self.ledger_fingerprint()
            self.s3.put_bucket_versioning(Bucket=self.bucket, VersioningConfiguration={'Status': 'Enabled'})
            with zipfile.ZipFile(snapshot) as payload:
                # API never stores provider version IDs. Preserve historical bytes and latest/delete semantics.
                for item in sorted(manifest['objects'], key=lambda x: (x['key'], x['latest'], x['modified'], x['versionId'])):
                    if item['delete']:
                        self.s3.delete_object(Bucket=self.bucket, Key=item['key'])
                    else:
                        with payload.open('objects/' + item['sha256']) as source:
                            # boto3 needs a seekable stream for signing/checksums; each object is <=15 MiB.
                            data = source.read()
                        self.s3.put_object(Bucket=self.bucket, Key=item['key'], Body=data,
                            ContentType='application/octet-stream', ContentDisposition='attachment')
                for database in DATABASES:
                    dest = work / (database + '.dump')
                    with payload.open('db/' + database + '.dump') as source, dest.open('xb') as target:
                        shutil.copyfileobj(source, target)
                    with dest.open('rb') as source:
                        self.compose('exec', '-T', 'postgres', 'pg_restore', '-U', 'skillhunters', '-d', database,
                            '--single-transaction', '--exit-on-error', '--no-owner', '--no-acl', source=source)
            self.verify_documents()
            if self.ledger_fingerprint() != ledger_before:
                raise ValueError('CURRENT_LEDGER_CHANGED_DURING_RESTORE')
            return {'operation': 'restore', 'status': 'STAGED_AWAITING_REPLAY', 'documents': len(manifest['documents']),
                'objectVersions': len(manifest['objects']), 'sourceRevision': manifest['sourceRevision'],
                'ledgerUnchanged': True, 'migrations': manifest['migrations'], 'imageIds': manifest['imageIds']}

    def ledger_fingerprint(self):
        h = hashlib.sha256()
        for page in self.ledger_s3.get_paginator('list_objects_v2').paginate(Bucket=self.ledger):
            for item in page.get('Contents', []):
                with self.ledger_s3.get_object(Bucket=self.ledger, Key=item['Key'])['Body'] as stream:
                    h.update(canonical([item['Key'], hashlib.file_digest(stream, 'sha256').hexdigest()]))
        return h.hexdigest()

    def verify_documents(self):
        docs = self.documents()
        for doc in docs:
            with self.s3.get_object(Bucket=self.bucket, Key=doc['object_key'])['Body'] as source:
                if hashlib.file_digest(source, 'sha256').hexdigest() != doc['sha256']:
                    raise ValueError('RESTORED_DOCUMENT_DIGEST_MISMATCH')
            if self.s3.head_object(Bucket=self.bucket, Key=doc['object_key'])['ContentLength'] != doc['byte_size']:
                raise ValueError('RESTORED_DOCUMENT_SIZE_MISMATCH')
        return len(docs)

    def verify(self):
        import urllib.request
        with urllib.request.urlopen('http://localhost:8080/actuator/health/readiness', timeout=10) as response:
            if json.load(response)['status'] != 'UP':
                raise ValueError('APPLICATION_NOT_READY')
        if self.sql("SELECT count(*) FROM erasure_tombstone WHERE state<>'COMPLETE'", 'skillhunters') != '0':
            raise ValueError('ERASURE_REPLAY_NOT_COMPLETE')
        due = self.sql("SELECT count(*) FROM document_import d WHERE d.expires_at<=CURRENT_TIMESTAMP AND NOT EXISTS (SELECT 1 FROM retention_hold h WHERE h.import_id=d.id AND h.until_at>CURRENT_TIMESTAMP)", 'skillhunters')
        batches = self.sql("SELECT count(*) FROM import_batch b WHERE b.expires_at<=CURRENT_TIMESTAMP AND NOT EXISTS (SELECT 1 FROM import_batch_item i JOIN document_import d ON d.idempotency_key=CONCAT('batch-',CAST(i.id AS VARCHAR)) JOIN retention_hold h ON h.import_id=d.id WHERE i.batch_id=b.id AND h.until_at>CURRENT_TIMESTAMP)", 'skillhunters')
        if due != '0' or batches != '0':
            raise ValueError('EXPIRED_DATA_PURGE_NOT_COMPLETE')
        erased = json.loads(self.sql("SELECT coalesce(json_agg(x),'[]') FROM (SELECT source_id,pool_id FROM erasure_tombstone WHERE kind='IMPORT') x", 'skillhunters'))
        keys = {item['key'] for item in self.objects()}
        if any('quarantine/' + r['pool_id'] + '/' + r['source_id'] in keys for r in erased):
            raise ValueError('ERASED_OBJECT_VERSION_RESTORED')
        return {'operation': 'verify', 'status': 'VERIFIED', 'documents': self.verify_documents(),
            'completedErasures': len(erased), 'readiness': 'UP'}

    @staticmethod
    def report(path, data):
        with path.open('x') as out:
            os.chmod(path, 0o600)
            json.dump(dict(data, checkedAt=datetime.now(timezone.utc).isoformat()), out, indent=2)
            out.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / '.env')
    parser.add_argument('--project', default='infrastructure')
    sub = parser.add_subparsers(dest='command', required=True)
    b = sub.add_parser('backup'); b.add_argument('directory', type=Path); b.add_argument('--days', type=int, required=True)
    r = sub.add_parser('restore'); r.add_argument('archive', type=Path); r.add_argument('--ledger-current', action='store_true'); r.add_argument('--report', type=Path, required=True)
    v = sub.add_parser('verify'); v.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    try:
        ops = Operations(args.env_file, args.project)
        if args.command == 'backup':
            print(ops.backup(args.directory, args.days))
        else:
            result = ops.restore(args.archive, args.ledger_current) if args.command == 'restore' else ops.verify()
            ops.report(args.report, result)
            print(result['status'])
    except Exception as error:
        # Only explicit application codes are safe to print, never provider/SQL messages.
        code = str(error) if isinstance(error, (ValueError, RuntimeError)) and re.fullmatch('[A-Z_]+', str(error)) else 'OPERATION_FAILED'
        raise SystemExit(code) from None

if __name__ == '__main__':
    main()
