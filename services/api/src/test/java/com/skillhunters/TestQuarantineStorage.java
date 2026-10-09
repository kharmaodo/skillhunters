package com.skillhunters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import com.skillhunters.documents.QuarantineStorage;
import com.skillhunters.shared.ApiProblem.Rejected;
import org.springframework.http.HttpStatus;

@Component
@Profile("test")
public class TestQuarantineStorage implements QuarantineStorage {
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    boolean fail;
    @Override public void put(String key, Path file) {
        if (fail) throw new Rejected(HttpStatus.SERVICE_UNAVAILABLE, "QUARANTINE_UNAVAILABLE");
        try { objects.put(key, Files.readAllBytes(file)); }
        catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
}
