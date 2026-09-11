package com.orule.common.storage;

import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * RFC-0017 §3.3 — Local filesystem-backed ArtifactStorage implementation.
 *
 * <p>All paths are constrained to the {@code basePath} via path normalization
 * ({@code normalize().startsWith(basePath)}) to prevent path-traversal attacks.
 *
 * <p>Logging is delegated to SLF4J. We declare an SLF4J bridge via {@code commons-logging}
 * (which Jakarta Commons Logging / Spring auto-bridge to whatever backend).
 */
public class LocalStorage implements ArtifactStorage {

    public static final String STORAGE_TYPE = "local";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalStorage.class);

    private final Path basePath;
    private final String baseUrl;

    public LocalStorage(String basePath, String baseUrl) {
        this(Paths.get(basePath).toAbsolutePath(), baseUrl);
    }

    public LocalStorage(Path basePath, String baseUrl) {
        this.basePath = basePath;
        this.baseUrl = baseUrl;
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new RuntimeException("Unable to create storage directory: " + basePath, e);
        }
    }

    @Override
    public String storageType() {
        return STORAGE_TYPE;
    }

    @Override
    public UploadResult upload(String key, byte[] content) {
        validateKey(key);
        Path target = resolveSafe(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            String sha256 = DigestUtils.sha256Hex(content);
            long size = content.length;
            String url = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl + "/" + key : null;
            log.info("Uploaded artifact key={} size={} sha256={}", key, size, sha256);
            return new UploadResult(key, url, size, sha256, STORAGE_TYPE);
        } catch (IOException e) {
            throw new RuntimeException("Upload failed: " + key, e);
        }
    }

    @Override
    public InputStream download(String key) {
        validateKey(key);
        Path target = resolveSafe(key);
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new RuntimeException("Download failed: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        validateKey(key);
        Path target = basePath.resolve(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new RuntimeException("Delete failed: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            validateKey(key);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return Files.exists(basePath.resolve(key));
    }

    @Override
    public List<String> list(String prefix) {
        Path dir = basePath.resolve(prefix);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream
                .filter(Files::isRegularFile)
                .forEach(p -> results.add(basePath.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            throw new RuntimeException("List failed: " + prefix, e);
        }
        return results;
    }

    @Override
    public String getUrl(String key) {
        return (baseUrl != null && !baseUrl.isBlank()) ? baseUrl + "/" + key : null;
    }

    private Path resolveSafe(String key) {
        Path target = basePath.resolve(key).normalize();
        if (!target.startsWith(basePath)) {
            throw new IllegalArgumentException("Path traversal blocked for key: " + key);
        }
        return target;
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()
                || key.contains("..")
                || key.startsWith("/")
                || key.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
    }
}
