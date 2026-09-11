package com.orule.common.storage;

public record UploadResult(
    String storagePath,
    String url,
    long fileSize,
    String sha256,
    String storageType
) {}
