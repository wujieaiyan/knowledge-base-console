package com.kbconsole.integration;

import com.kbconsole.common.ChunkLevel;
import com.kbconsole.document.KbDocument;
import com.kbconsole.document.KbDocumentRepository;
import com.kbconsole.ingest.KbChunk;
import com.kbconsole.ingest.KbChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexBootstrap {
    private final ElasticsearchProperties properties;
    private final ElasticsearchService elasticsearchService;
    private final KbChunkRepository chunkRepository;
    private final KbDocumentRepository documentRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (!properties.isEnabled()) {
            log.info("Elasticsearch 未启用，检索使用本地 H2 索引");
            return;
        }
        if (!elasticsearchService.ensureIndex()) {
            log.warn("Elasticsearch 初始化失败，应用将继续使用本地 H2 检索");
            return;
        }
        long count = elasticsearchService.count();
        log.info("Elasticsearch 索引就绪：index={}, documents={}", properties.getIndex(), count);
        if (count > 0 || !properties.isReindexOnStartup()) {
            return;
        }
        reindexAll();
    }

    private void reindexAll() {
        List<KbChunk> chunks = chunkRepository.findByStatus("ACTIVE");
        if (chunks.isEmpty()) {
            log.info("本地没有可回填到 Elasticsearch 的切片");
            return;
        }
        Map<Long, List<KbChunk>> grouped = chunks.stream()
                .filter(chunk -> chunk.getChunkLevel() != ChunkLevel.PARENT)
                .collect(Collectors.groupingBy(KbChunk::getDocumentId));
        Map<Long, KbDocument> documents = documentRepository.findAllById(grouped.keySet()).stream()
                .filter(document -> document.getDeletedAt() == null)
                .collect(Collectors.toMap(KbDocument::getId, Function.identity()));
        int indexed = 0;
        int failed = 0;
        for (Map.Entry<Long, List<KbChunk>> entry : grouped.entrySet()) {
            KbDocument document = documents.get(entry.getKey());
            if (document == null) {
                continue;
            }
            ElasticsearchService.IndexResult result = elasticsearchService.indexDocument(document, entry.getValue());
            if (result.success()) {
                indexed += result.count();
            } else {
                failed++;
                log.warn("回填文档 #{} 到 Elasticsearch 失败：{}", document.getId(), result.message());
            }
        }
        log.info("Elasticsearch 启动回填完成：documents={}, chunks={}, failed={}",
                documents.size(), indexed, failed);
    }
}
