package com.orule.server.repository;

import com.orule.common.entity.EnumType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EnumTypeRepository extends JpaRepository<EnumType, String> {
    Optional<EnumType> findByCode(String code);
}
