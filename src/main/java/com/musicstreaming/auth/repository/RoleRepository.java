package com.musicstreaming.auth.repository;

import com.musicstreaming.auth.entity.Role;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

public interface RoleRepository extends R2dbcRepository<Role, Long> {

    Mono<Role> findByName(String name);
}
