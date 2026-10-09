package com.skillhunters.shared;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiProblem {
    public static ProblemDetail detail(HttpStatus status, String code) {
        var problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("urn:skillhunters:problem:" + code.toLowerCase()));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("correlationId", UUID.randomUUID().toString());
        return problem;
    }

    public static class Rejected extends RuntimeException {
        public final HttpStatus status;
        public final String code;
        public Rejected(HttpStatus status, String code) { this.status = status; this.code = code; }
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(Exception ignored) {
        return ResponseEntity.status(413).body(detail(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE"));
    }

    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    ResponseEntity<ProblemDetail> malformedMultipart(Exception ignored) {
        return ResponseEntity.badRequest().body(detail(HttpStatus.BAD_REQUEST, "INVALID_MULTIPART"));
    }

    @ExceptionHandler(java.io.IOException.class)
    ResponseEntity<ProblemDetail> unavailable(Exception ignored) {
        return ResponseEntity.status(503).body(detail(HttpStatus.SERVICE_UNAVAILABLE, "RECEPTION_UNAVAILABLE"));
    }

    @ExceptionHandler(Rejected.class)
    ResponseEntity<ProblemDetail> rejected(Rejected error) {
        return ResponseEntity.status(error.status).body(detail(error.status, error.code));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<ProblemDetail> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(detail(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
    }
}
