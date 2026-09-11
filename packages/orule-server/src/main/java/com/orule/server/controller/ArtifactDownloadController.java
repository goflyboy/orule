package com.orule.server.controller;

import com.orule.common.storage.ArtifactStorage;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;

/**
 * RFC-0017 §3.6 — Artifact download endpoint (used only by local storage backend).
 * For S3/OSS, clients should use the pre-signed URL instead.
 */
@RestController
@RequestMapping("/api/v1/artifacts")
@RequiredArgsConstructor
public class ArtifactDownloadController {

    private final ArtifactStorage storage;

    @GetMapping("/**")
    public ResponseEntity<Resource> download(HttpServletRequest request) {
        String key = extractKey(request);
        if (!storage.exists(key)) {
            return ResponseEntity.notFound().build();
        }
        InputStream stream = storage.download(key);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(new InputStreamResource(stream));
    }

    private String extractKey(HttpServletRequest request) {
        String fullPath = request.getRequestURI();
        String prefix = "/api/v1/artifacts/";
        if (fullPath.startsWith(prefix)) {
            return fullPath.substring(prefix.length());
        }
        return fullPath;
    }
}
