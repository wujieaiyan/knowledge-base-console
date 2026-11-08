package com.kbconsole.series;

import java.time.Instant;

public record SeriesView(
        Long id,
        String name,
        String description,
        Long deptId,
        String deptName,
        Long authorId,
        String authorName,
        String status,
        String displayStatus,
        int docCount,
        int readyDocCount,
        int failedDocCount,
        long chunkCount,
        String indexStrategy,
        Long configVersionId,
        String role,
        Long rowVersion,
        Instant createdAt,
        Instant updatedAt
) {
}
