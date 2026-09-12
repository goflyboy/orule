package com.orule.server.service;

import com.orule.common.dto.*;
import com.orule.common.entity.AttributeType;
import com.orule.common.entity.DomainType;
import com.orule.common.entity.ObjectType;
import com.orule.common.exception.ConflictException;
import com.orule.common.exception.NotFoundException;
import com.orule.common.model.type.Type;
import com.orule.common.model.type.TypeFactory;
import com.orule.server.repository.AttributeTypeRepository;
import com.orule.server.repository.DomainTypeRepository;
import com.orule.server.repository.ObjectTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 元数据域服务（RFC-0032 重构版）。
 *
 * <p>变更要点：
 * <ul>
 *   <li>enum 不再独立管理：合入 ObjectType(kind=ENUM)，跨 attribute 共享</li>
 *   <li>AttributeType 用 3 列结构（data_type + sub_data_type_program_code + _2）</li>
 *   <li>组装 Type 时调用 {@link TypeFactory#buildType}</li>
 * </ul>
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

    public DomainTypeDto findDomainTypeByProgramCode(String programCode) {
        return toDomainDto(domainRepo.findByProgramCode(programCode)
            .orElseThrow(() -> new NotFoundException("DomainType", "programCode=" + programCode)));
    }

    @Transactional
    public DomainTypeDto createDomainType(CreateDomainTypeRequest req) {
        if (domainRepo.existsByProgramCode(req.programCode())) {
            throw new ConflictException("DomainType programCode already exists: " + req.programCode());
        }
        DomainType e = DomainType.builder()
            .id(UUID.randomUUID().toString())
            .programCode(req.programCode()).name(req.name())
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
        e.getAttributes().size(); // force init lazy
        List<AttributeTypeDto> attrs = attrRepo.findByObjectId(id).stream()
            .map(this::toAttrDto).toList();
        return new ObjectTypeDto(e.getId(),
            e.getDomain() != null ? e.getDomain().getId() : null,
            e.getProgramCode(), e.getName(), e.getKind().name(), attrs,
            toEnumValueDtos(e.getEnumValues()),
            e.getDescription(), e.getCreatedAt(), e.getUpdatedAt());
    }

    @Transactional
    public ObjectTypeDto createObjectType(CreateObjectTypeRequest req) {
        DomainType domain = domainRepo.findById(req.domainId())
            .orElseThrow(() -> new NotFoundException("DomainType", req.domainId()));
        ObjectType.Kind kind = parseKind(req.kind());
        if (kind == ObjectType.Kind.ENUM && (req.enumValues() == null || req.enumValues().isEmpty())) {
            throw new IllegalArgumentException("kind=ENUM 时 enumValues 必填");
        }
        ObjectType e = ObjectType.builder()
            .id(UUID.randomUUID().toString())
            .domain(domain)
            .programCode(req.programCode())
            .name(req.name())
            .kind(kind)
            .enumValues(toEntityEnumValues(req.enumValues()))
            .description(req.description()).build();
        return toObjectDto(objectRepo.save(e));
    }

    @Transactional
    public ObjectTypeDto updateObjectType(String id, UpdateObjectTypeRequest req) {
        ObjectType e = objectRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("ObjectType", id));
        e.setName(req.name());
        if (req.kind() != null) e.setKind(parseKind(req.kind()));
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
            .object(obj)
            .programCode(req.programCode())
            .name(req.name())
            .dataType(req.dataType())
            .subDataTypeProgramCode(req.subDataTypeProgramCode())
            .subDataTypeProgramCode2(req.subDataTypeProgramCode2())
            .isRequired(Boolean.TRUE.equals(req.required()))
            .defaultValue(req.defaultValue()).description(req.description()).build();
        return toAttrDto(attrRepo.save(e));
    }

    // === DTO conversions ===

    private DomainTypeDto toDomainDto(DomainType e) {
        return new DomainTypeDto(e.getId(), e.getProgramCode(), e.getName(),
            e.getDescription(), e.getOwnerCode(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private ObjectTypeDto toObjectDto(ObjectType e) {
        return new ObjectTypeDto(e.getId(),
            e.getDomain() != null ? e.getDomain().getId() : null,
            e.getProgramCode(), e.getName(), e.getKind().name(), null,
            toEnumValueDtos(e.getEnumValues()),
            e.getDescription(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private List<ObjectTypeDto.EnumValueDto> toEnumValueDtos(List<ObjectType.EnumValue> values) {
        if (values == null) return null;
        return values.stream()
            .map(v -> new ObjectTypeDto.EnumValueDto(v.code(), v.label(), v.sortOrder()))
            .toList();
    }

    private List<ObjectType.EnumValue> toEntityEnumValues(List<ObjectTypeDto.EnumValueDto> dtos) {
        if (dtos == null) return null;
        return dtos.stream()
            .map(d -> new ObjectType.EnumValue(d.code(), d.label(), d.sortOrder()))
            .toList();
    }

    private AttributeTypeDto toAttrDto(AttributeType a) {
        Type type = buildTypeFromAttribute(a);
        return new AttributeTypeDto(a.getId(),
            a.getObject() != null ? a.getObject().getId() : null,
            a.getProgramCode(), a.getName(), a.getDataType(),
            a.getSubDataTypeProgramCode(), a.getSubDataTypeProgramCode2(),
            Boolean.TRUE.equals(a.getIsRequired()),
            a.getDefaultValue(), a.getDescription(), type);
    }

    /**
     * 组装 Type：根据 attribute 的 3 列 + 同 domain 下所有 ObjectType。
     */
    private Type buildTypeFromAttribute(AttributeType a) {
        if (a.getObject() == null || a.getObject().getDomain() == null) {
            return null;
        }
        Map<String, ObjectType> objectsByCode = new HashMap<>();
        String domainId = a.getObject().getDomain().getId();
        objectRepo.findByDomainId(domainId)
            .forEach(o -> objectsByCode.put(o.getProgramCode(), o));
        return TypeFactory.buildType(a, objectsByCode);
    }

    private ObjectType.Kind parseKind(String s) {
        if (s == null) return ObjectType.Kind.CLASS;
        try {
            return ObjectType.Kind.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown kind: " + s + " (CLASS|ENUM)");
        }
    }
}
