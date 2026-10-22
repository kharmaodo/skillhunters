import json
import math
import os
from pathlib import Path
import sys
import threading
import unittest
import urllib.request
import urllib.error
from http.server import HTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from engine import Engine
from worker import handler


class EmbeddingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = Engine(os.environ['MODEL_DIRECTORY'])

    def test_real_cpu_vectors_are_finite_normalized_and_translation_is_closer(self):
        texts = ['développement logiciel', 'software development', 'gestion de projet', 'Java']
        result = self.engine.encode(texts)
        self.assertEqual(result['model'], self.engine.manifest['model'])
        for vector in result['vectors']:
            self.assertEqual(len(vector), 384)
            self.assertTrue(all(math.isfinite(v) for v in vector))
            self.assertAlmostEqual(sum(v*v for v in vector), 1, places=5)
        a, b, c, d = result['vectors']
        dot = lambda x,y: sum(i*j for i,j in zip(x,y))
        self.assertGreater(dot(a,b), dot(a,d))
        self.assertGreater(dot(a,b), 0.65)

    def test_limits_do_not_silently_truncate(self):
        for value in [[], ['Java']*129, ['x'*201], ['person@example.com'], ['bad\nlabel'], [None], 'Java']:
            with self.assertRaises(ValueError): self.engine.encode(value)

    def test_runtime_adapter_auth_schema_and_success(self):
        token = 'synthetic-test-token-000000000000000000'
        server = HTTPServer(('127.0.0.1',0),handler(self.engine,token))
        thread = threading.Thread(target=server.serve_forever,daemon=True)
        thread.start()
        try:
            url=f'http://127.0.0.1:{server.server_port}/embed'
            def post(data, auth=token):
                return urllib.request.urlopen(urllib.request.Request(url,json.dumps(data).encode(),headers={'Authorization':'Bearer '+auth,'Content-Type':'application/json'}),timeout=10)
            with self.assertRaises(urllib.error.HTTPError) as error: post({'texts':['Java']},'wrong')
            self.assertEqual(error.exception.code,401)
            with self.assertRaises(urllib.error.HTTPError) as error: post({'texts':['Java'],'command':'ignored?'})
            self.assertEqual(error.exception.code,422)
            with post({'texts':['Java']}) as response:
                self.assertEqual(response.headers['Cache-Control'],'no-store')
                self.assertEqual(len(json.load(response)['vectors']),1)
        finally:
            server.shutdown();server.server_close();thread.join()
