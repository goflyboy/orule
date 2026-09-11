package com.orule.server.exception;

import com.orule.common.dto.Result;
import com.orule.common.exception.ConflictException;
import com.orule.common.exception.NotFoundException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(404).body(Result.error(404, e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Result<Void>> handleConflict(ConflictException e) {
        return ResponseEntity.status(409).body(Result.error(409, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().stream()
            .findFirst()
            .map(err -> err.getDefaultMessage())
            .orElse("Validation failed");
        return ResponseEntity.status(400).body(Result.error(400, message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException e) {
        return ResponseEntity.status(400).body(Result.error(400, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegal(IllegalArgumentException e) {
        return ResponseEntity.status(400).body(Result.error(400, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("Internal error", e);
        return ResponseEntity.status(500).body(Result.error(500, "Internal server error"));
    }
}
