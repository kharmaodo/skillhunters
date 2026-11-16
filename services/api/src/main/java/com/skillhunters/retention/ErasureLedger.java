package com.skillhunters.retention;
import java.util.function.Consumer;
/** Independent of database backups. Implementations must retain the current journal on restore. */
public interface ErasureLedger {
    void append(ErasureRecord record);
    void scan(Consumer<ErasureRecord> consumer);
}
