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
public class S3QuarantineStorage implements QuarantineStorage, com.skillhunters.retention.ErasureLedger, AutoCloseable {
    private final S3Client s3;
    private final S3Client ledgerClient;
    private final String bucket;
    private final String ledgerBucket;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    public S3QuarantineStorage(@Value("${app.storage.endpoint}") URI endpoint,
            @Value("${app.storage.access-key}") String accessKey,
            @Value("${app.storage.secret-key}") String secretKey,
            @Value("${app.storage.bucket}") String bucket,
            @Value("${app.storage.region}") String region,
            @Value("${app.storage.create-bucket:false}") boolean createBucket,
            @Value("${app.retention.ledger-bucket:skillhunters-erasure-ledger}") String ledgerBucket, com.fasterxml.jackson.databind.ObjectMapper json,
            @Value("${app.retention.ledger-endpoint:}") String ledgerEndpoint,
            @Value("${app.retention.ledger-access-key:}") String ledgerAccess,
            @Value("${app.retention.ledger-secret-key:}") String ledgerSecret) {
        if(bucket.equals(ledgerBucket))throw new IllegalArgumentException("SEPARATE_LEDGER_BUCKET_REQUIRED");this.ledgerBucket=ledgerBucket;this.json=json;
        this.bucket = bucket;
        s3 = S3Client.builder().endpointOverride(endpoint).region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(true)
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(20)))
            .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(45)).apiCallAttemptTimeout(Duration.ofSeconds(25)))
            .build();
        if(ledgerEndpoint.isBlank())ledgerClient=s3;
        else {
            if(ledgerAccess.isBlank()||ledgerSecret.isBlank())throw new IllegalArgumentException("LEDGER_CREDENTIALS_REQUIRED");
            ledgerClient=S3Client.builder().endpointOverride(URI.create(ledgerEndpoint)).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ledgerAccess,ledgerSecret)))
                .forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(20)))
                .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(45)).apiCallAttemptTimeout(Duration.ofSeconds(25))).build();
        }
        if (createBucket) {
            try{ledgerClient.headBucket(b->b.bucket(ledgerBucket));}catch(software.amazon.awssdk.services.s3.model.S3Exception e){if(e.statusCode()!=404)throw e;ledgerClient.createBucket(b->b.bucket(ledgerBucket));}
            try { s3.headBucket(b -> b.bucket(bucket)); }
            catch (software.amazon.awssdk.services.s3.model.S3Exception missing) {
                if (missing.statusCode() != 404) throw missing;
                s3.createBucket(b -> b.bucket(bucket));
            }
        }
    }

    public S3QuarantineStorage(URI endpoint,String accessKey,String secretKey,String bucket,String region,boolean createBucket,String ledgerBucket,com.fasterxml.jackson.databind.ObjectMapper json) {
        this(endpoint,accessKey,secretKey,bucket,region,createBucket,ledgerBucket,json,"","","");
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

    @Override public byte[] read(String key) {
        try (var input = s3.getObject(b -> b.bucket(bucket).key(key))) {
            byte[] bytes = input.readNBytes(15728641);
            if (bytes.length > 15728640) throw new IllegalStateException("OBJECT_LIMIT");
            return bytes;
        } catch (Exception unavailable) {
            throw new Rejected(HttpStatus.SERVICE_UNAVAILABLE, "QUARANTINE_UNAVAILABLE");
        }
    }


    @Override public void delete(String key) {
        try {
            for(int page=0;page<100;page++){
                var versions=s3.listObjectVersions(b->b.bucket(bucket).prefix(key).maxKeys(1000));
                int removed=0;
                for(var v:versions.versions())if(v.key().equals(key)){s3.deleteObject(b->b.bucket(bucket).key(key).versionId(v.versionId()));removed++;}
                for(var v:versions.deleteMarkers())if(v.key().equals(key)){s3.deleteObject(b->b.bucket(bucket).key(key).versionId(v.versionId()));removed++;}
                if(removed==0){
                    try{s3.headObject(b->b.bucket(bucket).key(key));s3.deleteObject(b->b.bucket(bucket).key(key));}
                    catch(software.amazon.awssdk.services.s3.model.S3Exception absent){if(absent.statusCode()==404)return;throw absent;}
                }
            }
            throw new IllegalStateException("OBJECT_VERSIONS_REMAIN");
        }catch(RuntimeException failure){throw new IllegalStateException("ERASURE_STORAGE_UNAVAILABLE");}
    }
    @Override public void append(com.skillhunters.retention.ErasureRecord record){
        String name="v1/"+record.sourceId()+".json";
        try{
            try{var existing=json.readValue(ledgerClient.getObjectAsBytes(b->b.bucket(ledgerBucket).key(name)).asByteArray(),com.skillhunters.retention.ErasureRecord.class);if(!existing.equals(record))throw new IllegalStateException("ERASURE_LEDGER_CONFLICT");return;}
            catch(software.amazon.awssdk.services.s3.model.S3Exception absent){if(absent.statusCode()!=404)throw absent;}
            ledgerClient.putObject(b->b.bucket(ledgerBucket).key(name).contentType("application/json").ifNoneMatch("*"),RequestBody.fromBytes(json.writeValueAsBytes(record)));
        }catch(Exception failure){throw new IllegalStateException("ERASURE_LEDGER_UNAVAILABLE");}
    }
    @Override public void scan(java.util.function.Consumer<com.skillhunters.retention.ErasureRecord> consumer){
        try{
            for(var page:ledgerClient.listObjectsV2Paginator(b->b.bucket(ledgerBucket).prefix("v1/").maxKeys(1000))){
                for(var object:page.contents()){
                    if(object.size()>4096)throw new IllegalStateException("INVALID_ERASURE_LEDGER");
                    var r=json.readValue(ledgerClient.getObjectAsBytes(b->b.bucket(ledgerBucket).key(object.key())).asByteArray(),com.skillhunters.retention.ErasureRecord.class);
                    if(!object.key().equals("v1/"+r.sourceId()+".json"))throw new IllegalStateException("INVALID_ERASURE_LEDGER");
                    consumer.accept(r);
                }
            }
        }catch(Exception failure){throw new IllegalStateException("ERASURE_REPLAY_UNAVAILABLE",failure);}
    }
    @Override public void close() { if(ledgerClient!=s3)ledgerClient.close();s3.close(); }
}
