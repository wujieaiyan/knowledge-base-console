package com.kbconsole.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbconsole.common.ApiException;
import com.kbconsole.common.ErrorCodes;
import com.kbconsole.common.IndexStrategy;
import com.kbconsole.config.dto.ConfigModels;
import com.kbconsole.config.dto.ConfigModels.ChunkConfig;
import com.kbconsole.config.dto.ConfigModels.ParseConfig;
import com.kbconsole.config.dto.ConfigModels.RetrievalConfig;
import com.kbconsole.series.KbSeries;
import com.kbconsole.series.KbSeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
@RequiredArgsConstructor
public class ConfigService {
    private static final Set<String> DOC_TYPES = Set.of("PDF", "DOCX", "XLSX", "MD");
    private static final Set<String> PARSE_STRATEGIES = Set.of("NATIVE", "OCR", "AUTO");

    private final KbConfigVersionRepository configRepository;
    private final KbSeriesRepository seriesRepository;
    private final ObjectMapper objectMapper;

    public record ConfigResponse(Long versionId, int versionNo, String indexStrategy, ParseConfig parse,
                                 ChunkConfig chunk, RetrievalConfig retrieval, String changeNote, Instant createdAt) {
    }

    public record SavedConfig(Long versionId, int versionNo) {
    }

    @Transactional
    public SavedConfig createVersion(Long seriesId, String indexStrategyValue, ParseConfig parseInput,
                                     ChunkConfig chunkInput, RetrievalConfig retrievalInput,
                                     String changeNote, Long userId) {
        KbSeries series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND, "知识库不存在"));

        ParseConfig parse = normalizeParse(parseInput);
        ChunkConfig chunk = normalizeChunk(chunkInput);
        RetrievalConfig retrieval = normalizeRetrieval(retrievalInput);
        IndexStrategy indexStrategy = resolveIndexStrategy(indexStrategyValue, series.getIndexStrategy());
        validate(parse, chunk, retrieval);

        int nextVersion = configRepository.findFirstBySeriesIdOrderByVersionNoDesc(seriesId)
                .map(v -> v.getVersionNo() + 1).orElse(1);
        KbConfigVersion version = new KbConfigVersion();
        version.setSeriesId(seriesId);
        version.setVersionNo(nextVersion);
        version.setIndexStrategy(indexStrategy);
        version.setParseConfig(write(parse));
        version.setChunkConfig(write(chunk));
        version.setRetrievalConfig(write(retrieval));
        version.setChangeNote(changeNote);
        version.setCreatedBy(userId);
        configRepository.save(version);

        series.setActiveConfigVersionId(version.getId());
        series.setIndexStrategy(indexStrategy);
        seriesRepository.save(series);
        return new SavedConfig(version.getId(), version.getVersionNo());
    }

    public ConfigResponse current(Long seriesId) {
        KbSeries series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND, "知识库不存在"));
        KbConfigVersion version = series.getActiveConfigVersionId() == null
                ? configRepository.findFirstBySeriesIdOrderByVersionNoDesc(seriesId).orElse(null)
                : configRepository.findByIdAndSeriesId(series.getActiveConfigVersionId(), seriesId).orElse(null);
        if (version == null) {
            return new ConfigResponse(null, 0, series.getIndexStrategy().name(),
                    ConfigModels.defaultParse(), ConfigModels.defaultChunk(), ConfigModels.defaultRetrieval(),
                    null, null);
        }
        return toResponse(version);
    }

    public ConfigResponse version(Long seriesId, Long versionId) {
        KbConfigVersion version = configRepository.findByIdAndSeriesId(versionId, seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.INVALID_CONFIG, "配置版本不存在"));
        return toResponse(version);
    }

    public List<ConfigResponse> versions(Long seriesId) {
        return configRepository.findBySeriesIdOrderByVersionNoDesc(seriesId).stream().map(this::toResponse).toList();
    }

    public KbConfigVersion entityForTask(Long seriesId, Long versionId) {
        if (versionId != null) {
            return configRepository.findByIdAndSeriesId(versionId, seriesId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.INVALID_CONFIG, "配置版本不存在"));
        }
        KbSeries series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND, "知识库不存在"));
        Long activeId = series.getActiveConfigVersionId();
        if (activeId != null) {
            return configRepository.findByIdAndSeriesId(activeId, seriesId).orElse(null);
        }
        return configRepository.findFirstBySeriesIdOrderByVersionNoDesc(seriesId).orElse(null);
    }

    public ParseConfig parse(KbConfigVersion version) {
        return read(version.getParseConfig(), ParseConfig.class, ConfigModels.defaultParse());
    }

    public ChunkConfig chunk(KbConfigVersion version) {
        return read(version.getChunkConfig(), ChunkConfig.class, ConfigModels.defaultChunk());
    }

    public RetrievalConfig retrieval(KbConfigVersion version) {
        return read(version.getRetrievalConfig(), RetrievalConfig.class, ConfigModels.defaultRetrieval());
    }

    private ConfigResponse toResponse(KbConfigVersion version) {
        return new ConfigResponse(version.getId(), version.getVersionNo(), version.getIndexStrategy().name(),
                parse(version), chunk(version), retrieval(version), version.getChangeNote(), version.getCreatedAt());
    }

    private IndexStrategy resolveIndexStrategy(String value, IndexStrategy fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? IndexStrategy.HYBRID : fallback;
        }
        try {
            IndexStrategy strategy = IndexStrategy.valueOf(value.toUpperCase());
            if (strategy == IndexStrategy.GRAPH) {
                throw invalid("indexStrategy", "图谱增强索引暂未开放");
            }
            return strategy;
        } catch (IllegalArgumentException ex) {
            throw invalid("indexStrategy", "索引策略不合法");
        }
    }

    public ParseConfig normalizeParse(ParseConfig input) {
        ParseConfig config = input == null ? ConfigModels.defaultParse() : input;
        List<String> types = new ArrayList<>();
        if (config.getDocTypes() != null) {
            for (String type : config.getDocTypes()) {
                if (type != null && DOC_TYPES.contains(type.toUpperCase())) {
                    types.add(type.toUpperCase());
                }
            }
        }
        if (types.isEmpty()) {
            types.addAll(List.of("PDF", "DOCX", "XLSX", "MD"));
        }
        config.setDocTypes(types.stream().distinct().toList());
        Map<String, ConfigModels.TypeParseConfig> perType = new LinkedHashMap<>();
        for (String type : config.getDocTypes()) {
            ConfigModels.TypeParseConfig existing = config.getPerType() == null ? null : config.getPerType().get(type);
            if (existing == null) {
                existing = new ConfigModels.TypeParseConfig(true, "PDF".equals(type) ? "AUTO" : "NATIVE");
            }
            if (existing.getStrategy() == null) {
                existing.setStrategy("PDF".equals(type) ? "AUTO" : "NATIVE");
            }
            existing.setStrategy(existing.getStrategy().toUpperCase());
            perType.put(type, existing);
        }
        config.setPerType(perType);
        if (config.getOcr() == null) {
            config.setOcr(new ConfigModels.OcrConfig());
        }
        if (config.getOcr().getLanguages() == null || config.getOcr().getLanguages().isEmpty()) {
            config.getOcr().setLanguages(List.of("zh", "en"));
        }
        if (config.getOptions() == null) {
            config.setOptions(new ConfigModels.ParseOptions());
        }
        return config;
    }

    public ChunkConfig normalizeChunk(ChunkConfig input) {
        ChunkConfig config = input == null ? ConfigModels.defaultChunk() : input;
        if (config.getCommon() == null) {
            config.setCommon(new ConfigModels.CommonChunkConfig());
        }
        if (config.getByLine() == null) {
            config.setByLine(new ConfigModels.ByLineConfig());
        }
        if (config.getParentChild() == null) {
            config.setParentChild(new ConfigModels.ParentChildConfig());
        }
        if (config.getTitleEnhanced() == null) {
            config.setTitleEnhanced(new ConfigModels.TitleEnhancedConfig());
        }
        if (config.getCustom() == null) {
            config.setCustom(new ConfigModels.CustomConfig());
        }
        if (config.getStrategy() == null) {
            config.setStrategy("DEFAULT");
        }
        config.setStrategy(config.getStrategy().toUpperCase());
        return config;
    }

    public RetrievalConfig normalizeRetrieval(RetrievalConfig input) {
        RetrievalConfig config = input == null ? ConfigModels.defaultRetrieval() : input;
        if (config.getCitation() == null) {
            config.setCitation(new ConfigModels.CitationConfig());
        }
        if (config.getCitation().getGranularity() == null) {
            config.getCitation().setGranularity("PARAGRAPH");
        }
        if (config.getNoAnswerStrategy() == null) {
            config.setNoAnswerStrategy("EMPTY");
        }
        return config;
    }

    public void validate(ParseConfig parse, ChunkConfig chunk, RetrievalConfig retrieval) {
        if (parse.getDocTypes().isEmpty()) {
            throw invalid("parse.docTypes", "请至少选择一种文档类型");
        }
        for (Map.Entry<String, ConfigModels.TypeParseConfig> entry : parse.getPerType().entrySet()) {
            ConfigModels.TypeParseConfig typeConfig = entry.getValue();
            if (!PARSE_STRATEGIES.contains(typeConfig.getStrategy())) {
                throw invalid("parse.perType." + entry.getKey() + ".strategy", "解析方式不合法");
            }
            if (!"PDF".equals(entry.getKey()) && "OCR".equals(typeConfig.getStrategy())
                    && !parse.getOptions().isOcrEmbeddedImages()) {
                throw invalid("parse.perType." + entry.getKey() + ".strategy",
                        "非 PDF 文档启用 OCR 时，需要开启“提取文档内图片文字”");
            }
        }

        ConfigModels.CommonChunkConfig common = chunk.getCommon();
        if (common.getMaxTokens() < 64 || common.getMaxTokens() > 4096) {
            throw invalid("chunk.common.maxTokens", "切片长度需在 64-4096 之间");
        }
        if (common.getOverlapTokens() < 0 || common.getOverlapTokens() > common.getMaxTokens() / 2) {
            throw invalid("chunk.common.overlapTokens", "重叠长度不能超过最大长度的一半");
        }
        if (common.getMinTokens() < 0 || common.getMinTokens() > common.getMaxTokens()) {
            throw invalid("chunk.common.minTokens", "最小长度不能大于最大长度");
        }
        if (!Set.of("DEFAULT", "BY_LINE", "PARENT_CHILD", "TITLE_ENHANCED", "CUSTOM").contains(chunk.getStrategy())) {
            throw invalid("chunk.strategy", "切片策略不合法");
        }
        ConfigModels.ParentChildConfig pc = chunk.getParentChild();
        if ("PARENT_CHILD".equals(chunk.getStrategy())) {
            if (pc.getParentTokens() < 256 || pc.getParentTokens() > 4096) {
                throw invalid("chunk.parentChild.parentTokens", "父块长度需在 256-4096 之间");
            }
            if (pc.getChildTokens() < 64 || pc.getChildTokens() >= pc.getParentTokens()) {
                throw invalid("chunk.parentChild.childTokens", "子块长度必须小于父块长度且不小于 64");
            }
            if (pc.getChildOverlap() < 0 || pc.getChildOverlap() >= pc.getChildTokens() / 2) {
                throw invalid("chunk.parentChild.childOverlap", "子块重叠需小于子块长度的一半");
            }
            if (pc.getMaxChildrenPerParent() < 1 || pc.getMaxChildrenPerParent() > 32) {
                throw invalid("chunk.parentChild.maxChildrenPerParent", "每个父块的子块上限需在 1-32 之间");
            }
        }
        if ("CUSTOM".equals(chunk.getStrategy())) {
            String separator = chunk.getCustom().getSeparator();
            if (separator == null || separator.isBlank()) {
                throw invalid("chunk.custom.separator", "自定义分隔符不能为空");
            }
            try {
                Pattern.compile(separator);
            } catch (PatternSyntaxException ex) {
                throw invalid("chunk.custom.separator", "分隔符不是合法的正则表达式");
            }
        }

        if (retrieval.getRecallTopK() < 1 || retrieval.getRecallTopK() > 100) {
            throw invalid("retrieval.recallTopK", "TopK 需在 1-100 之间");
        }
        if (retrieval.getRerankTopN() < 1 || retrieval.getRerankTopN() > 20
                || retrieval.getRerankTopN() > retrieval.getRecallTopK()) {
            throw invalid("retrieval.rerankTopN", "精排数量需在 1-20 之间且不能大于召回数量");
        }
        if (retrieval.getSimilarityThreshold() < 0 || retrieval.getSimilarityThreshold() > 1) {
            throw invalid("retrieval.similarityThreshold", "相似度阈值需在 0-1 之间");
        }
        if (retrieval.getVectorWeight() < 0 || retrieval.getVectorWeight() > 1) {
            throw invalid("retrieval.vectorWeight", "向量权重需在 0-1 之间");
        }
    }

    private ApiException invalid(String field, String reason) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_CONFIG, "配置校验失败",
                List.of(Map.of("field", field, "reason", reason)));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize config", ex);
        }
    }

    private <T> T read(String json, Class<T> type, T fallback) {
        try {
            return json == null || json.isBlank() ? fallback : objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }
}
