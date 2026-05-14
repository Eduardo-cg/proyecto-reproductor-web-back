package com.musicstreaming.domain.repository;

import com.musicstreaming.domain.model.User;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

public interface UserRepository extends R2dbcRepository<User, Long> {

    Mono<User> findByUsername(String username);

    Mono<User> findByEmail(String email);

    Mono<Boolean> existsByUsername(String username);

    Mono<Boolean> existsByEmail(String email);
}