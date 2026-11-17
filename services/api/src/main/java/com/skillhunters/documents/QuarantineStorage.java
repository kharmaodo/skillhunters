package com.skillhunters.documents;

import java.nio.file.Path;

/** Private quarantine boundary. Reads are internal to analysis; no public download operation. */
public interface QuarantineStorage {
    void put(String key, Path file);
    byte[] read(String key);
    /** Delete all object versions and deletion markers; fail if absence cannot be established. */
    void delete(String key);
}
