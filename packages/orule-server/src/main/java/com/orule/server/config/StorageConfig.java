package com.orule.server.config;

import com.orule.common.storage.ArtifactStorage;
import com.orule.common.storage.LocalStorage;
import com.orule.common.storage.StorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    public ArtifactStorage artifactStorage(StorageProperties props) {
        // MVP: only LocalStorage. Phase 2 will switch by props.getType().
        return new LocalStorage(
            props.getLocal().getBasePath(),
            props.getLocal().getBaseUrl()
        );
    }
}
