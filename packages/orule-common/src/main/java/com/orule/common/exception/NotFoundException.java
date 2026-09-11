package com.orule.common.exception;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String entity, String id) {
        super(entity + " not found: " + id);
    }
}
