package com.kbconsole.ingest;

import java.util.List;

public record ParseResult(String text, Integer pageCount, long charCount, List<TextBlock> blocks) {
}
