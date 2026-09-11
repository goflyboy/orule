package com.orule.common.dto;

public record UpdateRuleVersionRequest(
    String description,
    String simpleTs,
    String changelog
) {}
