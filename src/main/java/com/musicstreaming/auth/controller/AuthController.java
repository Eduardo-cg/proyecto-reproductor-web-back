package com.musicstreaming.auth.controller;

import com.musicstreaming.auth.dto.*;
import com.musicstreaming.auth.service.AuthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request)
                .map(user -> ResponseEntity.status(HttpStatus.CREATED).body(user))
                .doOnSuccess(r -> log.info("Register successful for: {}", request.getUsername()));
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.getUsername(), request.getPassword())
                .map(ResponseEntity::ok)
                .doOnSuccess(r -> log.info("Login successful for: {}", request.getUsername()));
    }

    @GetMapping("/me")
    public Mono<ResponseEntity<UserResponse>> getCurrentUser(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        Long userId = Long.parseLong(((UserPrincipal) userDetails).getId().toString());
        return authService.getCurrentUser(userId)
                .map(ResponseEntity::ok);
    }
}