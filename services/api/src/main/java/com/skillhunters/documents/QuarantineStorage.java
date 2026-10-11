package com.skillhunters.documents;

import java.nio.file.Path;

/** Private quarantine boundary. Reads are internal to analysis; no public download operation. */
public interface QuarantineStorage {
    void put(String key, Path file);
    byte[] read(String key);
}
