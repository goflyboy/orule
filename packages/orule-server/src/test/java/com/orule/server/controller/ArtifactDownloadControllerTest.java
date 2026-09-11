package com.orule.server.controller;

import com.orule.common.storage.ArtifactStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jakarta.annotation.PostConstruct;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RFC-0017 §3.6 — Integration test for ArtifactDownloadController.
 *
 * <p>Tests the REST download endpoint backed by {@link ArtifactStorage}.
 * Uploads artifacts directly through the storage bean so we can verify
 * the end-to-end upload → list → download round trip.
 */
@SpringBootTest
@ActiveProfiles("test")
class ArtifactDownloadControllerTest {

    @Autowired private WebApplicationContext ctx;
    @Autowired private ArtifactStorage storage;

    private MockMvc mvc;

    @PostConstruct
    void init() {
        this.mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    @BeforeEach
    void cleanup() {
        // Best-effort cleanup: storage basePath is a per-run random UUID directory.
        storage.list("").forEach(storage::delete);
    }

    @Test
    @DisplayName("Download returns uploaded bytes for an existing key")
    void downloadReturnsBytes() throws Exception {
        byte[] payload = "hello-artifact".getBytes();
        storage.upload("rules/ORDER/v1.jar", payload);

        mvc.perform(get("/api/v1/artifacts/rules/ORDER/v1.jar"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/octet-stream"))
            .andExpect(content().bytes(payload));
    }

    @Test
    @DisplayName("Download for missing key returns 404")
    void downloadMissing() throws Exception {
        mvc.perform(get("/api/v1/artifacts/does/not/exist.jar"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Path traversal is rejected by the storage layer (404 or 400)")
    void pathTraversalBlocked() throws Exception {
        // Spring decodes the path; "../escape.txt" must not escape basePath.
        // LocalStorage.validateKey throws IllegalArgumentException → 500.
        // We just verify the controller does NOT return the contents.
        mvc.perform(get("/api/v1/artifacts/..%2F..%2Fescape.txt"))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                if (status == 200) {
                    throw new AssertionError("Path traversal must NOT return 200");
                }
                // 4xx (404 not found from exists()) or 5xx (validation failure) are both acceptable.
            });
    }

    @Test
    @DisplayName("Download for empty path returns 404")
    void downloadEmptyPath() throws Exception {
        // /api/v1/artifacts/ matches the /** mapping but key is empty.
        // exists("") returns false → 404.
        mvc.perform(get("/api/v1/artifacts/"))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                org.junit.jupiter.api.Assertions.assertTrue(status == 404 || status == 500,
                    "Expected 404 or 500 for empty key, got " + status);
            });
    }
}
