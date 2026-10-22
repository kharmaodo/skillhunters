"""Build-time download only. Exact upstream revision and content hashes are mandatory."""
import hashlib
import json
from pathlib import Path
import sys
import urllib.request

root = Path(sys.argv[1])
root.mkdir(parents=True, exist_ok=True)
manifest = json.loads(Path(__file__).with_name('manifest.json').read_text())
for name, info in manifest['files'].items():
    target = root / name
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == info['sha256']:
        continue
    url = f"https://huggingface.co/{manifest['repository']}/resolve/{manifest['revision']}/{info['source']}"
    temp = target.with_suffix('.download')
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(url, timeout=60) as response, temp.open('wb') as output:
        while block := response.read(1024 * 1024):
            size += len(block)
            if size > 150 * 1024 * 1024:
                raise ValueError('MODEL_SIZE')
            digest.update(block)
            output.write(block)
    if digest.hexdigest() != info['sha256']:
        temp.unlink()
        raise ValueError('MODEL_HASH')
    temp.replace(target)
