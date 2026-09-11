package com.orule.server.service;

import com.orule.common.dto.CreateEnumTypeRequest;
import com.orule.common.dto.CreateEnumValueRequest;
import com.orule.common.dto.EnumTypeDto;
import com.orule.common.dto.EnumValueDto;
import com.orule.common.dto.UpdateEnumTypeRequest;
import com.orule.common.entity.EnumType;
import com.orule.common.entity.EnumValue;
import com.orule.common.exception.ConflictException;
import com.orule.common.exception.NotFoundException;
import com.orule.server.repository.EnumTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnumTypeService {

    private final EnumTypeRepository enumRepo;

    public List<EnumTypeDto> findAll() {
        return enumRepo.findAll().stream().map(this::toDto).toList();
    }

    public EnumTypeDto findById(String id) {
        return toDto(enumRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("EnumType", id)));
    }

    public EnumTypeDto findByIdWithValues(String id) {
        return toDto(enumRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("EnumType", id)));
    }

    @Transactional
    public EnumTypeDto create(CreateEnumTypeRequest req) {
        if (enumRepo.findByCode(req.code()).isPresent()) {
            throw new ConflictException("EnumType code already exists: " + req.code());
        }
        EnumType e = EnumType.builder()
            .id(UUID.randomUUID().toString())
            .code(req.code()).name(req.name())
            .description(req.description()).build();
        return toDto(enumRepo.save(e));
    }

    @Transactional
    public EnumTypeDto update(String id, UpdateEnumTypeRequest req) {
        EnumType e = enumRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("EnumType", id));
        e.setName(req.name());
        e.setDescription(req.description());
        return toDto(enumRepo.save(e));
    }

    @Transactional
    public void delete(String id) {
        if (!enumRepo.existsById(id)) throw new NotFoundException("EnumType", id);
        enumRepo.deleteById(id);
    }

    @Transactional
    public EnumTypeDto addValue(String enumTypeId, CreateEnumValueRequest req) {
        EnumType e = enumRepo.findById(enumTypeId)
            .orElseThrow(() -> new NotFoundException("EnumType", enumTypeId));
        EnumValue v = EnumValue.builder()
            .id(UUID.randomUUID().toString())
            .enumType(e).code(req.code()).name(req.name())
            .sortOrder(req.sortOrder()).build();
        e.getValues().add(v);
        enumRepo.save(e);
        return toDto(e);
    }

    private EnumTypeDto toDto(EnumType e) {
        List<EnumValueDto> values = e.getValues() != null
            ? e.getValues().stream().map(this::toValueDto).toList()
            : List.of();
        return new EnumTypeDto(e.getId(), e.getCode(), e.getName(), e.getDescription(), values);
    }

    private EnumValueDto toValueDto(EnumValue v) {
        return new EnumValueDto(v.getId(), v.getEnumType().getId(), v.getCode(), v.getName(), v.getSortOrder());
    }
}
