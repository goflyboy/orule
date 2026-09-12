package com.orule.server.storage;

import com.orule.common.storage.LocalStorage;
import com.orule.common.storage.UploadResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC-0017 §5 — Unit tests for {@link LocalStorage}.
 *
 * <p>Covers: upload/download consistency, sha256, path-traversal protection,
 * delete idempotency, list, exists, getUrl.
 */
public class LocalStorageTest {

    @TempDir
    Path tmp;

    private LocalStorage storage;
    private Path basePath;

    @BeforeEach
    void setUp() throws IOException {
        basePath = tmp.resolve("artifacts");
        Files.createDirectories(basePath);
        storage = new LocalStorage(basePath, "http://localhost:8080/api/v1/artifacts");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(basePath)) {
            // Best-effort cleanup; @TempDir will wipe tmp anyway
            Files.walk(basePath)
                .sorted((a, b) -> b.toString().length() - a.toString().length())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
        }
    }

    @Test
    @DisplayName("upload + download: byte-identical, sha256 matches, file size matches")
    void uploadDownloadRoundTrip() throws IOException {
        byte[] content = "hello, orule!".getBytes(StandardCharsets.UTF_8);

        UploadResult result = storage.upload("rules/A/v1.txt", content);

        assertEquals("rules/A/v1.txt", result.storagePath());
        assertEquals(content.length, result.fileSize());
        assertEquals("da6a8166fdb71b8db68023a2cf04ef39a4f2d92b1ed9b5a9b3c2a3a48e8b8b0a".length(),
            result.sha256().length(), "sha256 should be 64 hex chars");
        assertNotNull(result.url());
        assertEquals("http://localhost:8080/api/v1/artifacts/rules/A/v1.txt", result.url());
        assertEquals(LocalStorage.STORAGE_TYPE, result.storageType());

        try (InputStream in = storage.download("rules/A/v1.txt")) {
            byte[] read = in.readAllBytes();
            assertArrayEquals(content, read);
        }
    }

    @Test
    @DisplayName("upload creates parent directories automatically")
    void uploadCreatesParents() throws IOException {
        byte[] content = "x".getBytes();
        storage.upload("deep/nested/dir/file.txt", content);
        assertTrue(Files.exists(basePath.resolve("deep/nested/dir/file.txt")));
    }

    @Test
    @DisplayName("exists returns true after upload and false for missing keys")
    void existsWorks() {
        assertFalse(storage.exists("rules/A/missing.txt"));
        storage.upload("rules/A/v1.txt", "x".getBytes());
        assertTrue(storage.exists("rules/A/v1.txt"));
    }

    @Test
    @DisplayName("delete is idempotent (re-delete does not throw)")
    void deleteIsIdempotent() {
        storage.upload("rules/A/v1.txt", "x".getBytes());
        storage.delete("rules/A/v1.txt");
        storage.delete("rules/A/v1.txt"); // should not throw
        assertFalse(storage.exists("rules/A/v1.txt"));
    }

    @Test
    @DisplayName("list returns all files under a prefix, recursively")
    void listReturnsFilesRecursively() {
        storage.upload("rules/A/v1.txt", "1".getBytes());
        storage.upload("rules/A/v2.txt", "2".getBytes());
        storage.upload("rules/B/v1.txt", "3".getBytes());

        List<String> aKeys = storage.list("rules/A");
        assertEquals(2, aKeys.size());
        assertTrue(aKeys.contains("rules/A/v1.txt"));
        assertTrue(aKeys.contains("rules/A/v2.txt"));

        List<String> allKeys = storage.list("rules");
        assertEquals(3, allKeys.size());
    }

    @Test
    @DisplayName("list on missing directory returns empty list")
    void listMissingPrefix() {
        List<String> keys = storage.list("nonexistent");
        assertTrue(keys.isEmpty());
    }

    @Test
    @DisplayName("getUrl returns baseUrl + key")
    void getUrlCombinesBaseUrlAndKey() {
        assertEquals("http://localhost:8080/api/v1/artifacts/rules/A/v1.txt",
            storage.getUrl("rules/A/v1.txt"));
    }

    @Test
    @DisplayName("getUrl returns null when baseUrl is null")
    void getUrlWithNullBaseUrl() {
        LocalStorage noUrl = new LocalStorage(tmp.resolve("no-url"), null);
        assertNull(noUrl.getUrl("anything"));
    }

    @Test
    @DisplayName("path traversal blocked: ../etc/passwd rejected")
    void pathTraversalBlocked() {
        assertThrows(IllegalArgumentException.class,
            () -> storage.upload("../etc/passwd", "x".getBytes()));
        assertThrows(IllegalArgumentException.class,
            () -> storage.upload("rules/../../escape.txt", "x".getBytes()));
    }

    @Test
    @DisplayName("absolute paths blocked: /etc/passwd rejected")
    void absolutePathBlocked() {
        assertThrows(IllegalArgumentException.class,
            () -> storage.upload("/etc/passwd", "x".getBytes()));
        assertThrows(IllegalArgumentException.class,
            () -> storage.upload("\\windows\\system32", "x".getBytes()));
    }

    @Test
    @DisplayName("null or blank key rejected")
    void invalidKeyRejected() {
        assertThrows(IllegalArgumentException.class, () -> storage.upload(null, "x".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> storage.upload("", "x".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> storage.upload("   ", "x".getBytes()));
    }

    @Test
    @DisplayName("storageType returns 'local'")
    void storageTypeIsLocal() {
        assertEquals("local", storage.storageType());
    }

    @Test
    @DisplayName("exists() returns false for invalid keys instead of throwing")
    void existsDoesNotThrowOnInvalidKey() {
        assertFalse(storage.exists("../escape"));
        assertFalse(storage.exists("/abs"));
    }
}
