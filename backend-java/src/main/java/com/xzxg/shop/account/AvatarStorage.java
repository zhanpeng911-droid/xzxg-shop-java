package com.xzxg.shop.account;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Avatar files live outside the database, matching the Go file server. The root is
 * configurable because the working directory differs between deployments.
 */
@Component
public class AvatarStorage {

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Path root;

    public AvatarStorage() {
        String configured = System.getenv("AVATAR_UPLOAD_ROOT");
        this.root = Paths.get(configured == null || configured.isBlank()
                        ? Paths.get(".", "uploads", "avatar").toString()
                        : configured)
                .toAbsolutePath()
                .normalize();
    }

    public Path root() {
        return root;
    }

    public String store(String accountId, byte[] content, String extension) throws IOException {
        Files.createDirectories(root);
        String filename = accountId + "_" + FILE_TIMESTAMP.format(LocalDateTime.now()) + extension;
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("avatar path escapes root");
        }
        Files.write(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return filename;
    }

    public Path resolve(String filename) {
        if (filename == null || filename.isBlank()
                || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            return null;
        }
        Path candidate = root.resolve(filename).normalize();
        return candidate.startsWith(root) ? candidate : null;
    }
}
