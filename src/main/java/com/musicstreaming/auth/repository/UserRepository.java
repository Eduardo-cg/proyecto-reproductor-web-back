package com.musicstreaming.auth.repository;

import com.musicstreaming.auth.entity.User;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

public interface UserRepository extends R2dbcRepository<User, Long> {

    Mono<User> findByUsername(String username);

    Mono<Boolean> existsByUsername(String username);

    Mono<Boolean> existsByEmail(String email);

    @Query("SELECT COALESCE(SUM(t.file_size), 0) FROM tracks t WHERE t.user_id = :userId")
    Mono<Long> sumTrackFileSizeByUserId(Long userId);
}