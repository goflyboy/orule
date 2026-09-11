package com.orule.server.repository;

import com.orule.common.entity.RuleSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RuleSetRepository extends JpaRepository<RuleSet, String> {
    Optional<RuleSet> findByCode(String code);
}
