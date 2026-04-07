package com.ticket.core.idempotency.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.common.exception.BusinessException;
import com.ticket.core.common.exception.ErrorCode;
import com.ticket.core.idempotency.entity.IdempotencyRecord;
import com.ticket.core.idempotency.mapper.IdempotencyRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * Computes a SHA-256 hex digest of the canonical JSON form of the request object.
     * Used to detect IDEMPOTENCY_CONFLICT (same key, different payload).
     */
    public String hashRequest(Object request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to hash request payload", e);
        }
    }

    /**
     * Checks for an existing idempotency record.
     * <ul>
     *   <li>If none exists: inserts a PROCESSING record and returns it (caller must execute business logic).</li>
     *   <li>If exists with the same hash and SUCCEEDED: returns the record (caller should replay the response).</li>
     *   <li>If exists with the same hash and PROCESSING: returns the record (in-flight duplicate).</li>
     *   <li>If exists with a different hash: throws IDEMPOTENCY_CONFLICT.</li>
     * </ul>
     */
    public IdempotencyRecord checkAndMarkProcessing(String actionName, String idempotencyKey,
                                                    String requestHash, String externalTradeNo) {
        LambdaQueryWrapper<IdempotencyRecord> query = new LambdaQueryWrapper<IdempotencyRecord>()
                .eq(IdempotencyRecord::getActionName, actionName)
                .eq(IdempotencyRecord::getIdempotencyKey, idempotencyKey);

        IdempotencyRecord existing = mapper.selectOne(query);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                log.warn("Idempotency conflict detected: action={}, key={}", actionName, idempotencyKey);
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return existing;
        }

        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyRecordId(UUID.randomUUID().toString());
        record.setActionName(actionName);
        record.setIdempotencyKey(idempotencyKey);
        record.setRequestHash(requestHash);
        record.setExternalTradeNo(externalTradeNo);
        record.setStatus("PROCESSING");

        try {
            mapper.insert(record);
            return record; // newly created PROCESSING record — caller must execute business logic
        } catch (DuplicateKeyException e) {
            // Concurrent insert won; re-read and validate
            existing = mapper.selectOne(query);
            if (existing != null && !existing.getRequestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return existing;
        }
    }

    /**
     * Marks the idempotency record as SUCCEEDED and caches the serialized response for future replay.
     */
    public void markSucceeded(String idempotencyRecordId, String resourceType,
                              String resourceId, Object responseBody) {
        try {
            String payload = objectMapper.writeValueAsString(responseBody);
            IdempotencyRecord update = new IdempotencyRecord();
            update.setIdempotencyRecordId(idempotencyRecordId);
            update.setResourceType(resourceType);
            update.setResourceId(resourceId);
            update.setStatus("SUCCEEDED");
            update.setResponsePayload(payload);
            mapper.updateById(update);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response payload for idempotency record {}", idempotencyRecordId, e);
            throw new IllegalStateException("Failed to serialize response for idempotency cache", e);
        }
    }

    /**
     * Deserializes the cached response payload into the specified response class.
     */
    public <T> T replayResponse(IdempotencyRecord record, Class<T> responseClass) {
        try {
            return objectMapper.readValue(record.getResponsePayload(), responseClass);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached response for idempotency record {}",
                    record.getIdempotencyRecordId(), e);
            throw new IllegalStateException("Failed to replay cached idempotent response", e);
        }
    }
}
