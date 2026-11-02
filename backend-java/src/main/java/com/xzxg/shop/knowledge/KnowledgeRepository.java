package com.xzxg.shop.knowledge;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.common.Ids;
import com.xzxg.shop.common.JsonCodec;
import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.common.QueryTerms;
import com.xzxg.shop.domain.KnowledgeDocument;
import com.xzxg.shop.domain.KnowledgeDocumentInput;
import com.xzxg.shop.rag.DocumentSplitter;
import com.xzxg.shop.retrieval.RetrievalRanker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class KnowledgeRepository {

    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final TransactionTemplate transactions;

    public KnowledgeRepository(JdbcTemplate jdbc, JsonCodec json, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = transactions;
    }

    public record CreateResult(KnowledgeDocument document, boolean duplicate) {
    }

    public CreateResult create(KnowledgeDocumentInput raw) {
        String title = trim(raw.title());
        String content = trim(raw.content());
        String docType = trim(raw.docType());
        String sourceUrl = trim(raw.sourceUrl());
        String contentHash = trim(raw.contentHash());
        if (contentHash.isEmpty()) {
            contentHash = sha256Hex(content);
        }
        if (!contentHash.isEmpty() && !raw.forceReindex()) {
            Optional<KnowledgeDocument> existing = findByHash(trim(raw.merchantId()), contentHash);
            if (existing.isPresent()) {
                return new CreateResult(existing.get(), true);
            }
        }
        List<DocumentSplitter.ChunkDraft> chunks =
                DocumentSplitter.split(title, content, docType);
        if (chunks.isEmpty()) {
            chunks = List.of(new DocumentSplitter.ChunkDraft(title, content,
                    DocumentSplitter.snippet(content, DocumentSplitter.SNIPPET_RUNES), 10));
        }
        String metadataJson = "{}";
        if (raw.metadata() != null && !raw.metadata().isEmpty()) {
            metadataJson = json.write(raw.metadata());
        }
        LocalDateTime now = LocalDateTime.now();
        String documentId = Ids.next("doc");
        int chunkCount = chunks.size();
        List<DocumentSplitter.ChunkDraft> finalChunks = chunks;
        String finalMetadataJson = metadataJson;
        String finalContentHash = contentHash;
        Boolean committed = transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO knowledge_documents (
                        document_id, merchant_id, title, doc_type, content, status, chunk_count,
                        source_url, content_hash, metadata_json, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'indexed', ?, ?, ?, ?, ?, ?)
                    """, documentId, trim(raw.merchantId()), title, docType, content, chunkCount,
                    sourceUrl, finalContentHash, finalMetadataJson, Timestamp.valueOf(now),
                    Timestamp.valueOf(now));
            for (DocumentSplitter.ChunkDraft chunk : finalChunks) {
                jdbc.update("""
                        INSERT INTO knowledge_chunks (chunk_id, title, snippet, source, sort_order,
                                                      created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, Ids.next("ck"), chunk.title(), chunk.snippet(), documentId,
                        chunk.sortOrder(), Timestamp.valueOf(now), Timestamp.valueOf(now));
            }
            return true;
        });
        if (!Boolean.TRUE.equals(committed)) {
            throw new IllegalStateException("create knowledge document failed");
        }
        KnowledgeDocument document = new KnowledgeDocument(documentId, trim(raw.merchantId()),
                title, docType, "indexed", chunkCount, sourceUrl, contentHash, GoTime.format(now));
        return new CreateResult(document, false);
    }

    public Optional<KnowledgeDocument> findByHash(String merchantId, String hash) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT document_id, merchant_id, title, doc_type, status, chunk_count,
                           source_url, content_hash, created_at
                    FROM knowledge_documents
                    WHERE merchant_id = ? AND content_hash = ? AND content_hash <> ''
                    ORDER BY created_at DESC, document_id DESC
                    LIMIT 1
                    """, (rs, rowNum) -> mapDocument(rs), merchantId, hash));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public PageResponse<KnowledgeDocument> listDocuments(String merchantId, PageRequest page,
                                                         boolean all) {
        String predicate = all ? "" : " WHERE merchant_id = ?";
        Integer total = all
                ? jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents", Integer.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE merchant_id = ?",
                        Integer.class, merchantId);
        List<Object> args = new ArrayList<>();
        if (!all) {
            args.add(merchantId);
        }
        args.add(page.pageSize());
        args.add(page.offset());
        List<KnowledgeDocument> items = jdbc.query("""
                SELECT document_id, merchant_id, title, doc_type, status, chunk_count,
                       source_url, content_hash, created_at
                FROM knowledge_documents
                """ + predicate + " ORDER BY created_at DESC, document_id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapDocument(rs), args.toArray());
        return new PageResponse<>(items, page.page(), page.pageSize(), total == null ? 0 : total);
    }

    public List<RetrievalRanker.Candidate> searchKeyword(String query, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT chunk_id, title, snippet, source
                FROM knowledge_chunks
                """);
        String trimmedQuery = trim(query);
        if (!trimmedQuery.isEmpty()) {
            List<String> clauses = new ArrayList<>();
            clauses.add("title LIKE ?");
            clauses.add("snippet LIKE ?");
            String like = "%" + trimmedQuery + "%";
            args.add(like);
            args.add(like);
            for (String term : QueryTerms.of(trimmedQuery)) {
                clauses.add("title LIKE ?");
                clauses.add("snippet LIKE ?");
                String termLike = "%" + term + "%";
                args.add(termLike);
                args.add(termLike);
                if (clauses.size() >= 14) {
                    break;
                }
            }
            sql.append(" WHERE ").append(String.join(" OR ", clauses));
        }
        sql.append(" ORDER BY sort_order, chunk_id LIMIT ?");
        args.add(limit <= 0 ? 200 : limit);
        List<RetrievalRanker.Candidate> candidates = new ArrayList<>();
        jdbc.query(sql.toString(), rs -> {
            candidates.add(new RetrievalRanker.Candidate(
                    rs.getString("chunk_id"), rs.getString("title"), rs.getString("snippet"),
                    nullToEmpty(rs.getString("source")), 0));
        }, args.toArray());
        return candidates;
    }

    public List<RetrievalRanker.Candidate> chunksByIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(chunkIds.size(), "?"));
        return jdbc.query("""
                SELECT chunk_id, title, snippet, source
                FROM knowledge_chunks
                WHERE chunk_id IN (""" + placeholders + ") ORDER BY sort_order, chunk_id",
                (rs, rowNum) -> new RetrievalRanker.Candidate(
                        rs.getString("chunk_id"), rs.getString("title"), rs.getString("snippet"),
                        nullToEmpty(rs.getString("source")), 0),
                chunkIds.toArray());
    }

    private static KnowledgeDocument mapDocument(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new KnowledgeDocument(
                rs.getString("document_id"),
                rs.getString("merchant_id"),
                rs.getString("title"),
                rs.getString("doc_type"),
                rs.getString("status"),
                rs.getInt("chunk_count"),
                nullToEmpty(rs.getString("source_url")),
                nullToEmpty(rs.getString("content_hash")),
                GoTime.format(createdAt == null ? null : createdAt.toLocalDateTime()));
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
