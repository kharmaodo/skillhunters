package com.skillhunters.settings;
import com.skillhunters.exports.WordRenderer;
import com.skillhunters.governance.AuditTrail;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ImportSettings {
    public record Limits(long maxFileBytes,int maxBatchFiles,long maxBatchBytes,long maxFilesPerUser,long maxBytesPerUser) {}
    public record Version(long version,long maxFileBytes,int maxBatchFiles,long maxBatchBytes,long maxFilesPerUser,long maxBytesPerUser,String templateVersion,String templateSha256,UUID actorId,OffsetDateTime createdAt,String reason) {}
    public record Edit(Limits limits,String reason) {}
    public record Page(List<Version> items,Long nextCursor) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final AuditTrail audit;private final WordRenderer renderer;private final long initialFiles,initialBytes;
    public ImportSettings(JdbcClient db,TransactionTemplate tx,AuditTrail audit,WordRenderer renderer,@Value("${app.imports.max-files-per-user:1000}") long files,@Value("${app.imports.max-bytes-per-user:3145728000}") long bytes){this.db=db;this.tx=tx;this.audit=audit;this.renderer=renderer;this.initialFiles=files;this.initialBytes=bytes;}
    private void admin(UUID actor){if(!db.sql("SELECT enabled FROM app_user WHERE id=:a FOR UPDATE").param("a",actor).query(Boolean.class).optional().orElse(false))fail(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");if(db.sql("SELECT COUNT(*) FROM global_role WHERE user_id=:a AND role='ADMIN'").param("a",actor).query(Integer.class).single()==0)fail(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED");}
    // Serializes publication with admission of a new import/manifest, not object I/O.
    public Version current(){return tx.execute(t->{long v=db.sql("SELECT version FROM import_settings_head WHERE id=1 FOR UPDATE").query(Long.class).single();if(v==0){var defaults=new Limits(15728640,100,314572800,initialFiles,initialBytes);validate(defaults);insert(1,defaults,null,"Initialisation des paramètres non secrets");db.sql("UPDATE import_settings_head SET version=1 WHERE id=1").update();v=1;}return version(v);});}
    public Version version(long v){return db.sql("SELECT * FROM import_settings_version WHERE version=:v").param("v",v).query(Version.class).optional().orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"SETTINGS_VERSION_NOT_FOUND"));}
    public Version get(UUID actor){return tx.execute(t->{admin(actor);var v=current();audit.record(actor,"SETTINGS_READ",null,null,null);return v;});}
    public Page history(UUID actor,Long cursor){return tx.execute(t->{admin(actor);current();if(cursor!=null&&cursor<1)fail(HttpStatus.BAD_REQUEST,"INVALID_CURSOR");var q=db.sql("SELECT * FROM import_settings_version"+(cursor==null?"":" WHERE version<:cursor")+" ORDER BY version DESC LIMIT 26");if(cursor!=null)q=q.param("cursor",cursor);var all=q.query(Version.class).list();var items=all.stream().limit(25).toList();audit.record(actor,"SETTINGS_HISTORY_READ",null,null,null);return new Page(items,all.size()>25?items.getLast().version():null);});}
    public Version publish(UUID actor,String etag,Edit edit){return tx.execute(t->{admin(actor);var old=current();if(etag==null)fail(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");if(!etag.equals("\""+old.version()+"\""))fail(HttpStatus.PRECONDITION_FAILED,"SETTINGS_CHANGED");if(edit==null||edit.reason()==null||edit.reason().isBlank()||edit.reason().length()>500||edit.reason().codePoints().anyMatch(Character::isISOControl))fail(HttpStatus.BAD_REQUEST,"INVALID_SETTINGS");validate(edit.limits());if(old.version()>=10000)fail(HttpStatus.TOO_MANY_REQUESTS,"SETTINGS_VERSION_LIMIT");long next=old.version()+1;insert(next,edit.limits(),actor,edit.reason().strip());db.sql("UPDATE import_settings_head SET version=:v WHERE id=1").param("v",next).update();audit.record(actor,"SETTINGS_PUBLISHED",null,null,"version:"+next);return version(next);});}
    private void insert(long v,Limits l,UUID actor,String reason){db.sql("INSERT INTO import_settings_version(version,max_file_bytes,max_batch_files,max_batch_bytes,max_files_per_user,max_bytes_per_user,template_version,template_sha256,actor_id,reason) VALUES (:v,:file,:count,:batch,:files,:bytes,:template,:hash,:actor,:reason)").param("v",v).param("file",l.maxFileBytes()).param("count",l.maxBatchFiles()).param("batch",l.maxBatchBytes()).param("files",l.maxFilesPerUser()).param("bytes",l.maxBytesPerUser()).param("template",WordRenderer.TEMPLATE).param("hash",renderer.templateHash()).param("actor",actor).param("reason",reason).update();}
    private static void validate(Limits l){if(l==null||l.maxFileBytes()<1||l.maxFileBytes()>15728640||l.maxBatchFiles()<1||l.maxBatchFiles()>100||l.maxBatchBytes()<l.maxFileBytes()||l.maxBatchBytes()>314572800||l.maxFilesPerUser()<1||l.maxFilesPerUser()>100000||l.maxBytesPerUser()<1||l.maxBytesPerUser()>1099511627776L)fail(HttpStatus.BAD_REQUEST,"INVALID_SETTINGS");}
    public record Admission(long version,Limits limits) {}
    public Admission forPool(UUID actor,UUID pool){return tx.execute(t->{
        if(!db.sql("SELECT enabled FROM app_user WHERE id=:a FOR UPDATE").param("a",actor).query(Boolean.class).optional().orElse(false))fail(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");
        if(db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:a AND pool_id=:p").param("a",actor).param("p",pool).query(Integer.class).single()==0)fail(HttpStatus.NOT_FOUND,"POOL_NOT_FOUND");
        var v=current();return new Admission(v.version(),new Limits(v.maxFileBytes(),v.maxBatchFiles(),v.maxBatchBytes(),v.maxFilesPerUser(),v.maxBytesPerUser()));
    });}
    public byte[] template(UUID actor){return tx.execute(t->{admin(actor);try(var in=WordRenderer.class.getResourceAsStream("/templates/cv/"+WordRenderer.TEMPLATE+".docx")){if(in==null)throw new IllegalStateException("TEMPLATE_UNAVAILABLE");audit.record(actor,"CLEAN_TEMPLATE_READ",null,null,null);return in.readAllBytes();}catch(java.io.IOException e){throw new IllegalStateException("TEMPLATE_UNAVAILABLE",e);}});}
    private static void fail(HttpStatus status,String code){throw new Rejected(status,code);}
}
