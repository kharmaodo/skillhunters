import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from datetime import timedelta
import json
import zipfile

spec = importlib.util.spec_from_file_location('backup', Path(__file__).parents[1] / 'retained_backup.py')
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)

class RetainedBackupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.source = self.root / 'snapshot'
        self.source.write_bytes(b'Synthetic database and object snapshot')
    def test_round_trip_and_exclusive_output(self):
        archive = b.pack(self.source, self.root / 'backups', 7)
        restored = b.stage(archive, self.root / 'restore')
        self.assertEqual(restored.read_bytes(), self.source.read_bytes())
        with self.assertRaises(FileExistsError): b.stage(archive, restored)
        self.assertTrue(restored.exists())
        self.assertEqual(b.expire(archive.parent), 0)
    def test_expiry_denies_restore_and_removes_envelope_only(self):
        archive = b.pack(self.source, self.root, 1)
        with patch.object(b, 'now', return_value=b.now()+timedelta(days=2)):
            with self.assertRaisesRegex(ValueError, 'BACKUP_EXPIRED'): b.stage(archive, self.root / 'restore')
            self.assertEqual(b.expire(self.root), 1)
        self.assertFalse(archive.exists()); self.assertTrue(self.source.exists())
    def test_retention_bound_and_symlink(self):
        for days in (0,31):
            with self.assertRaises(ValueError): b.pack(self.source, self.root, days)
        link = self.root / 'link'; link.symlink_to(self.source)
        with self.assertRaises(ValueError): b.pack(link, self.root, 1)
    def test_corrupt_payload_never_staged(self):
        archive = b.pack(self.source, self.root, 7)
        manifest, _ = b.metadata(archive)
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('manifest.json', json.dumps(manifest))
            z.writestr('snapshot.bin', b'x' * manifest['bytes'])
        with self.assertRaisesRegex(ValueError, 'BACKUP_INTEGRITY'): b.stage(archive, self.root / 'restore')
        self.assertFalse((self.root / 'restore').exists())
    def test_archive_path_traversal_rejected(self):
        archive = self.root / 'invalid.shbackup'
        with zipfile.ZipFile(archive, 'w') as z: z.writestr('../escape', 'synthetic')
        with self.assertRaises(ValueError): b.stage(archive, self.root / 'restore')
        self.assertFalse((self.root.parent / 'escape').exists())

if __name__ == '__main__': unittest.main()
