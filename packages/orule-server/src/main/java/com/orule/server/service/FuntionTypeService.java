package com.orule.server.service;

import com.orule.common.dto.CreateFuntionTypeRequest;
import com.orule.common.dto.FuntionTypeDto;
import com.orule.common.dto.UpdateFuntionTypeRequest;
import com.orule.common.entity.FuntionType;
import com.orule.common.exception.NotFoundException;
import com.orule.server.repository.FuntionTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * FuntionType 服务（RFC-0031 重构版）。
 *
 * <p>signature 字段为 FunctionSignature（Type 树 JSON）。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FuntionTypeService {

    private final FuntionTypeRepository repo;

    public List<FuntionTypeDto> findAll() {
        return repo.findAll().stream().map(this::toDto).toList();
    }

    public List<FuntionTypeDto> findByCategory(String category) {
        return repo.findByCategory(category).stream().map(this::toDto).toList();
    }

    public FuntionTypeDto findById(String id) {
        return toDto(repo.findById(id)
            .orElseThrow(() -> new NotFoundException("FuntionType", id)));
    }

    @Transactional
    public FuntionTypeDto create(CreateFuntionTypeRequest req) {
        FuntionType e = FuntionType.builder()
            .id(UUID.randomUUID().toString())
            .programCode(req.programCode()).name(req.name())
            .signature(req.signature())
            .description(req.description()).category(req.category())
            .isBuiltin(req.builtin()).build();
        return toDto(repo.save(e));
    }

    @Transactional
    public FuntionTypeDto update(String id, UpdateFuntionTypeRequest req) {
        FuntionType e = repo.findById(id)
            .orElseThrow(() -> new NotFoundException("FuntionType", id));
        e.setName(req.name());
        e.setSignature(req.signature());
        e.setDescription(req.description());
        e.setCategory(req.category());
        e.setIsBuiltin(req.builtin());
        return toDto(repo.save(e));
    }

    @Transactional
    public void delete(String id) {
        if (!repo.existsById(id)) throw new NotFoundException("FuntionType", id);
        repo.deleteById(id);
    }

    private FuntionTypeDto toDto(FuntionType e) {
        return new FuntionTypeDto(e.getId(), e.getProgramCode(), e.getName(),
            e.getSignature(), e.getDescription(), e.getCategory(),
            Boolean.TRUE.equals(e.getIsBuiltin()));
    }
}
