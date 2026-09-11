package com.orule.server.repository;

import com.orule.common.entity.FunctionLib;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FunctionLibRepository extends JpaRepository<FunctionLib, String> {
    List<FunctionLib> findByCategory(String category);
}
