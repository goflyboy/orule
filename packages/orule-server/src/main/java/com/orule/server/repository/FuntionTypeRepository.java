package com.orule.server.repository;

import com.orule.common.entity.FuntionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FuntionTypeRepository extends JpaRepository<FuntionType, String> {
    List<FuntionType> findByCategory(String category);
}
