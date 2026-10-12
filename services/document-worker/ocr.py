"""Local PDF page OCR, executed only inside the antivirus-gated parser sandbox."""
import csv
import hashlib
import io
from pathlib import Path
import subprocess
import tempfile
import time

class OcrUnavailable(Exception):
    pass

class OcrLimit(Exception):
    pass

MODEL_DIR = Path('/usr/share/tesseract-ocr/5/tessdata')
MAX_PAGES = 20


def run(command, **kwargs):
    try:
        return subprocess.run(command, check=True, stderr=subprocess.DEVNULL, **kwargs)
    except (OSError, subprocess.CalledProcessError) as error:
        raise OcrUnavailable() from error


def versions():
    try:
        model = ';'.join(language + '=' + hashlib.sha256((MODEL_DIR / (language + '.traineddata')).read_bytes()).hexdigest()
                         for language in ('fra', 'eng'))
        engine = run(['tesseract', '--version'], stdout=subprocess.PIPE).stdout.decode().splitlines()[0]
        renderer = subprocess.run(['pdftoppm', '-v'], check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.decode().splitlines()[0]
        return engine[:120], renderer[:120], model
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise OcrUnavailable() from error


def parse_tsv(tsv):
    lines = {}
    weighted = 0.0
    weight = 0
    words = 0
    for row in csv.DictReader(io.StringIO(tsv), delimiter='\t', quoting=csv.QUOTE_NONE):
        if row['level'] != '5' or not row['text'].strip():
            continue
        text = row['text'].strip()
        confidence = float(row['conf'])
        if not 0 <= confidence <= 100:
            raise OcrUnavailable()
        key = tuple(row[k] for k in ('block_num', 'par_num', 'line_num'))
        lines.setdefault(key, []).append(text)
        weighted += confidence * len(text)
        weight += len(text)
        words += 1
        if weight > 500_000 or words > 50000:
            raise OcrLimit()
    text = '\n'.join(' '.join(line) for line in lines.values())
    confidence = round(weighted / weight, 2) if weight else None
    warnings = []
    if not text:
        warnings.append('OCR_NO_TEXT')
    elif confidence < 70:
        warnings.append('OCR_LOW_CONFIDENCE')
    if 0 < words < 5:
        warnings.append('OCR_SPARSE_TEXT')
    return text, confidence, warnings


def page(path, number, provenance):
    started = time.monotonic()
    engine, renderer, model = provenance
    with tempfile.TemporaryDirectory(prefix='ocr-page-') as directory:
        prefix = str(Path(directory) / 'page')
        # Bounds raster dimensions before any image is handed to Tesseract.
        run(['pdftoppm', '-f', str(number), '-l', str(number), '-singlefile', '-scale-to', '2500',
             '-gray', '-png', str(path), prefix], stdout=subprocess.DEVNULL)
        output = str(Path(directory) / 'recognized')
        run(['tesseract', prefix + '.png', output, '--tessdata-dir', str(MODEL_DIR), '-l', 'fra+eng',
             '--psm', '3', 'tsv'], stdout=subprocess.DEVNULL)
        result_path = Path(output + '.tsv')
        if result_path.stat().st_size > 4 * 1024**2:
            raise OcrLimit()
        text, confidence, warnings = parse_tsv(result_path.read_text(encoding='utf-8'))
    return text, {'page': number, 'confidence': confidence, 'reviewRequired': bool(warnings),
                  'durationMs': round((time.monotonic() - started) * 1000),
                  'engineVersion': engine, 'rendererVersion': renderer, 'modelVersion': model,
                  'warnings': warnings}
