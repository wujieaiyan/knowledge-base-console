package com.kbconsole.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmbeddingService {
    private static final int DIMENSION = 256;

    private final ObjectMapper objectMapper;

    public double[] embed(String text) {
        double[] vector = new double[DIMENSION];
        Map<String, Integer> counts = new HashMap<>();
        for (String token : tokens(text)) {
            counts.merge(token, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String token = entry.getKey();
            int hash = token.hashCode();
            int index = Math.floorMod(hash, DIMENSION);
            double sign = (hash & 0x40000000) == 0 ? 1.0 : -1.0;
            vector[index] += sign * (1.0 + Math.log(entry.getValue()));
        }
        normalize(vector);
        return vector;
    }

    public String toJson(double[] vector) {
        StringBuilder json = new StringBuilder(vector.length * 8);
        json.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(String.format(Locale.ROOT, "%.5f", vector[i]));
        }
        json.append(']');
        return json.toString();
    }

    public double[] fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new double[DIMENSION];
        }
        try {
            double[] vector = objectMapper.readValue(json, double[].class);
            return vector.length == DIMENSION ? vector : new double[DIMENSION];
        } catch (Exception ex) {
            return new double[DIMENSION];
        }
    }

    public double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return Math.max(0, Math.min(1, dot));
    }

    public List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder latin = new StringBuilder();
        String previousCjk = null;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
                    flushLatin(latin, tokens);
                    String current = new String(Character.toChars(codePoint));
                    tokens.add(current);
                    if (previousCjk != null) {
                        tokens.add(previousCjk + current);
                    }
                    previousCjk = current;
                } else {
                    previousCjk = null;
                    latin.appendCodePoint(Character.toLowerCase(codePoint));
                }
            } else {
                previousCjk = null;
                flushLatin(latin, tokens);
            }
        }
        flushLatin(latin, tokens);
        return tokens;
    }

    private void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() > 0) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    private void normalize(double[] vector) {
        double sum = 0;
        for (double value : vector) {
            sum += value * value;
        }
        if (sum == 0) {
            return;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }
}
