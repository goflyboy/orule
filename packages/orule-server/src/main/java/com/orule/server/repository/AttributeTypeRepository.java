package com.orule.server.repository;

import com.orule.common.entity.AttributeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AttributeTypeRepository extends JpaRepository<AttributeType, String> {
    List<AttributeType> findByObjectId(String objectId);
}
