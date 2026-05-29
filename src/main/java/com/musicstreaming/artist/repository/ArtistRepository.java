package com.musicstreaming.artist.repository;

import com.musicstreaming.artist.entity.Artist;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ArtistRepository extends R2dbcRepository<Artist, Long> {

    Flux<Artist> findByUserId(Long userId);

    Flux<Artist> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT * FROM artists WHERE user_id = :userId AND LOWER(name) LIKE LOWER(CONCAT('%', :query, '%'))")
    Flux<Artist> searchByUserId(Long userId, String query);

    @Query("SELECT * FROM artists WHERE user_id = :userId AND LOWER(name) LIKE LOWER(CONCAT('%', :query, '%'))")
    Flux<Artist> searchByUserId(Long userId, String query, Pageable pageable);

    @Query("SELECT COUNT(*) FROM artists WHERE user_id = :userId AND LOWER(name) LIKE LOWER(CONCAT('%', :query, '%'))")
    Mono<Long> countByUserIdAndSearch(Long userId, String query);

    Mono<Artist> findByUserIdAndName(Long userId, String name);

    Mono<Long> countByUserId(Long userId);

    @Query("SELECT id, name FROM artists WHERE user_id = :userId ORDER BY name ASC")
    Flux<Artist> findSimpleByUserId(Long userId);
}
