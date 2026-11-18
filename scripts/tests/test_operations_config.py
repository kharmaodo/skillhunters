from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]

class OperationsConfigTest(unittest.TestCase):
    def test_initial_secrets_are_generated_and_existing_identity_is_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'scripts').mkdir();(root/'infrastructure').mkdir()
            script=root/'scripts/init_local.py';shutil.copyfile(ROOT/'scripts/init_local.py',script)
            result=subprocess.run(['python3',str(script)],capture_output=True,text=True,check=True)
            env=(root/'.env').read_text();values=dict(line.split('=',1) for line in env.splitlines())
            secrets=[values[k] for k in ('DB_PASSWORD','DEMO_PASSWORD','KEYCLOAK_ADMIN_PASSWORD','OIDC_CLIENT_SECRET','BACKUP_SIGNING_KEY','ERASURE_LEDGER_KEY','ERASURE_LEDGER_SECRET_KEY')]
            self.assertEqual(len(set(secrets)),len(secrets));self.assertTrue(all(len(v)>=32 for v in secrets))
            self.assertTrue(all(v not in result.stdout+result.stderr for v in secrets));self.assertEqual((root/'.env').stat().st_mode&0o777,0o600)
            self.assertEqual((root/'infrastructure/generated').stat().st_mode&0o777,0o700)
            self.assertEqual(values['ERASURE_LEDGER_ENDPOINT'],'http://localhost:9002')
            self.assertNotEqual(values['ERASURE_LEDGER_SECRET_KEY'],values['S3_SECRET_KEY'])
            self.assertNotEqual(subprocess.run(['python3',str(script)],capture_output=True).returncode,0)
            self.assertEqual((root/'.env').read_text(),env)
            subprocess.run(['python3',str(script),'--upgrade-operations'],check=True,capture_output=True)
            self.assertEqual((root/'.env').read_text(),env)
    def test_compose_ports_are_loopback_and_external_images_are_digest_pinned(self):
        text=(ROOT/'infrastructure/compose.yml').read_text()
        for line in text.splitlines():
            if 'ports:' in line:self.assertTrue(all(p.startswith('127.0.0.1:') for p in re.findall(r'"([^"]+)"',line)))
            if line.strip().startswith('image:'):self.assertRegex(line,r'@sha256:[a-f0-9]{64}$')
        for path in [*ROOT.glob('services/*/Dockerfile'),ROOT/'infrastructure/s3/Dockerfile']:
            for line in path.read_text().splitlines():
                if line.startswith('FROM '):self.assertRegex(line,r'@sha256:[a-f0-9]{64}(?: AS \w+)?$')

if __name__=='__main__':unittest.main()
