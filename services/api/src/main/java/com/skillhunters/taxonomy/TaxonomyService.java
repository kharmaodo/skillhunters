package com.skillhunters.taxonomy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.text.Normalizer;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaxonomyService {
    public record Skill(UUID id, String name, String category, List<String> aliases) {}
    public record Dictionary(long version, List<Skill> skills) {}
    public record Edit(String name, String category, List<String> aliases, String reason) {}
    public record Merge(UUID targetId, boolean confirmed, String reason) {}
    public record Match(String original, UUID skillId, String canonical, long dictionaryVersion) {}
    public record Event(long version, UUID actorId, String action, String reason, String createdAt) {}
    private final JdbcClient db;
    private final ObjectMapper json;
    public TaxonomyService(JdbcClient db, ObjectMapper json) { this.db=db; this.json=json; }

    public Dictionary dictionary(Long version) {
        long v=version==null?db.sql("SELECT version FROM skill_dictionary_head WHERE id=1").query(Long.class).single():version;
        var data=db.sql("SELECT entries_json FROM skill_dictionary WHERE version=:v").param("v",v).query(String.class).optional()
            .orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"DICTIONARY_NOT_FOUND"));
        try {return new Dictionary(v,json.readValue(data,new TypeReference<List<Skill>>(){}));}
        catch(Exception e) {throw new IllegalStateException("INVALID_DICTIONARY_STORAGE");}
    }
    public List<Event> history(int cursor) {
        if(cursor<0||cursor>100000) invalid();
        return db.sql("SELECT version,actor_id,action,reason,created_at FROM skill_dictionary ORDER BY version DESC LIMIT 25 OFFSET :cursor")
            .param("cursor",cursor).query((r,n)->new Event(r.getLong(1),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5))).list();
    }
    public static String key(String text) {
        return Normalizer.normalize(text,Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
    }
    public static Match normalize(Dictionary dictionary,String original) {
        String key=key(original);
        for(var skill:dictionary.skills()) if(key(skill.name()).equals(key)||skill.aliases().stream().anyMatch(a->key(a).equals(key)))
            return new Match(original,skill.id(),skill.name(),dictionary.version());
        return new Match(original,null,null,dictionary.version());
    }
    @Transactional
    public Dictionary edit(UUID actor,UUID id,String etag,Edit request) {
        var before=locked(actor,etag);
        if(request==null||request.aliases()==null||request.aliases().size()>50) invalid();
        text(request.name(),200);text(request.category(),100);text(request.reason(),500);
        request.aliases().forEach(a->text(a,200));
        var rows=new ArrayList<>(before.skills());
        if(id!=null&&!rows.removeIf(s->s.id().equals(id))) throw new Rejected(HttpStatus.NOT_FOUND,"SKILL_NOT_FOUND");
        if(rows.size()>=1000) throw new Rejected(HttpStatus.CONFLICT,"DICTIONARY_LIMIT");
        rows.add(new Skill(id==null?UUID.randomUUID():id,request.name().strip(),request.category().strip(),request.aliases().stream().map(String::strip).toList()));
        return persist(actor,before,rows,id==null?"CREATE":"UPDATE",request.reason());
    }
    @Transactional
    public Dictionary merge(UUID actor,UUID source,String etag,Merge request) {
        var before=locked(actor,etag);
        if(request==null||request.targetId()==null||!request.confirmed()||source.equals(request.targetId()))
            throw new Rejected(HttpStatus.BAD_REQUEST,"MERGE_CONFIRMATION_REQUIRED");
        text(request.reason(),500);
        var from=find(before,source);var to=find(before,request.targetId());
        var aliases=new LinkedHashMap<String,String>();
        for(String a:to.aliases()) aliases.put(key(a),a);
        aliases.put(key(from.name()),from.name());
        for(String a:from.aliases()) aliases.put(key(a),a);
        aliases.remove(key(to.name()));
        if(aliases.size()>50) throw new Rejected(HttpStatus.CONFLICT,"DICTIONARY_LIMIT");
        var rows=new ArrayList<>(before.skills());rows.removeIf(s->s.id().equals(source)||s.id().equals(to.id()));
        rows.add(new Skill(to.id(),to.name(),to.category(),List.copyOf(aliases.values())));
        return persist(actor,before,rows,"MERGE",request.reason());
    }
    private Skill find(Dictionary d,UUID id) {return d.skills().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"SKILL_NOT_FOUND"));}
    private Dictionary locked(UUID actor,String etag) {
        var enabled=db.sql("SELECT enabled FROM app_user WHERE id=:actor FOR UPDATE").param("actor",actor).query(Boolean.class).optional().orElse(false);
        if(!enabled||db.sql("SELECT COUNT(*) FROM global_role WHERE user_id=:actor AND role='ADMIN'").param("actor",actor).query(Integer.class).single()==0)
            throw new Rejected(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED");
        long version=db.sql("SELECT version FROM skill_dictionary_head WHERE id=1 FOR UPDATE").query(Long.class).single();
        if(etag==null) throw new Rejected(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");
        if(!etag.equals("\""+version+"\"")) throw new Rejected(HttpStatus.PRECONDITION_FAILED,"DICTIONARY_CHANGED");
        return dictionary(version);
    }
    private Dictionary persist(UUID actor,Dictionary before,List<Skill> rows,String action,String reason) {
        var owners=new HashMap<String,UUID>();
        for(var s:rows) {
            var names=new ArrayList<>(s.aliases());names.add(s.name());
            for(var name:names) if(owners.putIfAbsent(key(name),s.id())!=null)
                throw new Rejected(HttpStatus.CONFLICT,"SKILL_ALIAS_COLLISION");
        }
        rows.sort(Comparator.comparing(s->key(s.name())));
        long v=before.version()+1;
        String data;try{data=json.writeValueAsString(rows);}catch(Exception e){throw new IllegalStateException("DICTIONARY_SERIALIZATION");}
        db.sql("INSERT INTO skill_dictionary(version,entries_json,actor_id,action,reason) VALUES (:v,:data,:actor,:action,:reason)")
            .param("v",v).param("data",data).param("actor",actor).param("action",action).param("reason",reason).update();
        db.sql("UPDATE skill_dictionary_head SET version=:v WHERE id=1").param("v",v).update();
        return new Dictionary(v,List.copyOf(rows));
    }
    private static void text(String s,int max) {if(s==null||s.isBlank()||s.length()>max||s.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)) invalid();}
    private static void invalid() {throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_TAXONOMY");}
}
