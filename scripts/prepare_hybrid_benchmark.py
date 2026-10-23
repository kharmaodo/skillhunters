"""Encode the pre-annotated synthetic corpus with the real pinned local model.

The Java integration benchmark consumes these vectors through its port while
executing production permissions, publication, projection, SQL and pagination.
No fabricated vectors and no production CVs are used.
"""
import hashlib
import json
import os
from pathlib import Path
import platform
import resource
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'services/embedding-worker'))
from engine import Engine

corpus_path = ROOT / 'tests/fixtures/search/hybrid-corpus.json'
corpus = json.loads(corpus_path.read_text())
texts = [s['name'] for s in corpus['skills']]
start = time.perf_counter()
engine = Engine(os.environ['MODEL_DIRECTORY'])
loaded = time.perf_counter()
result = engine.encode(texts)
output = {
    'corpusSha256': hashlib.sha256(corpus_path.read_bytes()).hexdigest(),
    'model': result['model'], 'vectors': dict(zip(texts, result['vectors'])),
    'runtime': {'platform': platform.platform(), 'python': platform.python_version(),
                'logicalCpus': os.cpu_count(), 'inferenceThreads': 1,
                'loadSeconds': loaded-start, 'encodeSeconds': time.perf_counter()-loaded,
                'maxRssKiB': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss}}
Path(sys.argv[1]).write_text(json.dumps(output, ensure_ascii=False, allow_nan=False))
print(f"Encoded {len(texts)} catalog labels for {len(corpus['queries'])} annotated queries")
