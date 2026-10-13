package com.skillhunters.talent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.documents.ExtractionService;
import com.skillhunters.documents.StructuredExtractor;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CandidateService {
    public record Availability(String status, LocalDate availableFrom, LocalDate declaredOn, String declaration) {}
    public record Profile(String displayName, String professionalTitle, Availability availability) {}
    public record Contact(String email, String phone) {}
    public record Candidate(UUID id, UUID poolId, UUID importId, UUID revisionId, long version,
                            Profile profile, Contact contact, boolean contactsVisible, OffsetDateTime expiresAt) {}
    public record Page<T>(List<T> items, String nextCursor) {}
    public record Edit(long version, UUID actorId, List<String> fields, String provenance, OffsetDateTime occurredAt) {}
    private record Row(UUID id, UUID pool, UUID source, UUID revision, long version, String profile, OffsetDateTime expires) {}
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ExtractionService extraction;
    public CandidateService(JdbcClient db, TransactionTemplate tx, ObjectMapper json, ExtractionService extraction) {
        this.db=db;this.tx=tx;this.json=json;this.extraction=extraction;
    }
    // Same actor lock as membership replacement: a revocation cannot race a write.
    private void actor(UUID actor) {
        if (!db.sql("SELECT enabled FROM app_user WHERE id=:id FOR UPDATE").param("id",actor)
            .query(Boolean.class).optional().orElse(false)) throw new Rejected(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");
    }
    private void pool(UUID actor, UUID pool) {
        if (db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:actor AND pool_id=:pool")
            .param("actor",actor).param("pool",pool).query(Integer.class).single()==0) missing();
    }
    private boolean contacts(UUID actor, UUID pool) {
        return db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:actor AND pool_id=:pool AND role='RECRUITMENT_LEAD'")
            .param("actor",actor).param("pool",pool).query(Integer.class).single()>0;
    }
    private static void missing() { throw new Rejected(HttpStatus.NOT_FOUND,"CANDIDATE_NOT_FOUND"); }
    private static void invalid() { throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_CANDIDATE"); }
    private static final String SELECT="""
        SELECT c.id,d.pool_id,c.import_id,c.revision_id,c.version,c.profile_json,d.expires_at
        FROM candidate_profile c JOIN document_import d ON d.id=c.import_id
        """;
    private Row row(UUID actor, UUID id) {
        Row r=db.sql(SELECT+" WHERE c.id=:id").param("id",id).query((rs,n)->new Row(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
            rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getLong(5),rs.getString(6),rs.getObject(7,OffsetDateTime.class)))
            .optional().orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"CANDIDATE_NOT_FOUND"));
        pool(actor,r.pool());
        if (!r.expires().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) missing();
        return r;
    }
    private Candidate view(UUID actor, Row r) {
        boolean visible=contacts(actor,r.pool());
        Contact contact=visible?db.sql("SELECT email,phone FROM candidate_contact WHERE candidate_id=:id").param("id",r.id())
            .query((rs,n)->new Contact(rs.getString(1),rs.getString(2))).single():null;
        return new Candidate(r.id(),r.pool(),r.source(),r.revision(),r.version(),read(r.profile(),Profile.class),contact,visible,r.expires());
    }
    public Candidate get(UUID actor, UUID id) { return tx.execute(s->{actor(actor);return view(actor,row(actor,id));}); }
    public Candidate create(UUID actor, UUID source, UUID revisionId) {
        return tx.execute(s->{
            actor(actor);
            if (revisionId==null) invalid();
            // Lock via extraction service serializes concurrent creation for different actors too.
            var revision=extraction.get(actor,source);
            if (!revision.id().equals(revisionId)) throw new Rejected(HttpStatus.CONFLICT,"EXTRACTION_CHANGED");
            var existing=db.sql("SELECT id FROM candidate_profile WHERE import_id=:id").param("id",source).query(UUID.class).optional();
            if (existing.isPresent()) return view(actor,row(actor,existing.get()));
            UUID id=UUID.randomUUID();
            var profile=new Profile(single(revision,StructuredExtractor.Field.FULL_NAME),single(revision,StructuredExtractor.Field.TITLE),
                new Availability("UNKNOWN",null,null,null));
            db.sql("INSERT INTO candidate_profile(id,import_id,revision_id,profile_json) VALUES (:id,:source,:revision,:profile)")
                .param("id",id).param("source",source).param("revision",revisionId).param("profile",write(profile)).update();
            db.sql("INSERT INTO candidate_contact(candidate_id) VALUES (:id)").param("id",id).update();
            audit(actor,id,1,List.of("displayName","professionalTitle","availability"),"EXTRACTION:"+revisionId);
            return view(actor,row(actor,id));
        });
    }
    private String single(ExtractionService.Revision revision, StructuredExtractor.Field field) {
        var values=revision.proposal().claims().stream().filter(c->c.field()==field && c.status()==StructuredExtractor.State.PROPOSED)
            .map(StructuredExtractor.Claim::normalized).filter(Objects::nonNull).toList();
        return values.size()==1 && values.getFirst().length()<=200?values.getFirst():null;
    }
    public Page<Candidate> list(UUID actor, UUID pool, int offset, int limit) {
        bounds(offset,limit);
        return tx.execute(s->{actor(actor);pool(actor,pool);
            var ids=db.sql("""
                SELECT c.id FROM candidate_profile c JOIN document_import d ON d.id=c.import_id
                WHERE d.pool_id=:pool AND d.expires_at>:now ORDER BY c.id LIMIT :limit OFFSET :offset
                """).param("pool",pool).param("now",OffsetDateTime.now(ZoneOffset.UTC)).param("limit",limit+1).param("offset",offset).query(UUID.class).list();
            return new Page<>(ids.stream().limit(limit).map(id->view(actor,row(actor,id))).toList(),ids.size()>limit?""+(offset+limit):null);
        });
    }
    public Page<Edit> history(UUID actor, UUID id, int offset, int limit) {
        bounds(offset,limit);
        return tx.execute(s->{actor(actor);var r=row(actor,id);boolean visible=contacts(actor,r.pool());
            var edits=db.sql("SELECT version,actor_id,fields_json,provenance,occurred_at FROM candidate_edit WHERE candidate_id=:id ORDER BY version DESC LIMIT :limit OFFSET :offset")
                .param("id",id).param("limit",limit+1).param("offset",offset).query((rs,n)->{
                    List<String> fields=List.of(read(rs.getString(3),String[].class));
                    // No historic contact values, even for authorized readers. Contact-only provenance is redacted.
                    boolean sensitive=fields.stream().anyMatch(f->f.startsWith("contact."));
                    return new Edit(rs.getLong(1),rs.getObject(2,UUID.class),visible?fields:fields.stream().filter(f->!f.startsWith("contact.")).toList(),
                        !visible&&sensitive?"CONTACT_REDACTED":rs.getString(4),rs.getObject(5,OffsetDateTime.class));
                }).list();
            return new Page<>(edits.stream().limit(limit).toList(),edits.size()>limit?""+(offset+limit):null);
        });
    }
    public Candidate update(UUID actor, UUID id, String etag, JsonNode patch) {
        return tx.execute(s->{actor(actor);var r=row(actor,id);
            if (etag==null) throw new Rejected(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");
            if (!etag.equals("\""+r.version()+"\"")) throw new Rejected(HttpStatus.PRECONDITION_FAILED,"CANDIDATE_CHANGED");
            keys(patch,Set.of("displayName","professionalTitle","availability","contact","reason"));
            String reason=text(patch.get("reason"),500,false);
            Profile old=read(r.profile(),Profile.class);
            String name=patch.has("displayName")?text(patch.get("displayName"),200,true):old.displayName();
            String title=patch.has("professionalTitle")?text(patch.get("professionalTitle"),200,true):old.professionalTitle();
            Availability availability=patch.has("availability")?availability(patch.get("availability")):old.availability();
            Profile profile=new Profile(name,title,availability);
            List<String> changed=new ArrayList<>();
            if (!Objects.equals(name,old.displayName())) changed.add("displayName");
            if (!Objects.equals(title,old.professionalTitle())) changed.add("professionalTitle");
            if (!Objects.equals(availability,old.availability())) changed.add("availability");
            Contact contact=null;
            if (patch.has("contact")) {
                if (!contacts(actor,r.pool())) throw new Rejected(HttpStatus.FORBIDDEN,"CONTACT_ACCESS_REQUIRED");
                var c=patch.get("contact");keys(c,Set.of("email","phone"));
                if (!c.has("email")||!c.has("phone")) invalid();
                contact=new Contact(text(c.get("email"),254,true),text(c.get("phone"),40,true));
                if (contact.email()!=null&&!contact.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) invalid();
                if (contact.phone()!=null&&!contact.phone().matches("[+0-9() .-]{3,40}")) invalid();
                var before=view(actor,r).contact();
                if (!Objects.equals(before.email(),contact.email())) changed.add("contact.email");
                if (!Objects.equals(before.phone(),contact.phone())) changed.add("contact.phone");
            }
            if (changed.isEmpty()) return view(actor,r);
            // Atomic compare-and-swap also protects writers with distinct actor locks.
            int count=db.sql("UPDATE candidate_profile SET profile_json=:profile,version=version+1 WHERE id=:id AND version=:version")
                .param("profile",write(profile)).param("id",id).param("version",r.version()).update();
            if(count!=1) throw new Rejected(HttpStatus.PRECONDITION_FAILED,"CANDIDATE_CHANGED");
            if(contact!=null) db.sql("UPDATE candidate_contact SET email=:email,phone=:phone WHERE candidate_id=:id")
                .param("email",contact.email()).param("phone",contact.phone()).param("id",id).update();
            audit(actor,id,r.version()+1,changed,"HUMAN: "+reason);
            return view(actor,row(actor,id));
        });
    }
    private Availability availability(JsonNode node) {
        keys(node,Set.of("status","availableFrom","declaredOn","declaration"));
        String status=text(node.get("status"),20,false);
        LocalDate from=date(node.get("availableFrom")), declared=date(node.get("declaredOn"));
        String declaration=text(node.get("declaration"),500,true);
        if (status.equals("UNKNOWN")) { if(from!=null||declared!=null||declaration!=null) invalid(); }
        else if(status.equals("DECLARED")) {
            if(from==null||declared==null||declaration==null||declared.isAfter(LocalDate.now(ZoneOffset.UTC))) invalid();
        } else invalid();
        return new Availability(status,from,declared,declaration);
    }
    private static LocalDate date(JsonNode n) {
        if(n==null||n.isNull()) return null;
        try { if(!n.isTextual()) invalid();return LocalDate.parse(n.textValue()); }
        catch(java.time.format.DateTimeParseException e) { invalid();return null; }
    }
    private static String text(JsonNode n,int max,boolean nullable) {
        if(n==null||n.isNull()) {if(!nullable) invalid();return null;}
        if(!n.isTextual()||n.textValue().isBlank()||n.textValue().length()>max||n.textValue().chars().anyMatch(Character::isISOControl)) invalid();
        return n.textValue().strip();
    }
    private static void keys(JsonNode n,Set<String> allowed) {
        if(n==null||!n.isObject()) invalid();
        n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k)) invalid();});
    }
    private static void bounds(int offset,int limit) {if(offset<0||offset>100000||limit<1||limit>100) invalid();}
    private void audit(UUID actor,UUID id,long version,List<String> fields,String provenance) {
        db.sql("INSERT INTO candidate_edit(id,candidate_id,version,actor_id,fields_json,provenance) VALUES (:event,:id,:version,:actor,:fields,:provenance)")
            .param("event",UUID.randomUUID()).param("id",id).param("version",version).param("actor",actor).param("fields",write(fields))
            .param("provenance",provenance).update();
    }
    private <T>T read(String text,Class<T> type) {try{return json.readValue(text,type);}catch(Exception e){throw new IllegalStateException("INVALID_CANDIDATE_STORAGE");}}
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("CANDIDATE_SERIALIZATION");}}
}
