package com.orule.server.repository;

import com.orule.common.entity.ObjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ObjectTypeRepository extends JpaRepository<ObjectType, String> {
    List<ObjectType> findByDomainId(String domainId);

    /** RFC-0045 §4.3: 按 domain.programCode + object.programCode 定位。 */
    default Optional<ObjectType> findByDomainCodeAndProgramCode(String domainCode, String programCode) {
        return findByDomainId(domainCode == null ? null : domainCode).stream()
            .filter(o -> programCode != null && programCode.equals(o.getProgramCode()))
            .findFirst();
    }
}
