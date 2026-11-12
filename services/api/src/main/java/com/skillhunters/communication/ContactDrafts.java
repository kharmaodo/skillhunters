package com.skillhunters.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.documents.StructuredExtractor;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.talent.CandidateService;
import com.skillhunters.talent.ReviewService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

@Service
public class ContactDrafts {
    private final com.skillhunters.governance.AuditTrail trail;
    private final com.skillhunters.governance.CandidateGovernance governance;
    public static final List<String> VARIABLES=List.of("candidateFirstName","candidateLastName","recruiterName","companyName","jobTitle","jobDescription","workLocation","contractType","contactEmail","unsubscribeOrObjectionLink");
    private static final Set<String> OPTIONAL=Set.of("candidateFirstName","candidateLastName");
    private static final Pattern PLACEHOLDER=Pattern.compile("\\{\\{([a-zA-Z]+)}}");
    public record Template(String id,String version,String label,String subject,String body) {}
    public record Skill(int sourceIndex,String value) {}
    public record Context(List<Template> templates,List<String> variables,UUID publicationId,List<Skill> skills,String recipient) {}
    public record Input(UUID publicationId,String templateId,Map<String,String> variables,List<Integer> skillIndices,String subject,String body) {}
    public record Draft(long version,UUID publicationId,String templateId,String templateVersion,Map<String,String> variables,
                        List<Skill> matchingSkills,String recipient,String subject,String body,String htmlBody,
                        List<String> missingFields,boolean sendBlocked,OffsetDateTime createdAt) {}
    public record History(List<Draft> items,Integer nextCursor) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final ObjectMapper json;
    private final CandidateService candidates;private final ReviewService reviews;
    private final List<Template> templates;
    public ContactDrafts(JdbcClient db,TransactionTemplate tx,ObjectMapper json,CandidateService candidates,ReviewService reviews,com.skillhunters.governance.AuditTrail trail,com.skillhunters.governance.CandidateGovernance governance) {this.trail=trail;this.governance=governance;
        this.db=db;this.tx=tx;this.json=json;this.candidates=candidates;this.reviews=reviews;
        templates=List.of(template("professional","Premier contact professionnel"),template("direct","Prise de contact directe"));
    }
    private static Template template(String id,String label) {
        try(var stream=new ClassPathResource("contact-templates/"+id+".txt").getInputStream()) {
            var text=new String(stream.readAllBytes(),StandardCharsets.UTF_8);int split=text.indexOf('\n');
            return new Template(id,"1",label,text.substring(0,split),text.substring(split+1).strip());
        } catch(Exception e) {throw new IllegalStateException("CONTACT_TEMPLATE_UNAVAILABLE",e);}
    }
    private CandidateService.Candidate access(UUID actor,UUID candidate) {
        var c=candidates.get(actor,candidate);
        if(!c.contactsVisible()) throw new Rejected(HttpStatus.FORBIDDEN,"CONTACT_ACCESS_REQUIRED");
        governance.ensureNotOpposed(candidate);
        return c;
    }
    public Context context(UUID actor,UUID candidate) {return tx.execute(t->{
        var c=access(actor,candidate);var pub=reviews.publication(actor,candidate);
        return new Context(templates,VARIABLES,pub.revisionId(),pub.items().stream()
            .filter(i->i.field()==StructuredExtractor.Field.SKILL).map(i->new Skill(i.sourceIndex(),i.value())).toList(),c.contact().email());
    });}
    public Draft preview(UUID actor,UUID candidate,Input input) {return tx.execute(t->render(actor,candidate,input,0));}
    private Draft render(UUID actor,UUID candidate,Input input,long version) {
        var ctx=context(actor,candidate);
        if(ctx.publicationId()==null) throw new Rejected(HttpStatus.CONFLICT,"CONTACT_PUBLICATION_REQUIRED");
        if(input==null||input.variables()==null||input.skillIndices()==null||input.skillIndices().size()>30
            ||input.skillIndices().stream().anyMatch(Objects::isNull)||new HashSet<>(input.skillIndices()).size()!=input.skillIndices().size()
            ||!VARIABLES.containsAll(input.variables().keySet())) invalid();
        if(!ctx.publicationId().equals(input.publicationId())) throw new Rejected(HttpStatus.CONFLICT,"CONTACT_PUBLICATION_CHANGED");
        var template=templates.stream().filter(t->t.id().equals(input.templateId())).findFirst().orElseThrow(()->new Rejected(HttpStatus.BAD_REQUEST,"INVALID_CONTACT_DRAFT"));
        var variables=new LinkedHashMap<String,String>();var missing=new ArrayList<String>();
        for(String key:VARIABLES) {
            var value=bounded(input.variables().get(key),key.equals("jobDescription")?4000:500,key.equals("jobDescription"));
            variables.put(key,value);if(value.isBlank()&&!OPTIONAL.contains(key))missing.add(key);
        }
        String email=variables.get("contactEmail"),link=variables.get("unsubscribeOrObjectionLink");
        if(!email.isEmpty()&&!email.matches("[^\\s<>@]+@[^\\s<>@]+\\.[^\\s<>@]+"))invalid();
        if(!link.isEmpty()) {
            try {var uri=java.net.URI.create(link);if((!"https".equals(uri.getScheme())&&!governance.validIssuedLink(candidate,link))||uri.getHost()==null||uri.getUserInfo()!=null)invalid();}
            catch(IllegalArgumentException e){invalid();}
        }
        var selected=new ArrayList<Skill>();
        for(Integer index:input.skillIndices()) selected.add(ctx.skills().stream().filter(s->s.sourceIndex()==index).findFirst()
            .orElseThrow(()->new Rejected(HttpStatus.BAD_REQUEST,"CONTACT_SKILL_NOT_PUBLISHED")));
        var renderVars=new HashMap<>(variables);renderVars.put("matchingSkills",String.join(", ",selected.stream().map(Skill::value).toList()));
        if(selected.isEmpty())missing.add("matchingSkills");
        String recipient=bounded(ctx.recipient(),254,false);if(recipient.isEmpty())missing.add("recipient");
        String subject=input.subject()==null?replace(template.id().equals("direct")&&variables.get("candidateFirstName").isBlank()?"Un échange au sujet de {{jobTitle}} ?":template.subject(),renderVars):bounded(input.subject(),500,false);
        String body=input.body()==null?replace(template.body(),renderVars):bounded(input.body(),12000,true);
        // Revalidate generated headers too: published values remain untrusted data.
        subject=bounded(subject,500,false);body=bounded(body,12000,true);
        if(subject.isBlank())missing.add("subject");if(body.isBlank())missing.add("body");
        if(subject.contains("{{")||body.contains("{{")||subject.contains("}}")||body.contains("}}"))missing.add("unresolvedVariables");
        return new Draft(version,ctx.publicationId(),template.id(),template.version(),Map.copyOf(variables),List.copyOf(selected),recipient,
            subject,body,"<p>"+HtmlUtils.htmlEscape(body).replace("\n","<br>")+"</p>",List.copyOf(missing),true,OffsetDateTime.now(ZoneOffset.UTC));
    }
    private static String replace(String template,Map<String,String> values) {
        return PLACEHOLDER.matcher(template).replaceAll(m->java.util.regex.Matcher.quoteReplacement(values.getOrDefault(m.group(1),"")))
            .replace("Bonjour  ,","Bonjour,").replace("Bonjour ,","Bonjour,");
    }
    public Draft save(UUID actor,UUID candidate,String etag,Input input) {return tx.execute(t->{
        access(actor,candidate);
        long version=db.sql("SELECT COALESCE(MAX(version),0) FROM contact_draft_revision WHERE actor_id=:actor AND candidate_id=:candidate")
            .param("actor",actor).param("candidate",candidate).query(Long.class).single();
        if(etag==null)throw new Rejected(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");
        if(!etag.equals("\""+version+"\""))throw new Rejected(HttpStatus.PRECONDITION_FAILED,"CONTACT_DRAFT_CHANGED");
        if(version>=100)throw new Rejected(HttpStatus.TOO_MANY_REQUESTS,"CONTACT_DRAFT_QUOTA");
        var draft=render(actor,candidate,input,version+1);
        int invalidated=db.sql("UPDATE contact_delivery SET status='INVALIDATED',result_code='DRAFT_CHANGED' WHERE actor_id=:actor AND candidate_id=:candidate AND status='APPROVED'").param("actor",actor).param("candidate",candidate).update();
        if(invalidated>0)trail.candidate(actor,candidate,"CONTACT_APPROVAL_INVALIDATED");
        db.sql("INSERT INTO contact_draft_revision(candidate_id,actor_id,version,publication_id,created_at,payload_json) VALUES (:candidate,:actor,:version,:publication,:now,:json)")
            .param("candidate",candidate).param("actor",actor).param("version",draft.version()).param("publication",draft.publicationId())
            .param("now",draft.createdAt()).param("json",write(draft)).update();trail.candidate(actor,candidate,"CONTACT_DRAFT_SAVED");return draft;
    });}
    public History history(UUID actor,UUID candidate,int cursor) {return tx.execute(t->{
        access(actor,candidate);if(cursor<0||cursor>100)invalid();
        var values=db.sql("SELECT payload_json FROM contact_draft_revision WHERE actor_id=:actor AND candidate_id=:candidate ORDER BY version DESC LIMIT 26 OFFSET :cursor")
            .param("actor",actor).param("candidate",candidate).param("cursor",cursor).query(String.class).list();
        return new History(values.stream().limit(25).map(this::read).toList(),values.size()>25?cursor+25:null);
    });}
    @Scheduled(initialDelayString="${app.contact.cleanup-ms:3600000}",fixedDelayString="${app.contact.cleanup-ms:3600000}")
    public void cleanup(){tx.executeWithoutResult(t->db.sql("DELETE FROM contact_draft_revision WHERE candidate_id IN (SELECT c.id FROM candidate_profile c JOIN document_import d ON d.id=c.import_id WHERE d.expires_at<=:now)")
        .param("now",OffsetDateTime.now(ZoneOffset.UTC)).update());}
    private static String bounded(String value,int max,boolean multiline) {
        if(value==null)return "";
        if(value.length()>max||value.codePoints().anyMatch(c->Character.isISOControl(c)&&!(multiline&&(c=='\n'||c=='\t')))||value.indexOf('\u2028')>=0||value.indexOf('\u2029')>=0)invalid();
        return value.strip();
    }
    private static void invalid(){throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_CONTACT_DRAFT");}
    private String write(Draft draft){try{return json.writeValueAsString(draft);}catch(Exception e){throw new IllegalStateException("CONTACT_DRAFT_SERIALIZATION",e);}}
    private Draft read(String value){try{return json.readValue(value,Draft.class);}catch(Exception e){throw new IllegalStateException("CONTACT_DRAFT_STORAGE",e);}}
}
