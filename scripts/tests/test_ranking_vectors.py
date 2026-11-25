import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('vectors', Path(__file__).parents[1] / 'prepare_ranking_vectors.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class RankingLabelsTest(unittest.TestCase):
    def test_only_catalog_and_query_labels_not_profile_text_are_encoded(self):
        corpus = {'skills': ['Python', 'Java', 'Java'],
                  'queries': [{'required': 'COBOL', 'desired': None},
                              {'required': 'Java', 'desired': 'SQL'}],
                  'profiles': [{'text': 'Nom : PRIVATE_MARKER; âge 42; contact secret@example.com'}]}
        self.assertEqual(m.catalog_labels(corpus), ['COBOL', 'Java', 'Python', 'SQL'])
        corpus['profiles'][0]['text'] = 'Different private identity'
        self.assertEqual(m.catalog_labels(corpus), ['COBOL', 'Java', 'Python', 'SQL'])
