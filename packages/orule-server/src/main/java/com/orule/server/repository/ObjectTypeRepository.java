package com.orule.server.repository;

import com.orule.common.entity.ObjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ObjectTypeRepository extends JpaRepository<ObjectType, String> {
    List<ObjectType> findByDomainId(String domainId);
}
