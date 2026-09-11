package com.orule.server.repository;

import com.orule.common.entity.ArtifactStorageConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ArtifactStorageConfigRepository extends JpaRepository<ArtifactStorageConfig, String> {
    Optional<ArtifactStorageConfig> findByCode(String code);
    Optional<ArtifactStorageConfig> findByIsDefaultTrue();
}
