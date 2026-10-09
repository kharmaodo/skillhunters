package com.skillhunters.documents;

import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import com.skillhunters.shared.ApiProblem.Rejected;

/** Shared admission control for individual reception, batch upload and resumption. */
@Component
public class ImportCapacity {
    private final Semaphore permits = new Semaphore(4);
    public void acquire() {
        if (!permits.tryAcquire()) throw new Rejected(HttpStatus.TOO_MANY_REQUESTS, "IMPORT_BUSY");
    }
    public void release() { permits.release(); }
}
