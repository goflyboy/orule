package com.orule.server.repository;

import com.orule.common.entity.DomainType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DomainTypeRepository extends JpaRepository<DomainType, String> {
    Optional<DomainType> findByCode(String code);
    boolean existsByCode(String code);
}
