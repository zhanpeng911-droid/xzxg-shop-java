package com.xzxg.shop.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of the Go document splitter. All length checks are rune (code point) based.
 */
public final class DocumentSplitter {

    public static final int CHUNK_MAX_RUNES = 800;
    public static final int SNIPPET_RUNES = 160;

    private DocumentSplitter() {
    }

    public record ChunkDraft(String title, String content, String snippet, int sortOrder) {
    }

    public static List<ChunkDraft> split(String title, String content, String docType) {
        String cleaned = cleanText(content);
        if (cleaned.isEmpty()) {
            return List.of();
        }
        if ("faq".equals(docType)) {
            List<ChunkDraft> chunks = splitFaq(title, cleaned);
            if (!chunks.isEmpty()) {
                return chunks;
            }
        }
        List<ChunkDraft> chunks = new ArrayList<>();
        for (String block : splitBlocks(cleaned)) {
            for (String chunk : recursiveSplit(block, CHUNK_MAX_RUNES)) {
                chunks.add(makeChunkDraft(title, chunk, chunks.size()));
            }
        }
        if (chunks.isEmpty()) {
            chunks.add(makeChunkDraft(title, cleaned, 0));
        }
        return chunks;
    }

    public static String snippet(String text, int maxRunes) {
        String trimmed = text == null ? "" : text.trim();
        int[] codePoints = trimmed.codePoints().toArray();
        if (codePoints.length <= maxRunes) {
            return trimmed;
        }
        return new String(codePoints, 0, maxRunes);
    }

    private static String cleanText(String input) {
        String text = input == null ? "" : input.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = text.split("\n", -1);
        List<String> cleaned = new ArrayList<>(lines.length);
        for (String line : lines) {
            cleaned.add(line.trim());
        }
        text = String.join("\n", cleaned);
        text = text.replaceAll("\n{3,}", "\n\n");
        return text.trim();
    }

    private static List<String> splitBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        for (String part : text.split("\n\n", -1)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                blocks.add(trimmed);
            }
        }
        return blocks;
    }

    private static List<String> recursiveSplit(String text, int maxRunes) {
        if (runeLength(text) <= maxRunes) {
            return List.of(text);
        }
        for (String separator : List.of("。", "？", "！", "；", "\n", "，", "、")) {
            String[] parts = text.split(separator, -1);
            if (parts.length <= 1) {
                continue;
            }
            List<String> merged = mergeParts(parts, separator, maxRunes);
            List<String> result = new ArrayList<>();
            for (String item : merged) {
                if (runeLength(item) > maxRunes) {
                    result.addAll(fixedSplit(item, maxRunes));
                } else {
                    result.add(item);
                }
            }
            return result;
        }
        return fixedSplit(text, maxRunes);
    }

    private static List<String> mergeParts(String[] parts, String separator, int maxRunes) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawPart : parts) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (!"\n".equals(separator)) {
                part = part + separator;
            }
            String next = current + part;
            if (current.isEmpty() || runeLength(next) <= maxRunes) {
                current.append(part);
                continue;
            }
            result.add(current.toString().trim());
            current = new StringBuilder(overlapTail(current.toString())).append(part);
        }
        if (!current.toString().trim().isEmpty()) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private static List<String> fixedSplit(String text, int maxRunes) {
        int[] runes = text.codePoints().toArray();
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < runes.length) {
            int end = Math.min(start + maxRunes, runes.length);
            result.add(new String(runes, start, end - start).trim());
            if (end == runes.length) {
                break;
            }
            start = end - 100;
            if (start < 0) {
                start = end;
            }
        }
        return result;
    }

    private static String overlapTail(String text) {
        int[] runes = text.trim().codePoints().toArray();
        if (runes.length <= 100) {
            return new String(runes, 0, runes.length);
        }
        return new String(runes, runes.length - 100, 100) + "\n";
    }

    private static List<ChunkDraft> splitFaq(String title, String content) {
        List<ChunkDraft> chunks = new ArrayList<>();
        StringBuilder question = new StringBuilder();
        List<String> answer = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Q:") || trimmed.startsWith("问：") || trimmed.startsWith("问题：")) {
                flushFaq(title, question, answer, chunks);
                question = new StringBuilder(trimmed);
                continue;
            }
            if (trimmed.startsWith("A:") || trimmed.startsWith("答：") || trimmed.startsWith("答案：")) {
                answer.add(trimmed);
                continue;
            }
            if (!question.isEmpty() && !trimmed.isEmpty()) {
                answer.add(trimmed);
            }
        }
        flushFaq(title, question, answer, chunks);
        return chunks;
    }

    private static void flushFaq(String title, StringBuilder question, List<String> answer,
                                 List<ChunkDraft> chunks) {
        if (question.isEmpty() || answer.isEmpty()) {
            return;
        }
        String text = question + "\n" + String.join("\n", answer);
        for (String chunk : recursiveSplit(text, CHUNK_MAX_RUNES)) {
            chunks.add(makeChunkDraft(title + " / FAQ", chunk, chunks.size()));
        }
        answer.clear();
    }

    private static ChunkDraft makeChunkDraft(String title, String content, int index) {
        String trimmed = content.trim();
        return new ChunkDraft(title, trimmed, snippet(trimmed, SNIPPET_RUNES), (index + 1) * 10);
    }

    public static int runeLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
