package com.orule.server.repository;

import com.orule.common.entity.RuleArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RuleArtifactRepository extends JpaRepository<RuleArtifact, String> {
    Optional<RuleArtifact> findByRuleVersionId(String ruleVersionId);
}
