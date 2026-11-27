"""Render paired synthetic ranking profiles and run actual parsers/OCR offline."""
import hashlib
import json
import os
from pathlib import Path
import platform
import sys
import tempfile
import time
from prepare_model_evaluation import pdf_text, docx, scanned, parser, ocr, pypdf

ROOT = Path(__file__).resolve().parents[1]
FORMATS = ('MD', 'DOCX_FREE', 'PDF_TEXT', 'PDF_SCAN')


def main(output):
    path = ROOT / 'tests/fixtures/ranking/corpus.json'
    corpus = json.loads(path.read_text())
    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        for profile in corpus['profiles']:
            lines = ['Title: Synthetic profile ' + profile['id'],
                     *['Skill: ' + skill for skill in profile['skills']]]
            digital = pdf_text(lines)
            files = [('MD', 'text/markdown', '\n'.join(lines).encode()),
                     ('DOCX_FREE', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', docx(lines)),
                     ('PDF_TEXT', 'application/pdf', digital),
                     ('PDF_SCAN', 'application/pdf', scanned(digital, directory))]
            for fmt, media, data in files:
                source = directory / 'input'; source.write_bytes(data)
                start = time.perf_counter(); result = parser.extract(str(source), media)
                if result['state'] != 'EXTRACTED':
                    raise ValueError('Parser did not extract synthetic document')
                if fmt == 'PDF_SCAN' and (not result.get('ocr') or any(not b['source'].startswith('ocr:') for b in result['blocks'])):
                    raise ValueError('Scan did not exercise actual OCR')
                cases.append({'id': profile['id'], 'format': fmt,
                              'documentSha256': hashlib.sha256(data).hexdigest(),
                              'blocks': result['blocks'], 'ocr': result.get('ocr', []),
                              'elapsedMillis': round((time.perf_counter() - start) * 1000, 3)})
            if len(cases) % 40 == 0:
                print(f'Parsed {len(cases)} synthetic documents', flush=True)
    report = {'schema': 'ranking-formats-v1', 'synthetic': True,
              'corpusSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
              'runtime': {'python': platform.python_version(), 'platform': platform.platform(),
                          'logicalCpus': os.cpu_count(), 'pypdf': pypdf.__version__, 'ocrVersions': ocr.versions()},
              'cases': cases,
              'limits': 'Four simple layouts; synthetic paired profiles, not independent representative CVs. AV and sandbox verified separately.'}
    Path(output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'Ranking formats: {len(cases)} documents parsed with actual engines')


if __name__ == '__main__':
    main(sys.argv[1])
