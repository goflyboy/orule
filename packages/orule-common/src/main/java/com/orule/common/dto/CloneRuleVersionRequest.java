package com.orule.common.dto;

public record CloneRuleVersionRequest(
    String changelog,
    String createdBy
) {}
