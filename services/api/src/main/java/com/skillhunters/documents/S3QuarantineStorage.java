package com.skillhunters.documents;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import com.skillhunters.shared.ApiProblem.Rejected;
import org.springframework.http.HttpStatus;

@Component
@Profile("!test")
public class S3QuarantineStorage implements QuarantineStorage, AutoCloseable {
    private final S3Client s3;
    private final String bucket;

    public S3QuarantineStorage(@Value("${app.storage.endpoint}") URI endpoint,
            @Value("${app.storage.access-key}") String accessKey,
            @Value("${app.storage.secret-key}") String secretKey,
            @Value("${app.storage.bucket}") String bucket,
            @Value("${app.storage.region}") String region,
            @Value("${app.storage.create-bucket:false}") boolean createBucket) {
        this.bucket = bucket;
        s3 = S3Client.builder().endpointOverride(endpoint).region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(true)
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(20)))
            .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(45)).apiCallAttemptTimeout(Duration.ofSeconds(25)))
            .build();
        if (createBucket) {
            try { s3.headBucket(b -> b.bucket(bucket)); }
            catch (software.amazon.awssdk.services.s3.model.S3Exception missing) {
                if (missing.statusCode() != 404) throw missing;
                s3.createBucket(b -> b.bucket(bucket));
            }
        }
    }

    @Override public void put(String key, Path file) {
        try {
            s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/octet-stream")
                    .contentDisposition("attachment"), RequestBody.fromFile(file));
        } catch (RuntimeException unavailable) {
            // Do not expose endpoints, credentials, object keys or provider messages.
            throw new Rejected(HttpStatus.SERVICE_UNAVAILABLE, "QUARANTINE_UNAVAILABLE");
        }
    }

    @Override public void close() { s3.close(); }
}
