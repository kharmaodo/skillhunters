"""Prepare real pinned CPU embeddings for the normalized ranking corpus."""
import hashlib
import json
import os
from pathlib import Path
import platform
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def catalog_labels(corpus):
    labels = set(corpus['skills'])
    for query in corpus['queries']:
        labels.add(query['required'])
        if query['desired'] is not None:
            labels.add(query['desired'])
    return sorted(labels)


def main():
    sys.path.insert(0, str(ROOT / 'services/embedding-worker'))
    from engine import Engine
    corpus_path = ROOT / 'tests/fixtures/ranking/corpus.json'
    manifest_path = ROOT / 'services/embedding-worker/manifest.json'
    corpus = json.loads(corpus_path.read_text())
    labels = catalog_labels(corpus)
    begin = time.perf_counter()
    engine = Engine(os.environ['MODEL_DIRECTORY'])
    result = engine.encode(labels)
    output = {
        'corpusSha256': hashlib.sha256(corpus_path.read_bytes()).hexdigest(),
        'modelManifestSha256': hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        'model': result['model'], 'vectors': dict(zip(labels, result['vectors'])),
        'runtime': {'python': platform.python_version(), 'platform': platform.platform(),
                    'logicalCpus': os.cpu_count(), 'inferenceThreads': 1,
                    'elapsedSeconds': time.perf_counter() - begin}}
    Path(sys.argv[1]).write_text(json.dumps(output, allow_nan=False) + '\n')
    print(f'Encoded {len(labels)} catalog labels with the pinned CPU model')


if __name__ == '__main__':
    main()
