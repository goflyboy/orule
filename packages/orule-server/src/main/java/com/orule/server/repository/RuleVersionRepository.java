package com.orule.server.repository;

import com.orule.common.entity.RuleVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RuleVersionRepository extends JpaRepository<RuleVersion, String> {
    List<RuleVersion> findByRuleId(String ruleId);

    List<RuleVersion> findByRuleIdOrderByVersionDesc(String ruleId);

    Optional<RuleVersion> findByRuleIdAndStatus(String ruleId, String status);

    @Query("SELECT COALESCE(MAX(rv.version), 0) FROM RuleVersion rv WHERE rv.rule.id = :ruleId")
    Integer findMaxVersionByRuleId(@Param("ruleId") String ruleId);
}
