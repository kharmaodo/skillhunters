import hashlib
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import parser
import worker
import ocr
from pypdf import PdfReader, PdfWriter
from pypdf.generic import DictionaryObject,NameObject,DecodedStreamObject

class OcrTest(unittest.TestCase):
    def test_real_scan_in_networkless_parser_preserves_sources_models_and_unknown_dates(self):
        data=Path(__file__).with_name('scanned.pdf').read_bytes()
        with patch.object(worker,'scan',return_value=(True,'ClamAV synthetic')):
            result=worker.analyze(data,'application/pdf',hashlib.sha256(data).hexdigest())
        self.assertEqual(result['state'],'EXTRACTED',result)
        self.assertEqual(result['blocks'][0]['source'],'ocr:page:1')
        text=result['blocks'][0]['text']
        self.assertIn('Java',text);self.assertIn('2.5',text);self.assertIn('inconnues',text)
        page=result['ocr'][0]
        self.assertEqual(page['page'],1);self.assertGreater(page['durationMs'],0)
        self.assertIn('tesseract',page['engineVersion']);self.assertIn('fra=',page['modelVersion']);self.assertIn('eng=',page['modelVersion'])
    def test_mixed_pdf_ocr_only_for_image_page(self):
        pdf=PdfWriter();page=pdf.add_blank_page(width=200,height=200)
        font=DictionaryObject({NameObject('/Type'):NameObject('/Font'),NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Helvetica')})
        page[NameObject('/Resources')]=DictionaryObject({NameObject('/Font'):DictionaryObject({NameObject('/F1'):pdf._add_object(font)})})
        stream=DecodedStreamObject();stream.set_data(b'BT /F1 12 Tf 10 100 Td (Digital Java text) Tj ET')
        page[NameObject('/Contents')]=pdf._add_object(stream)
        pdf.add_page(PdfReader(Path(__file__).with_name('scanned.pdf')).pages[0])
        with tempfile.NamedTemporaryFile(suffix='.pdf') as f:
            pdf.write(f);f.flush()
            with patch.object(ocr,'page',wraps=ocr.page) as convert:
                result=parser.extract(f.name,'application/pdf')
            self.assertEqual(convert.call_count,1);self.assertEqual(convert.call_args.args[1],2)
        self.assertEqual([b['source'] for b in result['blocks']],['page:1','ocr:page:2'])
    def test_low_confidence_and_empty_results_require_review(self):
        header='level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n'
        text,confidence,warnings=ocr.parse_tsv(header+'5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t12.5\tJava\n')
        self.assertEqual(text,'Java');self.assertEqual(confidence,12.5)
        self.assertIn('OCR_LOW_CONFIDENCE',warnings);self.assertIn('OCR_SPARSE_TEXT',warnings)
        self.assertEqual(ocr.parse_tsv(header),('',None,['OCR_NO_TEXT']))
    def test_missing_french_model_does_not_silently_fall_back_to_english(self):
        with patch.object(ocr,'MODEL_DIR',Path('/nonexistent-model-directory')):
            with self.assertRaises(ocr.OcrUnavailable):ocr.versions()
    def test_ocr_page_budget_checked_before_rendering(self):
        pdf=PdfWriter()
        for _ in range(21):pdf.add_blank_page(width=100,height=100)
        with tempfile.NamedTemporaryFile(suffix='.pdf') as f:
            pdf.write(f);f.flush()
            with patch.object(ocr,'page') as convert:
                with self.assertRaisesRegex(parser.Refused,'OCR_PAGE_LIMIT'):parser.extract(f.name,'application/pdf')
                convert.assert_not_called()
    def test_antivirus_failure_prevents_ocr(self):
        data=Path(__file__).with_name('scanned.pdf').read_bytes()
        with patch.object(worker,'scan',side_effect=worker.Unavailable()),patch.object(worker.subprocess,'Popen') as child:
            result=worker.analyze(data,'application/pdf',hashlib.sha256(data).hexdigest())
        self.assertEqual(result['state'],'RETRY');child.assert_not_called()

if __name__=='__main__':unittest.main()
