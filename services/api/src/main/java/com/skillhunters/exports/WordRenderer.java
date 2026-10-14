package com.skillhunters.exports;

import com.skillhunters.documents.ExtractionService;
import com.skillhunters.documents.StructuredExtractor.Field;
import com.skillhunters.talent.ReviewService;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;

/** Trusted, versioned OOXML template only. Candidate values become text nodes, never XML or fields. */
@Component
public class WordRenderer {
    public static final String TEMPLATE="skillhunters-cv-template-v1";
    public static final String VERSION="sh11-ooxml-1";
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String UNKNOWN="Non renseigné";
    private final Map<String,byte[]> template=new LinkedHashMap<>();
    private final String templateHash;
    public WordRenderer() {
        try(var in=getClass().getResourceAsStream("/templates/cv/"+TEMPLATE+".docx")) {
            if(in==null) throw new IllegalStateException("TEMPLATE_MISSING");
            byte[] bytes=in.readAllBytes();templateHash=sha(bytes);
            try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for(ZipEntry e;(e=zip.getNextEntry())!=null;) template.put(e.getName(),zip.readAllBytes());
            }
        }catch(IOException e){throw new IllegalStateException("TEMPLATE_UNAVAILABLE");}
    }
    public String templateHash(){return templateHash;}
    public static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException("DIGEST_UNAVAILABLE");}
    }
    public byte[] render(ExtractionService.Revision source,ReviewService.Revision revision) {
        try {
            Document doc=parse(template.get("word/document.xml"));Element body=(Element)doc.getElementsByTagNameNS(W,"body").item(0);
            List<Element> blocks=new ArrayList<>();for(Node n=body.getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element e) blocks.add(e);
            if(blocks.size()!=24) throw new IllegalStateException("TEMPLATE_CONTRACT_CHANGED");
            while(body.hasChildNodes()) body.removeChild(body.getFirstChild());
            body.appendChild(paragraph(blocks.get(0),first(source,revision,Field.FULL_NAME,"Identité non renseignée")));
            body.appendChild(paragraph(blocks.get(1),first(source,revision,Field.TITLE,"Titre professionnel non renseigné")));
            // Contact, location and summary are absent from the published assertion schema; never copy live profile data.
            body.appendChild(blocks.get(5).cloneNode(true));
            var education=selected(source,revision,Field.EDUCATION);
            if(education.isEmpty()) body.appendChild(paragraph(blocks.get(6),UNKNOWN));
            for(var i:education) body.appendChild(paragraph(blocks.get(6),i.value()+" — "+period(i.period())));
            body.appendChild(blocks.get(7).cloneNode(true));var languages=selected(source,revision,Field.LANGUAGE);
            if(languages.isEmpty()) body.appendChild(paragraph(blocks.get(8),UNKNOWN));
            for(var i:languages) body.appendChild(paragraph(blocks.get(8),i.value()));
            body.appendChild(blocks.get(9).cloneNode(true));
            Element table=(Element)blocks.get(10).cloneNode(true);
            var rows=table.getElementsByTagNameNS(W,"tr");Element header=(Element)rows.item(0);Element prototype=(Element)rows.item(1);
            Element trPr=doc.createElementNS(W,"w:trPr");trPr.appendChild(doc.createElementNS(W,"w:tblHeader"));header.insertBefore(trPr,header.getFirstChild());
            table.removeChild(prototype);var skills=selected(source,revision,Field.SKILL);
            if(skills.isEmpty()) table.appendChild(skillRow(prototype,UNKNOWN,UNKNOWN));
            for(var skill:skills) {
                int item=source.proposal().claims().get(skill.sourceIndex()).item();String level=UNKNOWN;
                for(var l:selected(source,revision,Field.DECLARED_LEVEL)) if(source.proposal().claims().get(l.sourceIndex()).item()==item) level=l.value().replace('.',',');
                table.appendChild(skillRow(prototype,skill.value(),level));
            }
            body.appendChild(table);body.appendChild(blocks.get(11).cloneNode(true));
            body.appendChild(blocks.get(12).cloneNode(true));var experiences=selected(source,revision,Field.EXPERIENCE);
            if(experiences.isEmpty()) body.appendChild(paragraph(blocks.get(13),UNKNOWN));
            for(var i:experiences) {
                body.appendChild(paragraph(blocks.get(13),i.value()));
                body.appendChild(paragraph(blocks.get(14),kind(i.period())+" — "+period(i.period())));
            }
            body.appendChild(blocks.get(18).cloneNode(true));var certifications=selected(source,revision,Field.CERTIFICATION_DECLARATION);
            if(certifications.isEmpty()) body.appendChild(paragraph(blocks.get(19),UNKNOWN));
            for(var i:certifications) body.appendChild(paragraph(blocks.get(19),i.value()+" — Mention déclarée ; obtention non confirmée."));
            body.appendChild(blocks.get(20).cloneNode(true));body.appendChild(paragraph(blocks.get(21),"Durée globale et ancienneté par compétence : non renseignées. Les niveaux déclarés ne sont pas des durées."));
            body.appendChild(blocks.get(22).cloneNode(true));body.appendChild(blocks.get(23).cloneNode(true));
            scrub(doc.getDocumentElement());
            Map<String,byte[]> output=new LinkedHashMap<>();output.put("word/document.xml",xml(doc));
            for(String part:List.of("word/styles.xml","word/numbering.xml","word/fontTable.xml","word/theme/theme1.xml")) {
                var parsed=parse(template.get(part));scrub(parsed.getDocumentElement());output.put(part,xml(parsed));
            }
            output.put("docProps/core.xml",utf("<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\"/>"));
            output.put("docProps/app.xml",utf("<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>Skill Hunters</Application></Properties>"));
            output.put("_rels/.rels",utf(rels(List.of(new String[]{"officeDocument","word/document.xml"},new String[]{"core-properties","docProps/core.xml"},new String[]{"extended-properties","docProps/app.xml"}))));
            output.put("word/_rels/document.xml.rels",utf(rels(List.of(new String[]{"styles","styles.xml"},new String[]{"numbering","numbering.xml"},new String[]{"fontTable","fontTable.xml"},new String[]{"theme","theme/theme1.xml"}))));
            output.put("[Content_Types].xml",utf("""
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                <Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
                <Override PartName="/word/fontTable.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.fontTable+xml"/>
                <Override PartName="/word/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
                <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
                </Types>"""));
            var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)) {
                for(var entry:output.entrySet()){var e=new ZipEntry(entry.getKey());e.setTime(0);zip.putNextEntry(e);zip.write(entry.getValue());zip.closeEntry();}
            }
            return bytes.toByteArray();
        }catch(Rejected e){throw e;}catch(Exception e){throw new IllegalStateException("WORD_EXPORT_FAILED");}
    }
    private static List<ReviewService.Item> selected(ExtractionService.Revision s,ReviewService.Revision r,Field field) {
        return r.items().stream().filter(i->i.decision()==ReviewService.Decision.VALIDATED&&s.proposal().claims().get(i.sourceIndex()).field()==field).toList();
    }
    private static String first(ExtractionService.Revision s,ReviewService.Revision r,Field f,String fallback) {
        var list=selected(s,r,f);return list.isEmpty()?fallback:list.getFirst().value();
    }
    private static String period(ReviewService.Period p) {
        if(p==null||p.precision()==ReviewService.DatePrecision.UNKNOWN) return "Période non renseignée";
        String precision=switch(p.precision()){case DAY->"jour";case MONTH->"mois";case YEAR->"année";default->"inconnue";};
        return p.start()+" à "+p.end()+" (précision : "+precision+")";
    }
    private static String kind(ReviewService.Period p) {
        if(p==null) return "Type non renseigné";
        return switch(p.kind()){case EMPLOYMENT->"Emploi";case INTERNSHIP->"Stage";case FREELANCE->"Freelance";case ACADEMIC->"Projet académique / études";case UNKNOWN->"Type non renseigné";};
    }
    private static Element skillRow(Element prototype,String skill,String level) {
        Element row=(Element)prototype.cloneNode(true);var cells=row.getElementsByTagNameNS(W,"tc");
        var values=List.of(UNKNOWN,skill,level);for(int i=0;i<3;i++) {var cell=(Element)cells.item(i);var p=(Element)cell.getElementsByTagNameNS(W,"p").item(0);cell.replaceChild(paragraph(p,values.get(i)),p);}
        return row;
    }
    private static Element paragraph(Element prototype,String value) {
        Element p=(Element)prototype.cloneNode(false);Document d=p.getOwnerDocument();
        for(Node n=prototype.getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element e&&"pPr".equals(e.getLocalName())) p.appendChild(n.cloneNode(true));
        if(value.codePoints().anyMatch(c->!(c==9||c==10||c==13||c>=32&&c<=0xD7FF||c>=0xE000&&c<=0xFFFD||c>=0x10000&&c<=0x10FFFF)))
            throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_EXPORT_TEXT");
        String[] lines=value.split("\\R",-1);for(int i=0;i<lines.length;i++) {Element run=d.createElementNS(W,"w:r");if(i>0) run.appendChild(d.createElementNS(W,"w:br"));
            Element text=d.createElementNS(W,"w:t");text.setAttributeNS(XMLConstants.XML_NS_URI,"xml:space","preserve");text.appendChild(d.createTextNode(lines[i]));run.appendChild(text);p.appendChild(run);}
        return p;
    }
    private static Document parse(byte[] bytes) throws Exception {
        var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private static void scrub(Element e) {
        var attrs=e.getAttributes();for(int i=attrs.getLength()-1;i>=0;i--){Node a=attrs.item(i);if(a.getLocalName()!=null&&a.getLocalName().startsWith("rsid"))e.removeAttributeNode((Attr)a);}
        for(Node n=e.getFirstChild();n!=null;) {Node next=n.getNextSibling();if(n instanceof Element child) {if(child.getLocalName().startsWith("rsid")) e.removeChild(child);else scrub(child);} n=next;}
    }
    private static byte[] xml(Document doc) throws Exception {
        var f=TransformerFactory.newInstance();f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
        var out=new ByteArrayOutputStream();f.newTransformer().transform(new DOMSource(doc),new StreamResult(out));return out.toByteArray();
    }
    private static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static String rels(List<String[]> relationships) {
        var s=new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");int i=0;
        for(var r:relationships){String type=r[0].equals("core-properties")?"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties":"http://schemas.openxmlformats.org/officeDocument/2006/relationships/"+r[0];
            s.append("<Relationship Id=\"rId").append(++i).append("\" Type=\"").append(type).append("\" Target=\"").append(r[1]).append("\"/>");}
        return s.append("</Relationships>").toString();
    }
}
