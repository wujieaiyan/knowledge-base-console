package com.kbconsole.document;

import com.kbconsole.common.ApiException;
import com.kbconsole.common.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
public class StorageService {
    private final Path root;

    public StorageService(@Value("${kb.storage-dir:./data/uploads}") String storageDir) {
        this.root = Paths.get(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建文件存储目录: " + root, ex);
        }
        log.info("File storage directory: {}", root);
    }

    public record StoredFile(String key, Path path) {
    }

    public StoredFile store(Long seriesId, MultipartFile file, String ext) {
        String key = seriesId + "/" + UUID.randomUUID() + "." + ext;
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredFile(key, target);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL, "文件保存失败");
        }
    }

    public String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL, "文件摘要计算失败");
        }
    }

    public Path resolve(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "非法的文件路径");
        }
        return path;
    }

    public void deleteQuietly(String storageKey) {
        if (storageKey == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (Exception ex) {
            log.warn("Failed to delete stored file {}", storageKey, ex);
        }
    }
}
