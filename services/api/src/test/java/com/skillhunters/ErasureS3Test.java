package com.skillhunters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillhunters.documents.S3QuarantineStorage;
import com.skillhunters.retention.*;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named="SH32_S3_ENDPOINT",matches=".+")
class ErasureS3Test {
    @Test void allObjectVersionsAreErasedAndIndependentSignedLedgerSurvives() {
        var endpoint=URI.create(System.getenv("SH32_S3_ENDPOINT"));
        var access=System.getenv("S3_ACCESS_KEY");var secret=System.getenv("S3_SECRET_KEY");
        var bucket="sh32-originals-"+UUID.randomUUID();var ledger="sh32-ledger-"+UUID.randomUUID();
        var json=new ObjectMapper().registerModule(new JavaTimeModule());
        try(var s3=S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret))).build();
            var storage=new S3QuarantineStorage(endpoint,access,secret,bucket,"us-east-1",true,ledger,json)) {
            s3.putBucketVersioning(b->b.bucket(bucket).versioningConfiguration(v->v.status(BucketVersioningStatus.ENABLED)));
            String key="quarantine/synthetic/source";
            s3.putObject(b->b.bucket(bucket).key(key),RequestBody.fromString("Synthetic first version"));
            s3.putObject(b->b.bucket(bucket).key(key),RequestBody.fromString("Synthetic second version"));
            s3.deleteObject(b->b.bucket(bucket).key(key));
            s3.putObject(b->b.bucket(bucket).key(key+"-other"),RequestBody.fromString("Other source"));
            assertThat(s3.listObjectVersions(b->b.bucket(bucket).prefix(key)).versions()).hasSize(3);
            storage.delete(key);storage.delete(key);
            assertThat(s3.listObjectVersions(b->b.bucket(bucket).prefix(key)).versions()).allSatisfy(v->assertThat(v.key()).isEqualTo(key+"-other")).hasSize(1);
            assertThat(s3.listObjectVersions(b->b.bucket(bucket).prefix(key)).deleteMarkers()).isEmpty();
            var guard=new RetentionGuard(mock(org.springframework.jdbc.core.simple.JdbcClient.class),"synthetic-only-erasure-test-signing-key");
            var record=guard.sign(UUID.randomUUID(),"IMPORT",UUID.randomUUID(),"a".repeat(64),OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
            storage.append(record);storage.append(record);
            var read=new ArrayList<ErasureRecord>();storage.scan(read::add);
            assertThat(read).containsExactly(record);guard.verify(read.getFirst());
            // Simulate objects restored after an acknowledged erase: same tombstone can purge again.
            s3.putObject(b->b.bucket(bucket).key(key),RequestBody.fromString("Restored old synthetic bytes"));
            storage.delete(key);read.clear();storage.scan(read::add);assertThat(read).containsExactly(record);
            storage.delete(key+"-other");
            for(var item:s3.listObjectsV2(b->b.bucket(ledger)).contents())s3.deleteObject(b->b.bucket(ledger).key(item.key()));
            s3.deleteBucket(b->b.bucket(bucket));s3.deleteBucket(b->b.bucket(ledger));
        }
    }
}
