package com.xzxg.shop.files;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.domain.StoredFile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StoredFileRepository {

    private final JdbcTemplate jdbc;

    public StoredFileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StoredFile create(String fileId, String accountId, String objectKey, String url,
                             String mimeType, long sizeBytes, String contentHash,
                             String sourceUrl) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO stored_files (file_id, account_id, object_key, url, mime_type,
                                          size_bytes, content_hash, storage_provider, source_url,
                                          created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'minio', ?, ?)
                """, fileId, accountId, objectKey, url, mimeType, sizeBytes, contentHash,
                sourceUrl == null ? "" : sourceUrl, Timestamp.valueOf(now));
        return new StoredFile(fileId, accountId, objectKey, url, mimeType, sizeBytes, contentHash,
                "minio", sourceUrl == null ? "" : sourceUrl, GoTime.format(now));
    }

    public Optional<StoredFile> find(String fileId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT file_id, account_id, object_key, url, mime_type, size_bytes,
                           content_hash, storage_provider, source_url, created_at
                    FROM stored_files
                    WHERE file_id = ?
                    """, (rs, rowNum) -> map(rs), fileId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private static StoredFile map(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new StoredFile(
                rs.getString("file_id"),
                nullToEmpty(rs.getString("account_id")),
                rs.getString("object_key"),
                rs.getString("url"),
                rs.getString("mime_type"),
                rs.getLong("size_bytes"),
                rs.getString("content_hash"),
                rs.getString("storage_provider"),
                nullToEmpty(rs.getString("source_url")),
                GoTime.format(createdAt == null ? null : createdAt.toLocalDateTime()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
