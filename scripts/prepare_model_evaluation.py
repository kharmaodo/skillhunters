"""Generate synthetic paired documents and measure the production parsers.

Run in the pinned document-worker image, --network none. No antivirus bypass in
production: this offline parser qualification is separate from the real AV smoke.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import time
import zipfile
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'services/document-worker'))
import parser
import ocr
import pypdf
from pypdf import PdfWriter
from pypdf.generic import DictionaryObject, NameObject, NumberObject, DecodedStreamObject


def pdf_text(lines):
    pdf = PdfWriter()
    page = pdf.add_blank_page(width=595, height=842)
    font = DictionaryObject({NameObject('/Type'): NameObject('/Font'), NameObject('/Subtype'): NameObject('/Type1'), NameObject('/BaseFont'): NameObject('/Courier')})
    page[NameObject('/Resources')] = DictionaryObject({NameObject('/Font'): DictionaryObject({NameObject('/F1'): pdf._add_object(font)})})
    stream = DecodedStreamObject()
    # Corpus is fixed ASCII; escape PDF literal strings nevertheless.
    escaped = [s.replace('\\', '\\\\').replace('(', '\\(').replace(')', '\\)') for s in lines]
    stream.set_data(('BT /F1 18 Tf 45 760 Td 30 TL ' + ' T* '.join(f'({s}) Tj' for s in escaped) + ' ET').encode('ascii'))
    page[NameObject('/Contents')] = pdf._add_object(stream)
    out = io.BytesIO(); pdf.write(out)
    return out.getvalue()


def docx(lines):
    out = io.BytesIO()
    rows = ''.join('<w:tr><w:tc><w:p><w:r><w:t>' + escape(s) + '</w:t></w:r></w:p></w:tc></w:tr>' for s in lines)
    with zipfile.ZipFile(out, 'w') as z:
        z.writestr('[Content_Types].xml', '<Types/>')
        z.writestr('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:tbl>' + rows + '</w:tbl></w:body></w:document>')
    return out.getvalue()


def scanned(data, directory):
    source = directory / 'digital.pdf'; source.write_bytes(data)
    # Poppler writes P6 PPM: fixed header, then raw RGB. No additional imaging dependency.
    subprocess.run(['pdftoppm', '-r', '150', '-singlefile', str(source), str(directory / 'raster')], check=True, capture_output=True)
    with (directory / 'raster.ppm').open('rb') as f:
        assert f.readline().strip() == b'P6'
        width, height = map(int, f.readline().split()); assert f.readline().strip() == b'255'
        pixels = f.read()
    assert len(pixels) == width * height * 3
    pdf = PdfWriter(); page = pdf.add_blank_page(width=595, height=842)
    image = DecodedStreamObject(); image.set_data(pixels)
    image.update({NameObject('/Type'): NameObject('/XObject'), NameObject('/Subtype'): NameObject('/Image'), NameObject('/Width'): NumberObject(width), NameObject('/Height'): NumberObject(height), NameObject('/ColorSpace'): NameObject('/DeviceRGB'), NameObject('/BitsPerComponent'): NumberObject(8)})
    page[NameObject('/Resources')] = DictionaryObject({NameObject('/XObject'): DictionaryObject({NameObject('/I'): pdf._add_object(image.flate_encode())})})
    stream = DecodedStreamObject(); stream.set_data(b'q 595 0 0 842 0 0 cm /I Do Q')
    page[NameObject('/Contents')] = pdf._add_object(stream)
    out = io.BytesIO(); pdf.write(out); return out.getvalue()


def main(output):
    path = ROOT / 'tests/fixtures/evaluation/corpus.json'; corpus = json.loads(path.read_text())
    rows = []
    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        for variant in corpus['variants']:
            lines = [f"Name: {variant['name']}", f"Age: {variant['age']}", *corpus['professionalLines']]
            digital = pdf_text(lines)
            files = [('markdown', 'text/markdown', '\n'.join(lines).encode()), ('docx-table', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', docx(lines)), ('pdf-text', 'application/pdf', digital), ('pdf-scan', 'application/pdf', scanned(digital, directory))]
            for format_name, media, data in files:
                source = directory / 'input'; source.write_bytes(data); start = time.perf_counter()
                result = parser.extract(str(source), media)
                if result['state'] != 'EXTRACTED':
                    raise ValueError(f'Extraction failed: {format_name}/{variant["id"]}')
                if format_name == 'pdf-scan' and (not result.get('ocr') or any(not b['source'].startswith('ocr:') for b in result['blocks'])):
                    raise ValueError('Scan fixture did not exercise real OCR')
                rows.append({'id': format_name + '/' + variant['id'], 'format': format_name, 'variant': variant['id'], 'documentSha256': hashlib.sha256(data).hexdigest(), 'blocks': result['blocks'], 'ocr': result.get('ocr', []), 'elapsedMillis': round((time.perf_counter()-start)*1000, 3)})
    report = {'schema': 'sh35-formats-v1', 'corpusSha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'synthetic': True, 'runtime': {'platform': platform.platform(), 'python': platform.python_version(), 'logicalCpus': os.cpu_count(), 'pypdf': pypdf.__version__, 'ocrVersions': ocr.versions()}, 'cases': rows, 'limits': 'Offline real parsers; AV and sandbox security verified separately. Four simple layouts, not representative production CVs.'}
    Path(output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'SH35: {len(rows)} paired documents parsed across four formats')


if __name__ == '__main__':
    main(sys.argv[1])
