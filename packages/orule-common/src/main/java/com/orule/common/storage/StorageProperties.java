package com.orule.common.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "orule.artifact-storage")
public class StorageProperties {

    /** Storage backend type: local (MVP) / s3 / oss / minio (phase 2). */
    private String type = STORAGE_TYPE_LOCAL;

    private LocalProps local = new LocalProps();

    public static final String STORAGE_TYPE_LOCAL = "local";
    public static final String STORAGE_TYPE_S3 = "s3";
    public static final String STORAGE_TYPE_OSS = "oss";
    public static final String STORAGE_TYPE_MINIO = "minio";

    @Getter
    @Setter
    public static class LocalProps {
        private String basePath = System.getProperty("user.home") + "/orule/data/artifacts";
        private String baseUrl = "http://localhost:8080/api/v1/artifacts";
    }

    @Getter
    @Setter
    public static class S3Props {
        private String bucket;
        private String region;
        private String baseUrl;
        private String accessKey;
        private String secretKey;
    }

    @Getter
    @Setter
    public static class OssProps {
        private String bucket;
        private String endpoint;
        private String baseUrl;
        private String accessKey;
        private String secretKey;
    }

    @Getter
    @Setter
    public static class MinioProps {
        private String bucket;
        private String endpoint;
        private String baseUrl;
        private String accessKey;
        private String secretKey;
    }
}
