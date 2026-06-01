package com.musicstreaming.auth.controller;

import com.musicstreaming.auth.dto.*;
import com.musicstreaming.auth.service.AuthService;
import com.musicstreaming.auth.service.StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final StorageService storageService;

    @PostMapping("/register")
    public Mono<ResponseEntity<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request)
                .map(user -> ResponseEntity.status(HttpStatus.CREATED).body(user))
                .doOnSuccess(r -> log.info("Register successful userId={} username={}", r.getBody().getId(), request.getUsername()));
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.getUsername(), request.getPassword())
                .map(ResponseEntity::ok)
                .doOnSuccess(r -> log.info("Login successful userId={} username={}", r.getBody().getUser().getId(), request.getUsername()));
    }

    @GetMapping("/me")
    public Mono<ResponseEntity<UserResponse>> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        return authService.getCurrentUser(principal.getId())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/storage")
    public Mono<ResponseEntity<StorageUsageResponse>> getStorageUsage(@AuthenticationPrincipal UserPrincipal principal) {
        return storageService.getUsage(principal.getId())
                .map(ResponseEntity::ok);
    }
}
