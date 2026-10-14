package com.skillhunters;

import com.skillhunters.documents.*;
import com.skillhunters.exports.WordRenderer;
import com.skillhunters.talent.ReviewService.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WordRendererTest {
    record Fixture(ExtractionService.Revision source,Revision review) {}
    Fixture fixture(boolean longer) {
        String text="Nom : Profil synthétique & <XML>\nTitre : Développeur synthétique\nCompétence : Java 2,5\n";
        if(longer) {
            text+="Formation : Diplôme synthétique\nLangue : Français professionnel déclaré\nCertification : Examen synthétique mentionné\n";
            for(int i=1;i<=8;i++) text+="Compétence : Technologie synthétique "+i+" 2\n";
            for(int i=1;i<=8;i++) text+="Expérience : Projet synthétique "+i+". "+"Conception de composants et tests documentés avec une équipe de projet. ".repeat(4)+"\n";
        }
        var proposal=new StructuredExtractor().extract(List.of(new DocumentAnalyzer.Block("document",text)));
        UUID sourceId=UUID.randomUUID(),actor=UUID.randomUUID();var source=new ExtractionService.Revision(sourceId,UUID.randomUUID(),"a".repeat(64),"b".repeat(64),"synthetic","ClamAV synthetic",actor,OffsetDateTime.now(),proposal);
        var items=new ArrayList<Item>();
        for(int i=0;i<proposal.claims().size();i++) {
            var c=proposal.claims().get(i);boolean unknown=c.status()==StructuredExtractor.State.UNKNOWN;
            items.add(new Item(i,c.normalized(),unknown?Decision.UNKNOWN:Decision.VALIDATED,unknown?Precision.UNKNOWN:c.field()==StructuredExtractor.Field.DECLARED_LEVEL?Precision.DECLARED_LEVEL:Precision.TEXT,
                c.field()==StructuredExtractor.Field.EXPERIENCE?new Period("2024-01","2025-06",DatePrecision.MONTH,Kind.ACADEMIC):null,unknown?null:"Revue synthétique"));
        }
        return new Fixture(source,new Revision(UUID.randomUUID(),UUID.randomUUID(),sourceId,null,2,"PUBLISHED",items,List.of(),actor,OffsetDateTime.now(),"Test synthétique"));
    }
    Map<String,byte[]> unzip(byte[] bytes) throws IOException {
        var entries=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {for(ZipEntry e;(e=zip.getNextEntry())!=null;) entries.put(e.getName(),zip.readAllBytes());}return entries;
    }
    @Test void absentSectionsNeverContainTemplateExamplesAndXmlValuesRemainText() throws Exception {
        var f=fixture(false);byte[] bytes=new WordRenderer().render(f.source(),f.review());var files=unzip(bytes);
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(files.get("word/document.xml")));
        String text=document.getDocumentElement().getTextContent();
        assertThat(text).contains("Profil synthétique & <XML>","Java","2,5","CertificationsNon renseigné").doesNotContain("{{","Examen synthétique");
        assertThat(document.getElementsByTagName("XML").getLength()).isZero();
        assertThat(files.keySet()).noneMatch(k->k.contains("customXml")||k.contains("thumbnail")||k.contains("comments")||k.contains("vba"));
        for(var part:files.entrySet()) {
            var xml=new String(part.getValue(),StandardCharsets.UTF_8);assertThat(xml).doesNotContain("TargetMode=\"External\"","lastModifiedBy","<dc:creator","w:rsid");
            factory.newDocumentBuilder().parse(new ByteArrayInputStream(part.getValue()));
        }
        Files.createDirectories(Path.of("target/sh11-qa"));Files.write(Path.of("target/sh11-qa/export-short.docx"),bytes);
    }
    @Test void repeatedRowsAndLongNarrativesPreservePeriodsAndDeclarativeLevels() throws Exception {
        var f=fixture(true);var bytes=new WordRenderer().render(f.source(),f.review());
        var xml=new String(unzip(bytes).get("word/document.xml"),StandardCharsets.UTF_8);
        assertThat(xml).contains("Projet synthétique 8","Technologie synthétique 8","2024-01 à 2025-06","Projet académique / études","obtention non confirmée","w:tblHeader").doesNotContain("{{");
        Files.createDirectories(Path.of("target/sh11-qa"));Files.write(Path.of("target/sh11-qa/export-long.docx"),bytes);
    }
}
