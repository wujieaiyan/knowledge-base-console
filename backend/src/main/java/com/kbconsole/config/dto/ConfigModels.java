package com.kbconsole.config.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigModels {
    private ConfigModels() {
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ParseConfig {
        private List<String> docTypes = new ArrayList<>(List.of("PDF", "DOCX", "XLSX", "MD"));
        private Map<String, TypeParseConfig> perType = new LinkedHashMap<>();
        private OcrConfig ocr = new OcrConfig();
        private ParseOptions options = new ParseOptions();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class TypeParseConfig {
        private boolean enabled = true;
        private String strategy = "NATIVE";

        public TypeParseConfig(boolean enabled, String strategy) {
            this.enabled = enabled;
            this.strategy = strategy;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class OcrConfig {
        private List<String> languages = new ArrayList<>(List.of("zh", "en"));
        private double minConfidence = 0.6;
        private boolean keepLayout = true;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ParseOptions {
        private boolean tableStructure = true;
        private boolean filterHeaderFooter = true;
        private boolean ocrEmbeddedImages = false;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ChunkConfig {
        private String strategy = "DEFAULT";
        private CommonChunkConfig common = new CommonChunkConfig();
        private ByLineConfig byLine = new ByLineConfig();
        private ParentChildConfig parentChild = new ParentChildConfig();
        private TitleEnhancedConfig titleEnhanced = new TitleEnhancedConfig();
        private CustomConfig custom = new CustomConfig();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class CommonChunkConfig {
        private int maxTokens = 512;
        private int overlapTokens = 64;
        private int minTokens = 50;
        private boolean keepTableWhole = true;
        private String tokenizer = "cl100k_base";
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ByLineConfig {
        private int maxTokens = 512;
        private boolean dropEmpty = true;
        private boolean mergeShortLines = true;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class ParentChildConfig {
        private int parentTokens = 1024;
        private int childTokens = 256;
        private int childOverlap = 32;
        private int maxChildrenPerParent = 8;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class TitleEnhancedConfig {
        private String template = "{doc} > {h1} > {h2}";
        private boolean keepTitlePath = true;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class CustomConfig {
        private String separator = "\\n\\n";
        private boolean keepSeparator = true;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class RetrievalConfig {
        private boolean hybridEnabled = true;
        private double vectorWeight = 0.7;
        private boolean rerankEnabled = true;
        private String rerankModel = "local-hash-rerank";
        private CitationConfig citation = new CitationConfig();
        private int recallTopK = 20;
        private int rerankTopN = 5;
        private double similarityThreshold = 0.30;
        private String noAnswerStrategy = "EMPTY";
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class CitationConfig {
        private boolean enabled = true;
        private String granularity = "PARAGRAPH";
    }

    public static ParseConfig defaultParse() {
        ParseConfig config = new ParseConfig();
        config.getPerType().put("PDF", new TypeParseConfig(true, "AUTO"));
        config.getPerType().put("DOCX", new TypeParseConfig(true, "NATIVE"));
        config.getPerType().put("XLSX", new TypeParseConfig(true, "NATIVE"));
        config.getPerType().put("MD", new TypeParseConfig(true, "NATIVE"));
        return config;
    }

    public static ChunkConfig defaultChunk() {
        return new ChunkConfig();
    }

    public static RetrievalConfig defaultRetrieval() {
        return new RetrievalConfig();
    }
}
