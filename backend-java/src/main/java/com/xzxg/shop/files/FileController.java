package com.xzxg.shop.files;

import com.xzxg.shop.account.AccountRepository;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.config.ShopProperties;
import com.xzxg.shop.domain.Account;
import com.xzxg.shop.domain.StoredFile;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class FileController {

    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM");

    private final StoredFileRepository files;
    private final ObjectStorage storage;
    private final long maxUploadBytes;

    public FileController(StoredFileRepository files, ObjectStorage storage,
                          ShopProperties properties) {
        this.files = files;
        this.storage = storage;
        this.maxUploadBytes = properties.getFiles().getMaxUploadBytes();
    }

    @PostMapping("/api/v1/files")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) throws IOException {
        Account account = requireAccount(request);
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_file", "缺少 file 字段");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new ApiException(HttpStatus.valueOf(413), "file_too_large",
                    "文件超过上传大小限制");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "read_file_failed", "读取文件失败");
        }
        if (content.length > maxUploadBytes) {
            throw new ApiException(HttpStatus.valueOf(413), "file_too_large",
                    "文件超过上传大小限制");
        }
        String mimeType = FileTypes.detect(content);
        if (!FileTypes.isAccepted(mimeType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unsupported_file_type",
                    "当前只支持图片和 PDF 文件");
        }

        long startedAt = System.nanoTime();
        String fileId = "file_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String extension = FileTypes.extensionFor(file.getOriginalFilename(), mimeType);
        LocalDateTime now = LocalDateTime.now();
        String objectKey = String.join("/", "uploads", account.accountId(),
                YEAR.format(now), MONTH.format(now), fileId + extension);
        try {
            storage.put(objectKey, content, mimeType);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "object_store_unavailable",
                    "对象存储不可用");
        }
        StoredFile meta = files.create(fileId, account.accountId(), objectKey,
                "/api/v1/files/" + fileId, mimeType, content.length, sha256Hex(content), "");
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("file", meta);
        response.put("upload_duration_ms", durationMs);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/v1/files/{fileId}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String fileId,
                                                         HttpServletRequest request) {
        Account account = requireAccount(request);
        if (fileId.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_file_id", "文件 ID 不合法");
        }
        StoredFile meta = files.find(fileId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "file_not_found", "文件不存在"));
        if (meta.accountId() != null && !meta.accountId().isEmpty()
                && !meta.accountId().equals(account.accountId())
                && !"admin".equals(account.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "无权访问该文件");
        }
        InputStream stream;
        try {
            stream = storage.get(meta.objectKey());
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.NOT_FOUND, "object_not_found",
                    "对象存储文件不存在");
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(meta.mimeType()))
                .header("Cache-Control", "private, max-age=3600");
        if (meta.sizeBytes() > 0) {
            builder.contentLength(meta.sizeBytes());
        }
        return builder.body(new InputStreamResource(stream));
    }

    private static Account requireAccount(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        return account;
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
