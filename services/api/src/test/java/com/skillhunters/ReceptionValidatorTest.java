package com.skillhunters;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import com.skillhunters.documents.ReceptionValidator;
import com.skillhunters.shared.ApiProblem.Rejected;
import static org.assertj.core.api.Assertions.*;

class ReceptionValidatorTest {
    final ReceptionValidator validator = new ReceptionValidator();
    byte[] zip(String extra, byte[] payload) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(bytes)) {
            for (String name : new String[]{"[Content_Types].xml", "_rels/.rels", "word/document.xml"}) {
                out.putNextEntry(new ZipEntry(name)); out.write("<synthetic/>".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
            if (extra != null) { out.putNextEntry(new ZipEntry(extra)); out.write(payload); out.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    void rejected(String name,String type,byte[] body,String code) {
        assertThatThrownBy(() -> validator.receive(new MockMultipartFile("files",name,type,body)))
            .isInstanceOfSatisfying(Rejected.class,e -> assertThat(e.code).isEqualTo(code));
    }
    @Test void checksExtensionMimeAndRealSignature() {
        rejected("cv.exe","application/octet-stream",new byte[]{1},"UNSUPPORTED_FORMAT");
        rejected("cv.pdf","text/plain","# CV".getBytes(),"MIME_MISMATCH");
        rejected("cv.pdf","application/pdf","not a PDF".getBytes(),"SIGNATURE_MISMATCH");
        rejected("../cv.md","text/markdown",new byte[]{65},"INVALID_FILE_NAME");
        rejected("cv.md","text/markdown",new byte[]{0},"INVALID_TEXT");
        rejected("cv.md","text/markdown",new byte[]{(byte)255},"INVALID_TEXT");
        rejected("cv.md","text/markdown",new byte[0],"EMPTY_FILE");
        rejected("cv.md","text/markdown",new byte[(int)ReceptionValidator.MAX_BYTES+1],"FILE_TOO_LARGE");
    }
    @Test void boundsArchiveAndRejectsMacroTraversalAndFakeDocx() throws Exception {
        rejected("cv.docx",null,zip("word/vbaProject.bin",new byte[]{1}),"UNSAFE_ARCHIVE");
        rejected("cv.docx",null,zip("../outside",new byte[]{1}),"UNSAFE_ARCHIVE");
        rejected("cv.docx",null,zip("word/bomb.xml",new byte[1024*1024]),"ARCHIVE_LIMIT");
        rejected("cv.docx",null,new byte[]{1,2,3},"UNSAFE_ARCHIVE");
    }
    @Test void acceptedEnvelopesProduceHashAndCleanTemporaryFiles() throws Exception {
        byte[] doc = new byte[512];
        System.arraycopy(java.util.HexFormat.of().parseHex("d0cf11e0a1b11ae1"),0,doc,0,8); doc[28]=(byte)254;doc[29]=(byte)255;
        String[] names={"cv.md","cv.pdf","cv.docx","cv.doc"};
        byte[][] contents={"# Synthetic CV".getBytes(),"%PDF-1.7\n%%EOF".getBytes(),zip(null,null),doc};
        for (int i=0;i<names.length;i++) {
            java.nio.file.Path path;
            try(var file=validator.receive(new MockMultipartFile("files",names[i],null,contents[i]))) {
                path=file.path();assertThat(path).exists();assertThat(file.sha256()).hasSize(64);assertThat(file.size()).isEqualTo(contents[i].length);
            }
            assertThat(path).doesNotExist();
        }
    }
}
