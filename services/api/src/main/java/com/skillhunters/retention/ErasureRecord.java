package com.skillhunters.retention;
import java.time.OffsetDateTime;
import java.util.UUID;
public record ErasureRecord(UUID sourceId,String kind,UUID poolId,String replayHash,OffsetDateTime requestedAt,String signature) {}
