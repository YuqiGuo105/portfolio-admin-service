package site.yuqi.admin.dto;

import java.time.Instant;

public record ProjectionStatusResponse(
        String sourceType,
        String sourceId,
        int sourceVersion,
        String overallStatus,
        Projection search,
        Projection rag,
        Projection notification) {

    public record Projection(String status, int attempts, Instant updatedAt, String error) {}
}
