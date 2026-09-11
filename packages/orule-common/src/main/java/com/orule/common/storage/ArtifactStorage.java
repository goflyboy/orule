package com.orule.common.storage;

import java.io.InputStream;
import java.util.List;

/**
 * RFC-0017 §3.1 — Artifact storage abstraction.
 * Implementations: LocalStorage (MVP), S3/OSS/MinIO (phase 2).
 */
public interface ArtifactStorage {

    /** Upload artifact. Returns the storage path, URL, size, and SHA-256. */
    UploadResult upload(String key, byte[] content);

    /** Download artifact as an input stream. */
    InputStream download(String key);

    /** Delete artifact. Idempotent. */
    void delete(String key);

    /** Check whether the artifact exists. */
    boolean exists(String key);

    /** List all artifacts under a prefix (directory-style). */
    List<String> list(String prefix);

    /** Resolve to a downloadable URL if the backend supports it; null otherwise. */
    String getUrl(String key);

    /** Storage backend type (local / s3 / oss / minio). */
    String storageType();
}
