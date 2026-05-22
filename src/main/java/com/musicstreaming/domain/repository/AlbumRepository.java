package com.musicstreaming.domain.repository;

import com.musicstreaming.domain.model.Album;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AlbumRepository extends R2dbcRepository<Album, Long> {

    Flux<Album> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT COUNT(*) FROM albums WHERE user_id = :userId")
    Mono<Long> countByUserId(Long userId);
}
