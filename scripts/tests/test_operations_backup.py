import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parents[1]))
import operations_backup as b
import unittest
import tempfile
import json
import hashlib
import zipfile
from datetime import datetime, timezone, timedelta
from unittest.mock import Mock

KEY = 'synthetic-backup-authentication-key-only'

class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'snapshot.zip'
        data = b'Synthetic quarantine document'
        sha = hashlib.sha256(data).hexdigest()
        self.payload = {'db/skillhunters.dump': b'Synthetic app database', 'db/keycloak.dump': b'Synthetic identity database', 'objects/'+sha: data}
        self.manifest = {'format':'sh-snapshot-v1','createdAt':datetime.now(timezone.utc).isoformat(),
            'expiresAt':(datetime.now(timezone.utc)+timedelta(days=1)).isoformat(),'databases':list(b.DATABASES),
            'files':{k:{'sha256':hashlib.sha256(v).hexdigest(),'bytes':len(v)} for k,v in self.payload.items()},
            'objects':[{'key':'quarantine/synthetic/source','versionId':'synthetic-version','latest':True,'delete':False,'sha256':sha,'bytes':len(data)}],
            'documents':[{'object_key':'quarantine/synthetic/source','sha256':sha,'byte_size':len(data)}]}
    def write(self, signature=None):
        with zipfile.ZipFile(self.path,'w') as z:
            z.writestr('manifest.json',json.dumps({'manifest':self.manifest,'signature':signature or b.authenticate(self.manifest,KEY)}))
            for k,v in self.payload.items():z.writestr(k,v)
    def test_valid_snapshot_and_wrong_key(self):
        self.write();self.assertEqual(b.validate_snapshot(self.path,KEY),self.manifest)
        with self.assertRaisesRegex(ValueError,'SIGNATURE'):b.validate_snapshot(self.path,'different-backup-authentication-key')
    def test_modified_payload_rejected_before_restore(self):
        self.payload['db/skillhunters.dump']=b'Altered snapshot';self.write()
        with self.assertRaisesRegex(ValueError,'MISMATCH'):b.validate_snapshot(self.path,KEY)
    def test_modified_metadata_without_key_rejected(self):
        signature=b.authenticate(self.manifest,KEY);self.manifest['documents']=[];self.write(signature)
        with self.assertRaisesRegex(ValueError,'SIGNATURE'):b.validate_snapshot(self.path,KEY)
    def test_expiry_is_authenticated_inside_envelope(self):
        self.manifest['createdAt']=(datetime.now(timezone.utc)-timedelta(days=2)).isoformat()
        self.manifest['expiresAt']=(datetime.now(timezone.utc)-timedelta(days=1)).isoformat();self.write()
        with self.assertRaisesRegex(ValueError,'EXPIRED'):b.validate_snapshot(self.path,KEY)
    def test_traversal_and_unexpected_members(self):
        self.payload['../escape']=b'bad';self.write()
        with self.assertRaisesRegex(ValueError,'UNEXPECTED'):b.validate_snapshot(self.path,KEY)
        self.assertFalse((self.path.parent.parent/'escape').exists())
    def test_missing_or_changed_document_blocks_snapshot(self):
        self.manifest['documents'][0]['sha256']='0'*64;self.write()
        with self.assertRaisesRegex(ValueError,'DOCUMENT_OBJECT'):b.validate_snapshot(self.path,KEY)
    def test_latest_deleted_document_cannot_be_restored_as_live(self):
        self.manifest['objects'][0]['delete']=True;self.write()
        with self.assertRaisesRegex(ValueError,'DOCUMENT_OBJECT'):b.validate_snapshot(self.path,KEY)
    def test_restore_never_mutates_without_current_ledger_ack(self):
        ops=object.__new__(b.Operations);ops.s3=Mock();ops.compose=Mock()
        with self.assertRaisesRegex(ValueError,'CURRENT_INDEPENDENT'):ops.restore(self.path,False)
        ops.s3.assert_not_called();ops.compose.assert_not_called()
    def test_restore_rejects_nonempty_database_before_object_writes(self):
        self.manifest['migrationFiles']={};self.write()
        archive=b.retained_backup.pack(self.path,self.path.parent/'backups',1)
        ops=object.__new__(b.Operations);ops.key=KEY;ops.migration_files=lambda:{};ops.quiesce=Mock();ops.sql=Mock(return_value='1');ops.s3=Mock()
        with self.assertRaisesRegex(ValueError,'EMPTY_DATABASES'):ops.restore(archive,True)
        self.assertEqual(ops.s3.mock_calls,[])
    def test_restore_rejects_nonempty_quarantine_before_object_writes(self):
        self.manifest['migrationFiles']={};self.write()
        archive=b.retained_backup.pack(self.path,self.path.parent/'backups',1)
        ops=object.__new__(b.Operations);ops.key=KEY;ops.migration_files=lambda:{};ops.quiesce=Mock();ops.sql=Mock(return_value='0');ops.objects=lambda:[{'key':'existing'}];ops.s3=Mock()
        with self.assertRaisesRegex(ValueError,'EMPTY_QUARANTINE'):ops.restore(archive,True)
        self.assertEqual(ops.s3.mock_calls,[])

if __name__=='__main__':unittest.main()
