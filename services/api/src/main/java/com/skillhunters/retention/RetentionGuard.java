package com.skillhunters.retention;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class RetentionGuard {
    private final JdbcClient db;private final byte[] key;private volatile boolean ready;
    public RetentionGuard(JdbcClient db,@Value("${app.retention.ledger-key}") String key){if(key.length()<32)throw new IllegalArgumentException("ERASURE_LEDGER_KEY_REQUIRED");this.db=db;this.key=key.getBytes(StandardCharsets.UTF_8);}
    public boolean ready(){return ready;}
    public void ready(boolean value){ready=value;}
    public boolean erased(UUID id){return db.sql("SELECT COUNT(*) FROM erasure_tombstone WHERE source_id=:id").param("id",id).query(Integer.class).single()>0;}
    public void live(UUID id){if(erased(id))throw new Rejected(HttpStatus.GONE,"SOURCE_ERASED");}
    public void replayAllowed(String kind,UUID actor,UUID pool,String token){if(db.sql("SELECT COUNT(*) FROM erasure_tombstone WHERE replay_hash=:h").param("h",replayHash(kind,actor,pool,token)).query(Integer.class).single()>0)throw new Rejected(HttpStatus.GONE,"SOURCE_ERASED");}
    public String replayHash(String kind,UUID actor,UUID pool,String token){return mac(kind+"|"+actor+"|"+pool+"|"+token);}
    private String mac(String value){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("ERASURE_SIGNATURE",e);}}
    public ErasureRecord sign(UUID id,String kind,UUID pool,String replay,OffsetDateTime requested){return new ErasureRecord(id,kind,pool,replay,requested,mac(id+"|"+kind+"|"+pool+"|"+replay+"|"+requested));}
    public void verify(ErasureRecord r){if(r==null||r.sourceId()==null||r.poolId()==null||!Set.of("IMPORT","BATCH").contains(r.kind())||r.replayHash()==null||!r.replayHash().matches("[a-f0-9]{64}")||r.requestedAt()==null||r.signature()==null||!java.security.MessageDigest.isEqual(sign(r.sourceId(),r.kind(),r.poolId(),r.replayHash(),r.requestedAt()).signature().getBytes(StandardCharsets.UTF_8),r.signature().getBytes(StandardCharsets.UTF_8)))throw new IllegalStateException("INVALID_ERASURE_LEDGER");}
    public static final String UNHELD_SOURCE=" NOT EXISTS (SELECT 1 FROM retention_hold h WHERE h.import_id=d.id AND h.until_at>CURRENT_TIMESTAMP)";
    public static final String UNHELD_CANDIDATE=" NOT EXISTS (SELECT 1 FROM candidate_profile hc JOIN retention_hold h ON h.import_id=hc.import_id WHERE hc.id=candidate_id AND h.until_at>CURRENT_TIMESTAMP)";
}
