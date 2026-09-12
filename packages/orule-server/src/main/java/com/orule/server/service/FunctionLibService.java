package com.orule.server.service;

import com.orule.common.dto.CreateFunctionLibRequest;
import com.orule.common.dto.FunctionLibDto;
import com.orule.common.dto.UpdateFunctionLibRequest;
import com.orule.common.entity.FunctionLib;
import com.orule.common.exception.NotFoundException;
import com.orule.server.repository.FunctionLibRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * FunctionLib 服务（RFC-0031 重构版）。
 *
 * <p>signature 字段为 FunctionSignature（Type 树 JSON）。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FunctionLibService {

    private final FunctionLibRepository repo;

    public List<FunctionLibDto> findAll() {
        return repo.findAll().stream().map(this::toDto).toList();
    }

    public List<FunctionLibDto> findByCategory(String category) {
        return repo.findByCategory(category).stream().map(this::toDto).toList();
    }

    public FunctionLibDto findById(String id) {
        return toDto(repo.findById(id)
            .orElseThrow(() -> new NotFoundException("FunctionLib", id)));
    }

    @Transactional
    public FunctionLibDto create(CreateFunctionLibRequest req) {
        FunctionLib e = FunctionLib.builder()
            .id(UUID.randomUUID().toString())
            .code(req.code()).name(req.name())
            .signature(req.signature())
            .description(req.description()).category(req.category())
            .isBuiltin(req.builtin()).build();
        return toDto(repo.save(e));
    }

    @Transactional
    public FunctionLibDto update(String id, UpdateFunctionLibRequest req) {
        FunctionLib e = repo.findById(id)
            .orElseThrow(() -> new NotFoundException("FunctionLib", id));
        e.setName(req.name());
        e.setSignature(req.signature());
        e.setDescription(req.description());
        e.setCategory(req.category());
        e.setIsBuiltin(req.builtin());
        return toDto(repo.save(e));
    }

    @Transactional
    public void delete(String id) {
        if (!repo.existsById(id)) throw new NotFoundException("FunctionLib", id);
        repo.deleteById(id);
    }

    private FunctionLibDto toDto(FunctionLib e) {
        return new FunctionLibDto(e.getId(), e.getCode(), e.getName(),
            e.getSignature(), e.getDescription(), e.getCategory(),
            Boolean.TRUE.equals(e.getIsBuiltin()));
    }
}
