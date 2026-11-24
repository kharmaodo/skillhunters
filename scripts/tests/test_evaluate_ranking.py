import copy
import importlib.util
import math
import json
import hashlib
from unittest.mock import patch
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('ranking', Path(__file__).parents[1] / 'evaluate_ranking.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class RankingTest(unittest.TestCase):
    def fixture(self):
        return ({'schemaVersion': 1, 'split': 'validation',
                 'documents': [{'id': 'a', 'format': 'MD'}, {'id': 'b', 'format': 'PDF_SCAN'},
                               {'id': 'c', 'format': 'MD'}],
                 'queries': [{'id': 'q', 'grades': {'a': 3, 'b': 1, 'c': 0}}]},
                {'schemaVersion': 1, 'commit': 'a' * 40, 'configurationSha256': 'b' * 64,
                 'rankings': {'q': ['b', 'c', 'a']}})

    def test_graded_order_not_exhaustive_recall(self):
        j, r = self.fixture()
        report = m.evaluate(j, r)
        self.assertEqual(report['macroRecallAt20'], 1)
        self.assertAlmostEqual(report['macroNdcgAt10'], (1 + 7 / 2) / (7 + 1 / math.log2(3)))
        self.assertEqual(report['technicalDecision'], 'DOES_NOT_MEET_PROPOSED_TARGETS')

    def test_empty_results_are_zero_not_pass(self):
        j, r = self.fixture(); r['rankings']['q'] = []
        report = m.evaluate(j, r)
        self.assertEqual(report['macroRecallAt20'], 0)
        self.assertEqual(report['macroNdcgAt10'], 0)

    def test_no_positive_labels_not_perfect(self):
        j, r = self.fixture(); j['queries'][0]['grades'] = dict.fromkeys('abc', 0)
        report = m.evaluate(j, r)
        self.assertIsNone(report['macroRecallAt20'])
        self.assertIsNone(report['macroNdcgAt10'])
        self.assertEqual(report['unexpectedResultsForEmptyQueries'], 3)

    def test_reject_missing_extra_duplicate_and_unknown(self):
        j, r = self.fixture()
        for rankings in [{}, {'q': ['a'], 'extra': []}, {'q': ['a', 'a']}, {'q': ['unknown']}]:
            with self.subTest(rankings=rankings), self.assertRaises(ValueError):
                m.evaluate(j, dict(r, rankings=rankings))
        del j['queries'][0]['grades']['c']
        with self.assertRaises(ValueError): m.evaluate(j, r)

    def test_reject_invalid_grades_and_training_split(self):
        j, r = self.fixture()
        for grade in [True, -1, 4, 1.5, '1']:
            bad = copy.deepcopy(j); bad['queries'][0]['grades']['a'] = grade
            with self.assertRaises(ValueError): m.evaluate(bad, r)
        j['split'] = 'training'
        with self.assertRaises(ValueError): m.evaluate(j, r)

    def test_cutoff_and_format_use_original_ranking(self):
        j, r = self.fixture()
        j['documents'] = [{'id': f'd{i}', 'format': 'PDF_SCAN' if i == 20 else 'MD'} for i in range(21)]
        j['queries'][0]['grades'] = {f'd{i}': int(i == 20) for i in range(21)}
        r['rankings']['q'] = [f'd{i}' for i in range(21)]
        report = m.evaluate(j, r)
        self.assertEqual(report['macroRecallAt20'], 0)
        self.assertEqual(report['recallAt20ByFormat']['PDF_SCAN']['macroRecallAt20'], 0)
        self.assertIsNone(report['recallAt20ByFormat']['MD']['macroRecallAt20'])

    def test_sufficient_perfect_set_still_not_production_approval(self):
        j, r = self.fixture()
        j['documents'] = [{'id': f'd{i}', 'format': 'MD'} for i in range(100)]
        j['queries'] = [{'id': f'q{i}', 'grades': {f'd{k}': int(k == i) for k in range(100)}} for i in range(30)]
        r['rankings'] = {f'q{i}': [f'd{i}'] for i in range(30)}
        report = m.evaluate(j, r)
        self.assertEqual(report['technicalDecision'], 'MEETS_PROPOSED_TARGETS')
        self.assertEqual(report['productionDecision'], 'NOT_APPROVED_BY_THIS_REPORT')

    def test_duplicate_json_keys_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.json'; path.write_text('{"q": 1, "q": 2}')
            with self.assertRaises(ValueError): m.load(path)

    def test_cli_binds_report_to_inputs_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); jp = root / 'j.json'; rp = root / 'r.json'; out = root / 'out.json'
            j, r = self.fixture(); jp.write_text(json.dumps(j))
            r['judgmentsSha256'] = hashlib.sha256(jp.read_bytes()).hexdigest(); rp.write_text(json.dumps(r))
            argv = ['evaluate_ranking.py', str(jp), str(rp), str(out)]
            with patch('sys.argv', argv): self.assertEqual(m.main(), 1)
            report = json.loads(out.read_text())
            self.assertEqual(report['judgmentsSha256'], r['judgmentsSha256'])
            self.assertEqual(report['resultsSha256'], hashlib.sha256(rp.read_bytes()).hexdigest())
            before = out.read_bytes()
            with patch('sys.argv', argv): self.assertEqual(m.main(), 2)
            self.assertEqual(out.read_bytes(), before)
            out.unlink(); jp.write_text(json.dumps(j) + ' ')
            with patch('sys.argv', argv): self.assertEqual(m.main(), 2)
            self.assertFalse(out.exists())
