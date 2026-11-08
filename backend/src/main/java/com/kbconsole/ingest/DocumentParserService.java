package com.kbconsole.ingest;

import com.kbconsole.common.ApiException;
import com.kbconsole.common.ErrorCodes;
import com.kbconsole.config.dto.ConfigModels.ParseConfig;
import com.kbconsole.config.dto.ConfigModels.TypeParseConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class DocumentParserService {

    public ParseResult parse(Path file, String ext, ParseConfig config) {
        String type = normalizeType(ext);
        TypeParseConfig typeConfig = config.getPerType() == null ? null : config.getPerType().get(type);
        if (typeConfig != null && !typeConfig.isEnabled()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.UNSUPPORTED_TYPE,
                    "当前配置未启用 " + type + " 文档的解析");
        }
        String strategy = typeConfig == null || typeConfig.getStrategy() == null
                ? "NATIVE" : typeConfig.getStrategy().toUpperCase(Locale.ROOT);
        if ("OCR".equals(strategy)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.OCR_UNAVAILABLE,
                    "当前部署未接入 OCR 引擎，请将解析策略改为文字提取");
        }
        if (!Files.exists(file)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.PARSE_FAILED, "原始文件不存在，无法解析");
        }
        ParseResult result;
        try {
            result = switch (type) {
                case "PDF" -> parsePdf(file, config);
                case "DOCX" -> parseDocx(file);
                case "XLSX" -> parseExcel(file);
                case "MD" -> parseMarkdown(file);
                default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        ErrorCodes.UNSUPPORTED_TYPE, "不支持的文档类型: " + ext);
            };
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Parse failed for {} ({})", file, type, ex);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.PARSE_FAILED,
                    "文档解析失败: " + shorten(ex.getMessage(), 200));
        }
        if (result.blocks().isEmpty() || result.charCount() < 10) {
            String hint = "PDF".equals(type) ? "，文件可能是扫描件；当前部署未接入 OCR 引擎" : "";
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.NO_TEXT,
                    "未提取到可用文本" + hint);
        }
        log.info("Parsed {} {}: blocks={}, chars={}", type, file.getFileName(), result.blocks().size(), result.charCount());
        return result;
    }

    private ParseResult parsePdf(Path file, ParseConfig config) throws IOException {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.EMPTY_DOCUMENT, "PDF 不包含任何页面");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<List<String>> pageLines = new ArrayList<>();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String raw = stripper.getText(document);
                List<String> lines = new ArrayList<>();
                for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        lines.add(trimmed);
                    }
                }
                pageLines.add(lines);
            }
            boolean filterHeaderFooter = config.getOptions() == null || config.getOptions().isFilterHeaderFooter();
            List<List<String>> effective = filterHeaderFooter ? filterRepeatedLines(pageLines) : pageLines;
            List<TextBlock> blocks = new ArrayList<>();
            for (int i = 0; i < effective.size(); i++) {
                if (!effective.get(i).isEmpty()) {
                    blocks.add(new TextBlock(String.join("\n", effective.get(i)), i + 1, null, "PAGE"));
                }
            }
            if (blocks.isEmpty()) {
                for (int i = 0; i < pageLines.size(); i++) {
                    if (!pageLines.get(i).isEmpty()) {
                        blocks.add(new TextBlock(String.join("\n", pageLines.get(i)), i + 1, null, "PAGE"));
                    }
                }
            }
            return resultOf(blocks, pages);
        }
    }

    private List<List<String>> filterRepeatedLines(List<List<String>> pages) {
        if (pages.size() < 3) {
            return pages;
        }
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (List<String> page : pages) {
            Set<String> unique = new HashSet<>(page);
            for (String line : unique) {
                frequency.merge(normalizeLine(line), 1, Integer::sum);
            }
        }
        int threshold = Math.max(2, (int) Math.ceil(pages.size() * 0.5));
        List<List<String>> result = new ArrayList<>();
        for (List<String> page : pages) {
            List<String> kept = new ArrayList<>();
            for (String line : page) {
                String normalized = normalizeLine(line);
                if (normalized.length() <= 120 && frequency.getOrDefault(normalized, 0) >= threshold) {
                    continue;
                }
                if (normalized.matches("^[-—\\s]*\\d{1,4}[-—\\s]*$")) {
                    continue;
                }
                kept.add(line);
            }
            result.add(kept);
        }
        return result;
    }

    private String normalizeLine(String line) {
        return line.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private ParseResult parseDocx(Path file) throws IOException {
        List<TextBlock> blocks = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file); XWPFDocument document = new XWPFDocument(in)) {
            String[] headings = new String[6];
            StringBuilder buffer = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText() == null ? "" : paragraph.getText().trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    int level = headingLevel(paragraph);
                    if (level > 0) {
                        flush(blocks, buffer, headings);
                        updateHeadings(headings, level, text);
                        blocks.add(new TextBlock(text, null, joinHeadings(headings), "HEADING"));
                    } else {
                        if (!buffer.isEmpty()) {
                            buffer.append('\n');
                        }
                        buffer.append(text);
                    }
                } else if (element instanceof XWPFTable table) {
                    flush(blocks, buffer, headings);
                    String tableText = renderTable(table);
                    if (!tableText.isBlank()) {
                        blocks.add(new TextBlock(tableText, null, joinHeadings(headings), "TABLE"));
                    }
                }
            }
            flush(blocks, buffer, headings);
        }
        return resultOf(blocks, null);
    }

    private String renderTable(XWPFTable table) {
        StringBuilder text = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText() == null ? "" : cell.getText().replaceAll("\\s+", " ").trim());
            }
            String line = String.join(" | ", cells).trim();
            if (!line.isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(line);
            }
        }
        return text.toString();
    }

    private int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style != null) {
            String normalized = style.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
            if (normalized.startsWith("heading")) {
                String digits = normalized.replaceAll("\\D+", "");
                if (!digits.isEmpty()) {
                    int level = Integer.parseInt(digits);
                    if (level >= 1 && level <= 6) {
                        return level;
                    }
                }
            }
            if (normalized.matches("[1-6]")) {
                return Integer.parseInt(normalized);
            }
        }
        try {
            if (paragraph.getCTP().getPPr() != null && paragraph.getCTP().getPPr().getOutlineLvl() != null) {
                int level = paragraph.getCTP().getPPr().getOutlineLvl().getVal().intValue() + 1;
                if (level >= 1 && level <= 6) {
                    return level;
                }
            }
        } catch (Exception ignored) {
            // outline level is optional
        }
        return 0;
    }

    private void flush(List<TextBlock> blocks, StringBuilder buffer, String[] headings) {
        String text = buffer.toString().trim();
        if (!text.isEmpty()) {
            blocks.add(new TextBlock(text, null, joinHeadings(headings), "PARAGRAPH"));
            buffer.setLength(0);
        }
    }

    private ParseResult parseExcel(Path file) throws IOException {
        List<TextBlock> blocks = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (InputStream in = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(in)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                StringBuilder text = new StringBuilder();
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    short last = row.getLastCellNum();
                    for (int c = 0; c < last; c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                    }
                    String line = String.join(" | ", cells).trim();
                    if (!line.isBlank()) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(line);
                    }
                }
                if (!text.isEmpty()) {
                    blocks.add(new TextBlock(text.toString(), s + 1, sheet.getSheetName(), "TABLE"));
                }
            }
            return resultOf(blocks, workbook.getNumberOfSheets());
        }
    }

    private ParseResult parseMarkdown(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        Parser parser = Parser.builder().build();
        Node document = parser.parse(content);
        TextContentRenderer renderer = TextContentRenderer.builder().build();
        List<TextBlock> blocks = new ArrayList<>();
        String[] headings = new String[6];
        Node node = document.getFirstChild();
        while (node != null) {
            Node next = node.getNext();
            if (node instanceof Heading heading) {
                String text = renderer.render(heading).trim();
                if (!text.isEmpty()) {
                    updateHeadings(headings, heading.getLevel(), text);
                    blocks.add(new TextBlock(text, null, joinHeadings(headings), "HEADING"));
                }
            } else if (node instanceof Paragraph || node instanceof FencedCodeBlock
                    || node instanceof IndentedCodeBlock || node instanceof BlockQuote
                    || node instanceof BulletList || node instanceof OrderedList) {
                String text = renderer.render(node).trim();
                if (!text.isEmpty()) {
                    String kind = node instanceof BulletList || node instanceof OrderedList || node instanceof ListItem
                            ? "LIST" : "PARAGRAPH";
                    blocks.add(new TextBlock(text, null, joinHeadings(headings), kind));
                }
            }
            node = next;
        }
        return resultOf(blocks, null);
    }

    private void updateHeadings(String[] headings, int level, String text) {
        int safeLevel = Math.max(1, Math.min(level, headings.length));
        headings[safeLevel - 1] = text;
        for (int i = safeLevel; i < headings.length; i++) {
            headings[i] = null;
        }
    }

    private String joinHeadings(String[] headings) {
        StringBuilder path = new StringBuilder();
        for (String heading : headings) {
            if (heading == null) {
                continue;
            }
            if (!path.isEmpty()) {
                path.append(" > ");
            }
            path.append(heading);
        }
        String value = path.toString();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private ParseResult resultOf(List<TextBlock> blocks, Integer pageCount) {
        long chars = blocks.stream().mapToLong(b -> b.text().length()).sum();
        String text = blocks.stream().map(TextBlock::text).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n\n" + b);
        List<TextBlock> cleaned = blocks.stream()
                .filter(b -> b.text() != null && !b.text().isBlank())
                .toList();
        return new ParseResult(text, pageCount, chars, cleaned);
    }

    private String normalizeType(String ext) {
        String value = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "pdf" -> "PDF";
            case "docx" -> "DOCX";
            case "xlsx", "xls" -> "XLSX";
            case "md", "markdown" -> "MD";
            default -> value.toUpperCase(Locale.ROOT);
        };
    }

    private String shorten(String value, int max) {
        if (value == null) {
            return "未知错误";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
