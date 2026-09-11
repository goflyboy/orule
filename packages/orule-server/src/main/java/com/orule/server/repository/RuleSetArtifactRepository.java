package com.orule.server.repository;

import com.orule.common.entity.RuleSetArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RuleSetArtifactRepository extends JpaRepository<RuleSetArtifact, String> {
    Optional<RuleSetArtifact> findByRuleSetIdAndVersion(String ruleSetId, Integer version);
}
