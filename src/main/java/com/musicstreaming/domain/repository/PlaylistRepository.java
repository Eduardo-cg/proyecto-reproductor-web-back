package com.musicstreaming.domain.repository;

import com.musicstreaming.domain.model.Playlist;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PlaylistRepository extends R2dbcRepository<Playlist, Long> {

    Flux<Playlist> findByUserId(Long userId);

    Flux<Playlist> findByUserIdAndIsPublicTrue(Long userId);

    Mono<Playlist> findByIdAndUserId(Long id, Long userId);

    @Query("SELECT * FROM playlists WHERE (user_id = :userId AND is_public = true) OR is_public = true")
    Flux<Playlist> findPublicAndUserPlaylists(Long userId);
}