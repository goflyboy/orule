package com.orule.server.service;

import com.orule.common.dto.*;
import com.orule.common.entity.AttributeType;
import com.orule.common.entity.DomainType;
import com.orule.common.entity.ObjectType;
import com.orule.common.exception.ConflictException;
import com.orule.common.exception.NotFoundException;
import com.orule.server.repository.AttributeTypeRepository;
import com.orule.server.repository.DomainTypeRepository;
import com.orule.server.repository.ObjectTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 元数据域服务（RFC-0031 重构版）。
 *
 * <p>不再管理独立 enum 表；enum 定义内联到 AttributeType.type 中。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MetadataService {

    private final DomainTypeRepository domainRepo;
    private final ObjectTypeRepository objectRepo;
    private final AttributeTypeRepository attrRepo;

    // === DomainType ===

    public List<DomainTypeDto> findAllDomainTypes() {
        return domainRepo.findAll().stream().map(this::toDomainDto).toList();
    }

    public DomainTypeDto findDomainType(String id) {
        return toDomainDto(domainRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("DomainType", id)));
    }

    public DomainTypeDto findDomainTypeByCode(String code) {
        return toDomainDto(domainRepo.findByCode(code)
            .orElseThrow(() -> new NotFoundException("DomainType", "code=" + code)));
    }

    @Transactional
    public DomainTypeDto createDomainType(CreateDomainTypeRequest req) {
        if (domainRepo.existsByCode(req.code())) {
            throw new ConflictException("DomainType code already exists: " + req.code());
        }
        DomainType e = DomainType.builder()
            .id(UUID.randomUUID().toString())
            .code(req.code()).name(req.name())
            .description(req.description()).ownerCode(req.ownerCode())
            .build();
        return toDomainDto(domainRepo.save(e));
    }

    @Transactional
    public DomainTypeDto updateDomainType(String id, UpdateDomainTypeRequest req) {
        DomainType e = domainRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("DomainType", id));
        e.setName(req.name());
        e.setDescription(req.description());
        e.setOwnerCode(req.ownerCode());
        return toDomainDto(domainRepo.save(e));
    }

    @Transactional
    public void deleteDomainType(String id) {
        if (!domainRepo.existsById(id)) throw new NotFoundException("DomainType", id);
        domainRepo.deleteById(id);
    }

    // === ObjectType ===

    public List<ObjectTypeDto> findAllObjectTypes() {
        return objectRepo.findAll().stream().map(this::toObjectDto).toList();
    }

    public List<ObjectTypeDto> findObjectTypesByDomain(String domainId) {
        return objectRepo.findByDomainId(domainId).stream().map(this::toObjectDto).toList();
    }

    public ObjectTypeDto findObjectType(String id) {
        return toObjectDto(objectRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("ObjectType", id)));
    }

    @Transactional
    public ObjectTypeDto findObjectTypeWithAttributes(String id) {
        ObjectType e = objectRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("ObjectType", id));
        // Force-initialize lazy collection inside the transaction.
        e.getAttributes().size();
        List<AttributeTypeDto> attrs = attrRepo.findByObjectId(id).stream()
            .map(this::toAttrDto).toList();
        return new ObjectTypeDto(e.getId(),
            e.getDomain() != null ? e.getDomain().getId() : null,
            e.getCode(), e.getName(), e.getDescription(),
            attrs, e.getCreatedAt(), e.getUpdatedAt());
    }

    @Transactional
    public ObjectTypeDto createObjectType(CreateObjectTypeRequest req) {
        DomainType domain = domainRepo.findById(req.domainId())
            .orElseThrow(() -> new NotFoundException("DomainType", req.domainId()));
        ObjectType e = ObjectType.builder()
            .id(UUID.randomUUID().toString())
            .domain(domain).code(req.code()).name(req.name())
            .description(req.description()).build();
        return toObjectDto(objectRepo.save(e));
    }

    @Transactional
    public ObjectTypeDto updateObjectType(String id, UpdateObjectTypeRequest req) {
        ObjectType e = objectRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("ObjectType", id));
        e.setName(req.name());
        e.setDescription(req.description());
        return toObjectDto(objectRepo.save(e));
    }

    @Transactional
    public void deleteObjectType(String id) {
        if (!objectRepo.existsById(id)) throw new NotFoundException("ObjectType", id);
        objectRepo.deleteById(id);
    }

    // === AttributeType ===

    public List<AttributeTypeDto> findAttributesByObject(String objectId) {
        return attrRepo.findByObjectId(objectId).stream().map(this::toAttrDto).toList();
    }

    @Transactional
    public AttributeTypeDto createAttributeType(CreateAttributeTypeRequest req) {
        ObjectType obj = objectRepo.findById(req.objectId())
            .orElseThrow(() -> new NotFoundException("ObjectType", req.objectId()));
        AttributeType e = AttributeType.builder()
            .id(UUID.randomUUID().toString())
            .object(obj).code(req.code()).name(req.name())
            .dataType(req.dataType()).type(req.type())
            .isRequired(Boolean.TRUE.equals(req.required()))
            .defaultValue(req.defaultValue()).description(req.description()).build();
        return toAttrDto(attrRepo.save(e));
    }

    // === DTO conversions ===

    private DomainTypeDto toDomainDto(DomainType e) {
        return new DomainTypeDto(e.getId(), e.getCode(), e.getName(),
            e.getDescription(), e.getOwnerCode(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private ObjectTypeDto toObjectDto(ObjectType e) {
        return new ObjectTypeDto(e.getId(),
            e.getDomain() != null ? e.getDomain().getId() : null,
            e.getCode(), e.getName(), e.getDescription(), null,
            e.getCreatedAt(), e.getUpdatedAt());
    }

    private ObjectTypeDto toObjectDtoWithAttrs(ObjectType e) {
        List<AttributeTypeDto> attrs = e.getAttributes() != null
            ? e.getAttributes().stream().map(this::toAttrDto).toList()
            : List.of();
        return new ObjectTypeDto(e.getId(),
            e.getDomain() != null ? e.getDomain().getId() : null,
            e.getCode(), e.getName(), e.getDescription(), attrs,
            e.getCreatedAt(), e.getUpdatedAt());
    }

    private AttributeTypeDto toAttrDto(AttributeType a) {
        return new AttributeTypeDto(a.getId(),
            a.getObject() != null ? a.getObject().getId() : null,
            a.getCode(), a.getName(), a.getDataType(),
            a.getType(),
            Boolean.TRUE.equals(a.getIsRequired()),
            a.getDefaultValue(), a.getDescription());
    }
}
