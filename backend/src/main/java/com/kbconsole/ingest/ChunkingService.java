package com.kbconsole.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbconsole.common.ChunkLevel;
import com.kbconsole.config.dto.ConfigModels.ChunkConfig;
import com.kbconsole.config.dto.ConfigModels.CommonChunkConfig;
import com.kbconsole.config.dto.ConfigModels.ParentChildConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingService {
    private final ObjectMapper objectMapper;

    public record ChunkDraft(int seq, ChunkLevel level, Integer parentIndex, String content, int tokenCount,
                             Integer charStart, Integer charEnd, Integer pageNo, String titlePath,
                             String metadata) {
    }

    private record Piece(String text, int tokens, Integer pageNo, String titlePath, String kind, String groupKey) {
    }

    public List<ChunkDraft> chunk(String documentName, ParseResult parseResult, ChunkConfig config) {
        String strategy = config.getStrategy() == null ? "DEFAULT" : config.getStrategy().toUpperCase();
        CommonChunkConfig common = config.getCommon();
        List<ChunkDraft> drafts = switch (strategy) {
            case "BY_LINE" -> byLine(parseResult, config);
            case "PARENT_CHILD" -> parentChild(parseResult, config);
            case "TITLE_ENHANCED" -> titleEnhanced(documentName, parseResult, config);
            case "CUSTOM" -> custom(parseResult, config);
            default -> defaultChunks(parseResult, common);
        };
        List<ChunkDraft> finalized = new ArrayList<>();
        int offset = 0;
        for (ChunkDraft draft : drafts) {
            String content = draft.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            finalized.add(new ChunkDraft(finalized.size(), draft.level(), draft.parentIndex(), content,
                    countTokens(content), offset, offset + content.length(), draft.pageNo(),
                    draft.titlePath(), draft.metadata()));
            offset += content.length() + 1;
        }
        log.info("Chunked document [{}] strategy={} chunks={}", documentName, strategy, finalized.size());
        return finalized;
    }

    private List<ChunkDraft> defaultChunks(ParseResult parseResult, CommonChunkConfig common) {
        int max = Math.max(64, common.getMaxTokens());
        int overlap = Math.max(0, Math.min(common.getOverlapTokens(), max / 2));
        int min = Math.max(0, Math.min(common.getMinTokens(), max));
        List<Piece> pieces = pieces(parseResult.blocks(), max);
        List<ChunkDraft> drafts = pack(pieces, max, overlap, "DEFAULT");
        return mergeSmall(drafts, max, min, "DEFAULT");
    }

    private List<ChunkDraft> byLine(ParseResult parseResult, ChunkConfig config) {
        int max = Math.max(64, config.getCommon().getMaxTokens());
        int lineMax = config.getByLine().getMaxTokens() > 0
                ? Math.min(config.getByLine().getMaxTokens(), max) : max;
        boolean merge = config.getByLine().isMergeShortLines();
        boolean dropEmpty = config.getByLine().isDropEmpty();
        List<ChunkDraft> drafts = new ArrayList<>();
        for (TextBlock block : parseResult.blocks()) {
            StringBuilder buffer = new StringBuilder();
            int tokens = 0;
            for (String rawLine : block.text().split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    if (!dropEmpty) {
                        continue;
                    }
                    continue;
                }
                List<String> segments = countTokens(line) > lineMax ? hardSplit(line, lineMax) : List.of(line);
                for (String segment : segments) {
                    int segmentTokens = countTokens(segment);
                    if (!merge) {
                        add(drafts, ChunkLevel.SINGLE, null, segment, block.pageNo(), block.titlePath(), "BY_LINE");
                        continue;
                    }
                    if (tokens > 0 && tokens + segmentTokens > lineMax) {
                        add(drafts, ChunkLevel.SINGLE, null, buffer.toString(), block.pageNo(), block.titlePath(), "BY_LINE");
                        buffer.setLength(0);
                        tokens = 0;
                    }
                    if (!buffer.isEmpty()) {
                        buffer.append('\n');
                    }
                    buffer.append(segment);
                    tokens += segmentTokens;
                }
            }
            if (tokens > 0) {
                add(drafts, ChunkLevel.SINGLE, null, buffer.toString(), block.pageNo(), block.titlePath(), "BY_LINE");
            }
        }
        return drafts;
    }

    private List<ChunkDraft> parentChild(ParseResult parseResult, ChunkConfig config) {
        ParentChildConfig pc = config.getParentChild();
        int parentTokens = Math.max(128, pc.getParentTokens());
        int childTokens = Math.max(32, Math.min(pc.getChildTokens(), parentTokens));
        int childOverlap = Math.max(0, Math.min(pc.getChildOverlap(), childTokens / 2));
        int maxChildren = Math.max(1, pc.getMaxChildrenPerParent());
        List<Piece> pieces = pieces(parseResult.blocks(), parentTokens);
        List<ChunkDraft> parents = pack(pieces, parentTokens, 0, "PARENT_CHILD");
        List<ChunkDraft> drafts = new ArrayList<>();
        for (ChunkDraft parent : parents) {
            int parentIndex = drafts.size();
            add(drafts, ChunkLevel.PARENT, null, parent.content(), parent.pageNo(), parent.titlePath(), "PARENT_CHILD_PARENT");
            List<String> children = splitWithOverlap(parent.content(), childTokens, childOverlap);
            if (children.size() > maxChildren) {
                List<String> capped = new ArrayList<>(children.subList(0, maxChildren - 1));
                capped.add(String.join("\n", children.subList(maxChildren - 1, children.size())));
                children = capped;
            }
            for (String child : children) {
                add(drafts, ChunkLevel.CHILD, parentIndex, child, parent.pageNo(), parent.titlePath(), "PARENT_CHILD_CHILD");
            }
        }
        return drafts;
    }

    private List<ChunkDraft> titleEnhanced(String documentName, ParseResult parseResult, ChunkConfig config) {
        int max = Math.max(64, config.getCommon().getMaxTokens());
        int overlap = Math.max(0, Math.min(config.getCommon().getOverlapTokens(), max / 2));
        List<Piece> pieces = pieces(parseResult.blocks(), max);
        List<ChunkDraft> packed = pack(pieces, max, overlap, "TITLE_ENHANCED");
        String template = config.getTitleEnhanced().getTemplate();
        boolean keepTitle = config.getTitleEnhanced().isKeepTitlePath();
        List<ChunkDraft> drafts = new ArrayList<>();
        for (ChunkDraft draft : packed) {
            String prefix = keepTitle ? buildTitlePrefix(documentName, draft.titlePath(), template) : "";
            String content = prefix.isBlank() ? draft.content() : prefix + "\n" + draft.content();
            add(drafts, ChunkLevel.SINGLE, null, content, draft.pageNo(), draft.titlePath(), "TITLE_ENHANCED");
        }
        return drafts;
    }

    private List<ChunkDraft> custom(ParseResult parseResult, ChunkConfig config) {
        int max = Math.max(64, config.getCommon().getMaxTokens());
        int overlap = Math.max(0, Math.min(config.getCommon().getOverlapTokens(), max / 2));
        String separator = config.getCustom().getSeparator();
        boolean keepSeparator = config.getCustom().isKeepSeparator();
        List<String> parts = new ArrayList<>();
        try {
            Pattern pattern = Pattern.compile(separator == null || separator.isBlank() ? "\\n\\n" : separator);
            String text = String.join("\n\n", parseResult.blocks().stream().map(TextBlock::text).toList());
            Matcher matcher = pattern.matcher(text);
            int last = 0;
            while (matcher.find()) {
                String part = text.substring(last, matcher.start()) + (keepSeparator ? matcher.group() : "");
                if (!part.isBlank()) {
                    parts.add(part.trim());
                }
                last = matcher.end();
            }
            String tail = text.substring(last);
            if (!tail.isBlank()) {
                parts.add(tail.trim());
            }
            if (parts.isEmpty() && !text.isBlank()) {
                parts.add(text.trim());
            }
        } catch (PatternSyntaxException ex) {
            throw new com.kbconsole.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    com.kbconsole.common.ErrorCodes.INVALID_REGEX, "自定义分隔符不是合法正则表达式");
        }
        List<ChunkDraft> drafts = new ArrayList<>();
        for (String part : parts) {
            List<String> segments = countTokens(part) > max ? splitWithOverlap(part, max, overlap) : List.of(part);
            for (String segment : segments) {
                add(drafts, ChunkLevel.SINGLE, null, segment, null, null, "CUSTOM");
            }
        }
        return drafts;
    }

    private List<ChunkDraft> pack(List<Piece> pieces, int maxTokens, int overlapTokens, String strategy) {
        List<ChunkDraft> drafts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int tokens = 0;
        Piece current = null;
        for (Piece piece : pieces) {
            if (piece.text().isBlank()) {
                continue;
            }
            if (current != null && !Objects.equals(current.groupKey(), piece.groupKey()) && !buffer.isEmpty()) {
                flush(drafts, buffer.toString(), current, strategy);
                buffer.setLength(0);
                tokens = 0;
            }
            if (tokens > 0 && tokens + piece.tokens() > maxTokens) {
                flush(drafts, buffer.toString(), current, strategy);
                String tail = overlapTokens > 0 ? tailByTokens(buffer.toString(), overlapTokens) : "";
                buffer.setLength(0);
                tokens = 0;
                if (!tail.isBlank() && countTokens(tail) + piece.tokens() <= maxTokens) {
                    buffer.append(tail).append('\n');
                    tokens = countTokens(tail);
                }
            }
            if (!buffer.isEmpty()) {
                buffer.append(piece.kind().equals("TABLE") ? "\n" : " ");
            }
            buffer.append(piece.text());
            tokens += piece.tokens();
            current = piece;
        }
        if (!buffer.isEmpty()) {
            flush(drafts, buffer.toString(), current, strategy);
        }
        return drafts;
    }

    private void flush(List<ChunkDraft> drafts, String content, Piece meta, String strategy) {
        if (content == null || content.isBlank()) {
            return;
        }
        add(drafts, ChunkLevel.SINGLE, null, content, meta == null ? null : meta.pageNo(),
                meta == null ? null : meta.titlePath(), strategy);
    }

    private List<ChunkDraft> mergeSmall(List<ChunkDraft> drafts, int maxTokens, int minTokens, String strategy) {
        if (minTokens <= 0 || drafts.size() <= 1) {
            return drafts;
        }
        List<ChunkDraft> merged = new ArrayList<>();
        for (ChunkDraft draft : drafts) {
            if (!merged.isEmpty()) {
                ChunkDraft previous = merged.get(merged.size() - 1);
                if (draft.tokenCount() < minTokens
                        && previous.tokenCount() + draft.tokenCount() <= maxTokens
                        && Objects.equals(previous.pageNo(), draft.pageNo())
                        && Objects.equals(previous.titlePath(), draft.titlePath())) {
                    String content = previous.content() + "\n" + draft.content();
                    merged.set(merged.size() - 1, new ChunkDraft(previous.seq(), previous.level(),
                            previous.parentIndex(), content, countTokens(content), previous.charStart(),
                            draft.charEnd(), previous.pageNo(), previous.titlePath(),
                            meta(strategy, Map.of("strategy", strategy, "merged", true))));
                    continue;
                }
            }
            merged.add(draft);
        }
        return merged;
    }

    private List<Piece> pieces(List<TextBlock> blocks, int maxTokens) {
        List<Piece> pieces = new ArrayList<>();
        for (TextBlock block : blocks) {
            String groupKey = (block.pageNo() == null ? "-" : block.pageNo()) + "|"
                    + (block.titlePath() == null ? "-" : block.titlePath());
            for (String line : block.text().split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                for (String sentence : splitSentences(trimmed)) {
                    List<String> parts = countTokens(sentence) > maxTokens
                            ? hardSplit(sentence, maxTokens) : List.of(sentence);
                    for (String part : parts) {
                        pieces.add(new Piece(part, countTokens(part), block.pageNo(), block.titlePath(),
                                block.kind(), groupKey));
                    }
                }
            }
        }
        return pieces;
    }

    private List<String> splitSentences(String line) {
        List<String> sentences = new ArrayList<>();
        for (String candidate : line.split("(?<=[。！？!?;；])")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences.isEmpty() ? List.of(line) : sentences;
    }

    private List<String> hardSplit(String text, int maxTokens) {
        int limit = maxTokens * 4;
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int weight = 0;
            while (end < text.length() && weight < limit) {
                int codePoint = text.codePointAt(end);
                end += Character.charCount(codePoint);
                weight += charWeight(codePoint);
            }
            if (end <= start) {
                break;
            }
            parts.add(text.substring(start, end).trim());
            start = end;
        }
        return parts;
    }

    private List<String> splitWithOverlap(String text, int maxTokens, int overlapTokens) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (countTokens(text) <= maxTokens) {
            return List.of(text.trim());
        }
        List<String> parts = new ArrayList<>();
        int limit = maxTokens * 4;
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int weight = 0;
            while (end < text.length() && weight < limit) {
                int codePoint = text.codePointAt(end);
                end += Character.charCount(codePoint);
                weight += charWeight(codePoint);
            }
            if (end <= start) {
                break;
            }
            String content = text.substring(start, end).trim();
            if (!content.isEmpty()) {
                parts.add(content);
            }
            if (end >= text.length()) {
                break;
            }
            int nextStart = end;
            if (overlapTokens > 0) {
                String tail = tailByTokens(text.substring(start, end), overlapTokens);
                nextStart = Math.max(start + 1, end - tail.length());
            }
            start = nextStart;
        }
        return parts;
    }

    private String tailByTokens(String text, int tokens) {
        if (tokens <= 0 || text == null || text.isEmpty()) {
            return "";
        }
        int wanted = tokens * 4;
        int weight = 0;
        int index = text.length();
        while (index > 0 && weight < wanted) {
            int codePoint = text.codePointBefore(index);
            index -= Character.charCount(codePoint);
            weight += charWeight(codePoint);
        }
        return text.substring(index).trim();
    }

    private void add(List<ChunkDraft> drafts, ChunkLevel level, Integer parentIndex, String content,
                     Integer pageNo, String titlePath, String strategy) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        drafts.add(new ChunkDraft(drafts.size(), level, parentIndex, trimmed, countTokens(trimmed),
                null, null, pageNo, titlePath, meta(strategy, Map.of("strategy", strategy))));
    }

    private String buildTitlePrefix(String documentName, String titlePath, String template) {
        String path = titlePath == null ? "" : titlePath;
        String[] parts = path.split(" > ", -1);
        String result = template == null || template.isBlank() ? path : template;
        result = result.replace("{doc}", documentName == null ? "" : documentName);
        result = result.replace("{h1}", parts.length > 0 ? parts[0] : "");
        result = result.replace("{h2}", parts.length > 1 ? parts[1] : "");
        result = result.replace("{h3}", parts.length > 2 ? parts[2] : "");
        result = result.replace("{h4}", parts.length > 3 ? parts[3] : "");
        List<String> pieces = new ArrayList<>();
        for (String piece : result.split(" > ")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                pieces.add(trimmed);
            }
        }
        return String.join(" > ", pieces);
    }

    private String meta(String strategy, Map<String, Object> extra) {
        Map<String, Object> map = new LinkedHashMap<>(extra);
        map.put("strategy", strategy);
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            return "{\"strategy\":\"" + strategy + "\"}";
        }
    }

    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    private int charWeight(int codePoint) {
        if (Character.isWhitespace(codePoint)) {
            return 0;
        }
        return isCjk(codePoint) ? 4 : 1;
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(codePoint);
        } catch (Exception ex) {
            return false;
        }
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
