package com.xzxg.shop.retrieval;

import com.xzxg.shop.common.QueryTerms;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Port of {@code rag.RankCandidates} with the default weights. Retrieval weights
 * from Nacos are not wired yet; see KNOWN_DIFFERENCES.md.
 */
public final class RetrievalRanker {

    public record Candidate(String chunkId, String title, String snippet, String source,
                            double score) {
        public Candidate withScore(double value) {
            return new Candidate(chunkId, title, snippet, source, value);
        }
    }

    private static final Map<String, Double> DEFAULT_WEIGHTS = defaultWeights();

    private RetrievalRanker() {
    }

    public static List<Candidate> rank(String query, List<Candidate> candidates, int topK) {
        List<String> terms = QueryTerms.of(query);
        List<Candidate> scored = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            scored.add(candidate.score() == 0 ? candidate.withScore(score(query, candidate, terms))
                    : candidate);
        }
        scored.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(Candidate::chunkId));
        int limit = topK <= 0 || topK > scored.size() ? scored.size() : topK;
        List<Candidate> result = new ArrayList<>(limit);
        for (Candidate candidate : scored) {
            if (result.size() >= limit) {
                break;
            }
            result.add(candidate);
        }
        return result;
    }

    public static double score(String query, Candidate candidate, List<String> terms) {
        String text = (candidate.title() + "\n" + candidate.snippet()).toLowerCase(Locale.ROOT);
        if (terms.isEmpty()) {
            return 0.1;
        }
        String title = candidate.title().toLowerCase(Locale.ROOT);
        double titleScore = 0;
        for (String term : terms) {
            if (title.contains(term)) {
                titleScore = 1;
                break;
            }
        }
        int matched = 0;
        for (String term : terms) {
            if (text.contains(term)) {
                matched++;
            }
        }
        double exactRatio = (double) matched / terms.size();
        double score = weight("title_match", 0.35) * titleScore
                + weight("term_match", 0.45) * exactRatio;
        // DefaultRetrievalPlan leaves the boost term lists empty; retrievalconfig
        // overrides are not wired yet, so no brand/model/category boosts here.
        return Math.max(0, Math.min(1, score));
    }

    public static List<String> modelLikeTerms(List<String> terms) {
        List<String> out = new ArrayList<>();
        for (String term : terms) {
            String normalized = term.toLowerCase(Locale.ROOT);
            boolean hasDigit = normalized.codePoints().anyMatch(Character::isDigit);
            boolean modelToken = List.of("iphone", "ipad", "macbook", "matepad", "matebook",
                            "thinkpad", "mix", "fold", "pro", "max", "airism", "ultraboost")
                    .stream().anyMatch(normalized::contains);
            if ((normalized.codePointCount(0, normalized.length()) >= 4 && hasDigit) || modelToken) {
                out.add(term);
            }
        }
        return out;
    }

    public static List<String> categoryLikeTerms(List<String> terms) {
        List<String> keywords = List.of("手机", "平板", "电脑", "笔记本", "面霜", "精华", "防晒",
                "洁面", "咖啡", "茶饮", "调味", "跑鞋", "t恤", "夹克", "碳酸", "饮料");
        List<String> out = new ArrayList<>();
        for (String term : terms) {
            String lower = term.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    out.add(term);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean anyTermMatches(String text, List<String> terms) {
        for (String term : terms) {
            String normalized = term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && text.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static double boolScore(boolean value) {
        return value ? 1 : 0;
    }

    private static double weight(String key, double fallback) {
        return DEFAULT_WEIGHTS.getOrDefault(key, fallback);
    }

    private static Map<String, Double> defaultWeights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("keyword_score", 0.55);
        weights.put("doc_type_score", 0.10);
        weights.put("product_score", 0.10);
        weights.put("category_score", 0.05);
        weights.put("source_score", 0.05);
        weights.put("freshness_score", 0.03);
        weights.put("quality_score", 0.05);
        weights.put("evidence_density", 0.07);
        weights.put("title_match", 0.35);
        weights.put("term_match", 0.45);
        weights.put("required_match", 0.20);
        weights.put("brand_boost", 0.35);
        weights.put("model_boost", 0.30);
        weights.put("category_boost", 0.15);
        weights.put("generic_penalty", 0.45);
        return weights;
    }
}
