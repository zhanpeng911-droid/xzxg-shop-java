package com.xzxg.shop.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Port of {@code rag.QueryTerms}: splits on non-word characters, expands Han text
 * into 2-4 character n-grams, lowercases, and keeps terms of at least two runes.
 */
public final class QueryTerms {

    private QueryTerms() {
    }

    public static List<String> of(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null || query.isEmpty()) {
            return new ArrayList<>();
        }
        StringBuilder current = new StringBuilder();
        boolean currentIsHan = false;
        for (int i = 0; i < query.length(); ) {
            int codePoint = query.codePointAt(i);
            boolean isHan = isHan(codePoint);
            boolean isWord = isHan || Character.isLetter(codePoint) || Character.isDigit(codePoint);
            if (!isWord) {
                flush(current, currentIsHan, terms);
                current.setLength(0);
                currentIsHan = false;
            } else {
                if (current.length() > 0 && isHan != currentIsHan) {
                    flush(current, currentIsHan, terms);
                    current.setLength(0);
                }
                currentIsHan = isHan;
                current.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        flush(current, currentIsHan, terms);
        return new ArrayList<>(terms);
    }

    private static void flush(StringBuilder current, boolean isHan, Set<String> terms) {
        if (current.length() == 0) {
            return;
        }
        String text = current.toString();
        if (isHan) {
            addHanNgrams(text, terms);
        } else {
            add(terms, text);
        }
    }

    private static void addHanNgrams(String text, Set<String> terms) {
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length <= 4) {
            add(terms, text);
        }
        for (int size = 2; size <= 4; size++) {
            for (int i = 0; i + size <= codePoints.length; i++) {
                add(terms, new String(codePoints, i, size));
            }
        }
    }

    private static void add(Set<String> terms, String term) {
        String normalized = term.trim().toLowerCase(Locale.ROOT);
        if (normalized.codePointCount(0, normalized.length()) >= 2) {
            terms.add(normalized);
        }
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    /** Utility for tests and callers that need the Go uniqueTerms behaviour. */
    public static List<String> unique(List<String> input, int limit) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String term : input) {
            String normalized = term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || !seen.add(normalized)) {
                continue;
            }
            out.add(normalized);
            if (out.size() == limit) {
                break;
            }
        }
        return out;
    }
}
