import json
from pathlib import Path
import unittest


class CorpusContractTest(unittest.TestCase):
    def test_frozen_annotations_and_cases(self):
        root = Path(__file__).resolve().parents[2] / 'tests/fixtures/ranking'
        corpus = json.loads((root / 'corpus.json').read_text())
        judgments = json.loads((root / 'judgments.json').read_text())
        docs = {d['id'] for d in judgments['documents']}
        self.assertEqual(len(docs), 100)
        self.assertEqual({p['id'] for p in corpus['profiles']}, docs)
        queries = {q['id']: q['grades'] for q in judgments['queries']}
        self.assertEqual(len(queries), 30)
        self.assertEqual(set(queries), {q['id'] for q in corpus['queries']})
        for grades in queries.values():
            self.assertEqual(set(grades), docs)
            self.assertTrue(all(type(v) is int and 0 <= v <= 3 for v in grades.values()))
        self.assertEqual([queries['q18'][d] for d in ['d000', 'd002', 'd010', 'd020']], [3, 2, 1, 0])
        self.assertEqual(set(queries['q28'].values()), {0})
        self.assertEqual(set(queries['q29'].values()), {0})

        self.assertEqual([q['id'] for q in corpus['queries'] if q['strict']], ['q28', 'q29'])
