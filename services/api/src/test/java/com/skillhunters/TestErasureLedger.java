package com.skillhunters;
import com.skillhunters.retention.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
@Component @Profile("test")
public class TestErasureLedger implements ErasureLedger {
    final Map<UUID,ErasureRecord> records=new ConcurrentHashMap<>();
    boolean fail;
    public void append(ErasureRecord r){if(fail)throw new IllegalStateException("SYNTHETIC_LEDGER_UNAVAILABLE");records.putIfAbsent(r.sourceId(),r);}
    public void scan(Consumer<ErasureRecord> consumer){if(fail)throw new IllegalStateException("SYNTHETIC_LEDGER_UNAVAILABLE");records.values().forEach(consumer);}
}
