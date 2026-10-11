import datetime as dt
import hashlib
import io
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import parser
import worker
from pypdf import PdfWriter
from pypdf.generic import DictionaryObject, NameObject, TextStringObject

DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'

def docx(extra=None, document=None):
    data=io.BytesIO()
    with zipfile.ZipFile(data,'w') as z:
        z.writestr('[Content_Types].xml','<Types/>')
        z.writestr('word/document.xml',document or '''<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
        <w:p><w:r><w:t>Profil synthétique</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>Java : 2,5</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>''')
        for name,value in (extra or {}).items(): z.writestr(name,value)
    return data.getvalue()

class WorkerTest(unittest.TestCase):
    def extract(self,data,kind):
        with tempfile.NamedTemporaryFile() as f:
            f.write(data);f.flush()
            return parser.extract(f.name,kind)
    def test_docx_table_and_merged_cell_keep_source_and_declared_rating(self):
        result=self.extract(docx(),DOCX)
        self.assertEqual([b['text'] for b in result['blocks']],['Profil synthétique','Java : 2,5'])
        self.assertIn('/tbl[',result['blocks'][1]['source'])
        self.assertIn('/tc[',result['blocks'][1]['source'])
    def test_external_link_macro_and_xml_entities_are_refused(self):
        for extra in [{'word/_rels/document.xml.rels':'<Relationships><Relationship TargetMode="External" Target="http://example.com"/></Relationships>'},
                      {'word/vbaProject.bin':'macro'}]:
            with self.assertRaises(parser.Refused): self.extract(docx(extra),DOCX)
        with self.assertRaises(Exception):
            self.extract(docx(document='<!DOCTYPE x [<!ENTITY a SYSTEM "file:///etc/passwd">]><x>&a;</x>'),DOCX)
    def test_pdf_scan_requires_ocr_and_encrypted_pdf_is_rejected(self):
        pdf=PdfWriter();pdf.add_blank_page(width=100,height=100)
        data=io.BytesIO();pdf.write(data)
        result=self.extract(data.getvalue(),'application/pdf')
        self.assertEqual(result['ocrPages'],[1]);self.assertEqual(result['state'],'OCR_REQUIRED')
        pdf.encrypt('synthetic'); data=io.BytesIO();pdf.write(data)
        with self.assertRaisesRegex(parser.Refused,'ENCRYPTED'): self.extract(data.getvalue(),'application/pdf')
    def test_pdf_active_content_is_rejected(self):
        pdf=PdfWriter();pdf.add_blank_page(width=100,height=100);pdf.add_js('app.alert("test")')
        data=io.BytesIO();pdf.write(data)
        with self.assertRaisesRegex(parser.Refused,'ACTIVE_CONTENT'): self.extract(data.getvalue(),'application/pdf')
    def test_pdf_text_extraction_keeps_page_reference(self):
        from pypdf.generic import DecodedStreamObject
        pdf=PdfWriter();page=pdf.add_blank_page(width=200,height=200)
        font=DictionaryObject({NameObject('/Type'):NameObject('/Font'),NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Helvetica')})
        page[NameObject('/Resources')]=DictionaryObject({NameObject('/Font'):DictionaryObject({NameObject('/F1'):pdf._add_object(font)})})
        stream=DecodedStreamObject();stream.set_data(b'BT /F1 12 Tf 10 100 Td (Java synthetic) Tj ET')
        page[NameObject('/Contents')]=pdf._add_object(stream)
        data=io.BytesIO();pdf.write(data)
        result=self.extract(data.getvalue(),'application/pdf')
        self.assertEqual(result['blocks'][0],{'source':'page:1','text':'Java synthetic'})
    def test_page_and_archive_bombs_are_bounded(self):
        pdf=PdfWriter()
        for _ in range(101):pdf.add_blank_page(width=100,height=100)
        data=io.BytesIO();pdf.write(data)
        with self.assertRaisesRegex(parser.Refused,'PAGE_LIMIT'):self.extract(data.getvalue(),'application/pdf')
        with self.assertRaisesRegex(parser.Refused,'ARCHIVE_LIMIT'):
            self.extract(docx({'huge.bin':b'X'*(parser.MAX_PART+1)}),DOCX)
    def test_antivirus_failure_and_detection_never_start_parser(self):
        data=b'# Synthetic'
        for outcome,state in [(worker.Unavailable(),'RETRY'),((False,'ClamAV test'),'REJECTED')]:
            with patch.object(worker,'scan',side_effect=outcome if isinstance(outcome,Exception) else None,return_value=outcome),patch.object(worker.subprocess,'Popen') as child:
                result=worker.analyze(data,'text/markdown',hashlib.sha256(data).hexdigest())
                self.assertEqual(result['state'],state);child.assert_not_called()
    def test_integrity_checked_before_scanning(self):
        with patch.object(worker,'scan') as scan:
            result=worker.analyze(b'test','text/markdown','invalid')
            self.assertEqual(result['code'],'INTEGRITY_MISMATCH');scan.assert_not_called()
    def test_real_parser_process_and_network_denial(self):
        version='ClamAV synthetic/1/'+dt.datetime.now(dt.timezone.utc).strftime('%a %b %d %H:%M:%S %Y')
        data=b'# Synthetic\nJava : 2.5'
        with patch.object(worker,'scan',return_value=(True,version)):
            result=worker.analyze(data,'text/markdown',hashlib.sha256(data).hexdigest())
            self.assertEqual(result['state'],'EXTRACTED');self.assertIn('2.5',result['blocks'][0]['text'])
        result=subprocess.run([sys.executable,str(Path(parser.__file__)),'--check-sandbox'],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stdout);self.assertEqual(result.stdout.strip(),'network-denied')
    def test_legacy_doc_conversion_in_real_sandbox(self):
        data=Path(__file__).with_name('synthetic.doc').read_bytes()
        with patch.object(worker,'scan',return_value=(True,'ClamAV test')):
            result=worker.analyze(data,'application/msword',hashlib.sha256(data).hexdigest())
        self.assertEqual(result['state'],'EXTRACTED',result)
        self.assertIn('Java declared level 2.5',result['blocks'][0]['text'])
        self.assertEqual(result['blocks'][0]['source'],'legacy-document')
    def test_stale_antivirus_signatures_fail_closed(self):
        class FakeSocket:
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def sendall(self,*args):pass
        with patch.object(worker,'connection',return_value=FakeSocket()),patch.object(worker,'receive',return_value='ClamAV 1.5.3/123/Mon Jan 01 00:00:00 2024'):
            with self.assertRaises(worker.Unavailable):worker.av_version()
    def test_markdown_is_not_html_rendered_and_invalid_utf8_rejected(self):
        result=self.extract(b'<script>alert(1)</script>','text/markdown')
        self.assertIn('<script>',result['blocks'][0]['text'])
        with self.assertRaises(UnicodeDecodeError):self.extract(b'\xff','text/markdown')

if __name__=='__main__':unittest.main()
