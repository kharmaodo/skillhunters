package com.skillhunters.documents;

import java.nio.file.Path;

/** Private write-only quarantine boundary. No public or presigned download operation. */
public interface QuarantineStorage {
    void put(String key, Path file);
}
