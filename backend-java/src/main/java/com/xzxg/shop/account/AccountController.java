package com.xzxg.shop.account;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xzxg.shop.common.api.ApiException;
import com.xzxg.shop.common.web.RequestContext;
import com.xzxg.shop.domain.Account;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AccountController {

    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;

    private final AccountService accountService;
    private final AvatarStorage avatarStorage;

    public AccountController(AccountService accountService, AvatarStorage avatarStorage) {
        this.accountService = accountService;
        this.avatarStorage = avatarStorage;
    }

    public record ProfileUpdateRequest(
            @JsonProperty("display_name") String displayName,
            String nickname,
            @JsonProperty("avatar_url") String avatarUrl) {
    }

    public record ContactRequest(String phone, String email) {
    }

    @GetMapping("/api/v1/account/profile")
    public Account profile(HttpServletRequest request) {
        return requireAccount(request);
    }

    @PatchMapping("/api/v1/account/profile")
    public Account updateProfile(@RequestBody(required = false) ProfileUpdateRequest body,
                                 HttpServletRequest request) {
        if (body == null) {
            throw badRequestBody();
        }
        return accountService.updateProfile(requireAccount(request),
                body.displayName(), body.nickname(), body.avatarUrl());
    }

    @PatchMapping("/api/v1/account/contact")
    public Account updateContact(@RequestBody(required = false) ContactRequest body,
                                 HttpServletRequest request) {
        if (body == null) {
            throw badRequestBody();
        }
        return accountService.updateContact(requireAccount(request), body.phone(), body.email());
    }

    @DeleteMapping("/api/v1/account")
    public Map<String, Boolean> delete(HttpServletRequest request) {
        accountService.delete(requireAccount(request));
        return Map.of("ok", true);
    }

    @PostMapping("/api/v1/uploads/avatar")
    public Map<String, Object> uploadAvatar(
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) throws IOException {
        requireAccount(request);
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing_file", "请选择头像文件");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file_too_large", "头像不能超过 2MB");
        }
        byte[] content = file.getBytes();
        String mimeType = detectImageMime(content);
        String extension = switch (mimeType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        String filename = avatarStorage.store(requireAccount(request).accountId(), content, extension);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("url", "/api/v1/uploads/avatar/" + filename);
        response.put("mime_type", mimeType);
        response.put("size", content.length);
        return response;
    }

    @GetMapping("/api/v1/uploads/avatar/{filename:.+}")
    public ResponseEntity<Resource> avatar(@PathVariable String filename) {
        Path path = avatarStorage.resolve(filename);
        if (path == null || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        String mimeType = mimeTypeFor(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .body(new FileSystemResource(path));
    }

    private static Account requireAccount(HttpServletRequest request) {
        Account account = RequestContext.account(request);
        if (account == null) {
            throw ApiException.unauthorized();
        }
        return account;
    }

    private static ApiException badRequestBody() {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "请求 JSON 不合法");
    }

    private static String detectImageMime(byte[] content) {
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (content.length >= 8
                && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N'
                && content[3] == 'G' && (content[4] & 0xFF) == 0x0D && (content[5] & 0xFF) == 0x0A
                && (content[6] & 0xFF) == 0x1A && (content[7] & 0xFF) == 0x0A) {
            return "image/png";
        }
        if (content.length >= 12
                && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return "image/webp";
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "bad_image_type",
                "头像仅支持 JPG、PNG 或 WebP");
    }

    private static String mimeTypeFor(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
