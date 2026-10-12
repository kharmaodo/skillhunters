package com.skillhunters.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalysisService {
    public record Status(String state, int attempts, String code, String parserVersion, String avVersion,
                         OffsetDateTime updatedAt, DocumentAnalyzer.Result result) {}
    public record Job(UUID id, UUID actor, UUID pool, UUID token, String key, String type, String sha, long size) {}
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final ImportService imports;
    private final QuarantineStorage storage;
    private final DocumentAnalyzer analyzer;
    private final ObjectMapper json;
    public AnalysisService(JdbcClient db, TransactionTemplate tx, ImportService imports, QuarantineStorage storage,
            DocumentAnalyzer analyzer, ObjectMapper json) {
        this.db=db; this.tx=tx; this.imports=imports; this.storage=storage; this.analyzer=analyzer; this.json=json;
    }
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    public Status get(UUID actor, UUID id) {
        return tx.execute(status -> {
            var receipt=imports.get(actor,id); imports.lockActor(actor,receipt.poolId());
            if(!receipt.expiresAt().isAfter(now())) throw new Rejected(HttpStatus.CONFLICT,"IMPORT_EXPIRED");
            db.sql("INSERT INTO document_audit(id,import_id,actor_id,action) VALUES (:event,:id,:actor,'ANALYSIS_READ')")
                .param("event",UUID.randomUUID()).param("id",id).param("actor",actor).update();
            return db.sql("SELECT * FROM document_analysis WHERE import_id=:id").param("id",id).query((rs,n) -> {
                DocumentAnalyzer.Result result=null;
                try { if(rs.getString("result_json")!=null) result=json.readValue(rs.getString("result_json"),DocumentAnalyzer.Result.class); }
                catch(Exception invalid) { throw new IllegalStateException("INVALID_STORED_RESULT"); }
                return new Status(rs.getString("state"),rs.getInt("attempts"),rs.getString("code"),rs.getString("parser_version"),
                    rs.getString("av_version"),rs.getObject("updated_at",OffsetDateTime.class),result);
            }).optional().orElse(new Status("PENDING",0,"RECEPTION_INCOMPLETE",null,null,receipt.createdAt(),null));
        });
    }
    public Status retry(UUID actor, UUID id) {
        return tx.execute(status -> {
            var receipt=imports.get(actor,id); imports.lockActor(actor,receipt.poolId());
            if(!receipt.expiresAt().isAfter(now())) throw new Rejected(HttpStatus.CONFLICT,"IMPORT_EXPIRED");
            int changed=db.sql("""
                UPDATE document_analysis SET state='PENDING',attempts=0,requested_by=:actor,code=NULL,
                result_json=NULL,lease_token=NULL,lease_until=NULL,available_at=:now,updated_at=:now
                WHERE import_id=:id AND state IN ('FAILED','RETRY','CANCELLED')
                """).param("actor",actor).param("id",id).param("now",now()).update();
            if(changed==0) throw new Rejected(HttpStatus.CONFLICT,"ANALYSIS_NOT_RETRYABLE");
            event(id,"PENDING","HUMAN_RETRY");
            db.sql("INSERT INTO document_audit(id,import_id,actor_id,action) VALUES (:event,:id,:actor,'ANALYSIS_RETRY')")
                .param("event",UUID.randomUUID()).param("id",id).param("actor",actor).update();
            return get(actor,id);
        });
    }
    private boolean allowed(UUID actor, UUID pool) {
        var enabled=db.sql("SELECT enabled FROM app_user WHERE id=:id FOR UPDATE").param("id",actor).query(Boolean.class).optional().orElse(false);
        return enabled && db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:actor AND pool_id=:pool")
            .param("actor",actor).param("pool",pool).query(Integer.class).single()>0;
    }
    public Job claim() {
        return tx.execute(status -> {
            var candidate=db.sql("""
                SELECT a.import_id FROM document_analysis a WHERE
                (a.state IN ('PENDING','RETRY') AND a.available_at<=:now)
                OR (a.state='RUNNING' AND a.lease_until<=:now)
                ORDER BY a.available_at,a.import_id LIMIT 1
                """).param("now",now()).query(UUID.class).optional();
            if(candidate.isEmpty()) return null;
            UUID id=candidate.get();
            var context=db.sql("SELECT a.requested_by,d.pool_id FROM document_analysis a JOIN document_import d ON d.id=a.import_id WHERE a.import_id=:id")
                .param("id",id).query((rs,n)->new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)}).optional();
            if(context.isEmpty()) return null;
            boolean permitted=allowed(context.get()[0],context.get()[1]);
            var row=db.sql("SELECT * FROM document_analysis WHERE import_id=:id FOR UPDATE").param("id",id).query((rs,n)->new Object[]{
                rs.getString("state"),rs.getInt("attempts"),rs.getObject("lease_until",OffsetDateTime.class),rs.getObject("available_at",OffsetDateTime.class),rs.getObject("requested_by",UUID.class)}).optional();
            if(row.isEmpty()) return null;
            Object[] r=row.get();
            if(!context.get()[0].equals(r[4])) return null;
            String state=(String)r[0];
            if(state.equals("RUNNING") ? ((OffsetDateTime)r[2]).isAfter(now()) :
                    !Set.of("PENDING","RETRY").contains(state) || ((OffsetDateTime)r[3]).isAfter(now())) return null;
            var document=db.sql("SELECT * FROM document_import WHERE id=:id").param("id",id).query((rs,n)->new Object[]{
                rs.getString("object_key"),rs.getString("media_type"),rs.getString("sha256"),rs.getLong("byte_size"),rs.getObject("expires_at",OffsetDateTime.class),rs.getString("state")}).single();
            if(!permitted || !((OffsetDateTime)document[4]).isAfter(now()) || !document[5].equals("QUARANTINED")) {
                terminal(id,"CANCELLED","SOURCE_UNAVAILABLE"); return null;
            }
            if((int)r[1]>=3) { terminal(id,"FAILED","ATTEMPTS_EXHAUSTED"); return null; }
            UUID token=UUID.randomUUID();
            db.sql("UPDATE document_analysis SET state='RUNNING',attempts=attempts+1,lease_token=:token,lease_until=:lease,updated_at=:now,code=NULL WHERE import_id=:id")
                .param("token",token).param("lease",now().plusMinutes(6)).param("now",now()).param("id",id).update();
            event(id,"RUNNING",null);
            return new Job(id,context.get()[0],context.get()[1],token,(String)document[0],(String)document[1],(String)document[2],(long)document[3]);
        });
    }
    private void terminal(UUID id,String state,String code) {
        db.sql("UPDATE document_analysis SET state=:state,code=:code,result_json=NULL,lease_token=NULL,lease_until=NULL,updated_at=:now WHERE import_id=:id")
            .param("state",state).param("code",code).param("now",now()).param("id",id).update();
        event(id,state,code);
    }
    private void event(UUID id,String state,String code) {
        db.sql("INSERT INTO document_analysis_event(id,import_id,state,code) VALUES (:event,:id,:state,:code)")
            .param("event",UUID.randomUUID()).param("id",id).param("state",state).param("code",code).update();
    }
    public void runOnce() {
        Job job=claim(); if(job==null) return;
        DocumentAnalyzer.Result result;
        try {
            byte[] data=storage.read(job.key());
            if(data==null || data.length!=job.size() || !HexFormat.of().formatHex(ReceptionValidator.sha().digest(data)).equals(job.sha()))
                result=new DocumentAnalyzer.Result("REJECTED","INTEGRITY_MISMATCH",List.of(),List.of(),null,null);
            else result=analyzer.analyze(data,job.type(),job.sha());
        } catch(Exception unavailable) {
            result=new DocumentAnalyzer.Result("RETRY","WORKER_UNAVAILABLE",List.of(),List.of(),null,null);
        }
        complete(job,result);
    }
    public void complete(Job job,DocumentAnalyzer.Result proposed) {
        final DocumentAnalyzer.Result result=valid(proposed) ? proposed : new DocumentAnalyzer.Result("RETRY","INVALID_WORKER_RESULT",List.of(),List.of(),null,null);
        tx.executeWithoutResult(status -> {
            boolean permitted=allowed(job.actor(),job.pool());
            var row=db.sql("SELECT attempts FROM document_analysis WHERE import_id=:id AND lease_token=:token AND state='RUNNING' AND lease_until>:now FOR UPDATE")
                .param("id",job.id()).param("token",job.token()).param("now",now()).query(Integer.class).optional();
            if(row.isEmpty()) return; // Expired/replaced lease or deleted source: never resurrect it.
            boolean live=db.sql("SELECT COUNT(*) FROM document_import WHERE id=:id AND pool_id=:pool AND state='QUARANTINED' AND expires_at>:now")
                .param("id",job.id()).param("pool",job.pool()).param("now",now()).query(Integer.class).single()==1;
            if(!permitted || !live) { terminal(job.id(),"CANCELLED","SOURCE_UNAVAILABLE"); return; }
            String state=result.state().equals("RETRY") && row.get()>=3 ? "FAILED" : result.state();
            String serialized=null;
            try { if(Set.of("EXTRACTED","OCR_REQUIRED").contains(state)) serialized=json.writeValueAsString(result); }
            catch(Exception invalid) { throw new IllegalStateException("RESULT_SERIALIZATION"); }
            db.sql("""
                UPDATE document_analysis SET state=:state,code=:code,result_json=:result,parser_version=:parser,av_version=:av,
                lease_token=NULL,lease_until=NULL,available_at=:next,updated_at=:now WHERE import_id=:id
                """).param("state",state).param("code",result.code()).param("result",serialized).param("parser",result.parserVersion())
                .param("av",result.avVersion()).param("next",now().plusSeconds(60L*row.get())).param("now",now()).param("id",job.id()).update();
            event(job.id(),state,result.code());
        });
    }
    private boolean validOcr(DocumentAnalyzer.Result r) {
        var pages=r.ocr()==null ? List.<DocumentAnalyzer.OcrPage>of() : r.ocr();
        if(pages.size()>20 || (!pages.isEmpty() && !r.state().equals("EXTRACTED"))) return false;
        var seen=new HashSet<Integer>();
        for(var page:pages) {
            if(page==null || page.page()<1 || page.page()>100 || !seen.add(page.page()) || page.durationMs()<0 || page.durationMs()>120000) return false;
            if(page.confidence()!=null && (!Double.isFinite(page.confidence()) || page.confidence()<0 || page.confidence()>100)) return false;
            if(page.warnings()==null || page.warnings().size()>3 || page.warnings().stream().anyMatch(w->!Set.of("OCR_NO_TEXT","OCR_LOW_CONFIDENCE","OCR_SPARSE_TEXT").contains(Objects.toString(w,"")))) return false;
            if((page.confidence()==null || page.confidence()<70 || !page.warnings().isEmpty()) && !page.reviewRequired()) return false;
            for(var version:new String[]{page.engineVersion(),page.rendererVersion(),page.modelVersion()})
                if(version==null || version.isBlank() || version.length()>200) return false;
        }
        for(var block:r.blocks()) {
            if(block!=null && block.source()!=null && block.source().startsWith("ocr:page:")) {
                try { if(!seen.contains(Integer.parseInt(block.source().substring(9)))) return false; }
                catch(NumberFormatException invalid) { return false; }
            }
        }
        return true;
    }
    private boolean valid(DocumentAnalyzer.Result r) {
        if(r==null || !Set.of("EXTRACTED","OCR_REQUIRED","REJECTED","RETRY").contains(Objects.toString(r.state(),"")) || r.blocks()==null || r.ocrPages()==null) return false;
        if(r.code()!=null && !r.code().matches("[A-Z_]{1,60}")) return false;
        if(r.parserVersion()!=null && r.parserVersion().length()>200 || r.avVersion()!=null && r.avVersion().length()>200) return false;
        if(!validOcr(r)) return false;
        if(r.blocks().size()>5000 || r.ocrPages().size()>100 || r.ocrPages().stream().anyMatch(p->p==null || p<1 || p>100)) return false;
        if(r.blocks().stream().anyMatch(b->b==null || b.source()==null || b.source().length()>500 || b.text()==null || b.text().indexOf(0)>=0)) return false;
        if(r.blocks().stream().mapToLong(b->b.text().length()).sum()>500000) return false;
        if(r.state().equals("EXTRACTED") && !r.ocrPages().isEmpty() || r.state().equals("OCR_REQUIRED") && r.ocrPages().isEmpty()) return false;
        if(Set.of("EXTRACTED","OCR_REQUIRED").contains(r.state())) return r.avVersion()!=null && r.avVersion().startsWith("ClamAV ") && r.parserVersion()!=null;
        return r.blocks().isEmpty() && r.ocrPages().isEmpty();
    }
}
