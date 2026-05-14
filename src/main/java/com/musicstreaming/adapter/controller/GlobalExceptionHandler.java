package com.musicstreaming.adapter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RuntimeException.class)
    public Mono<ResponseEntity<Map<String, String>>> handleRuntimeException(RuntimeException ex) {
        log.error("Runtime exception: {}", ex.getMessage());

        String message = ex.getMessage();
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;

        if (message.contains("not found")) {
            status = HttpStatus.NOT_FOUND;
        } else if (message.contains("already exists")) {
            status = HttpStatus.CONFLICT;
        } else if (message.contains("Invalid") || message.contains("Access denied")) {
            status = HttpStatus.UNAUTHORIZED;
        }

        return Mono.just(ResponseEntity.status(status)
                .body(Map.of("error", message)));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, String>>> handleException(Exception ex) {
        log.error("Unexpected exception: {}", ex.getMessage(), ex);

        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error")));
    }
}