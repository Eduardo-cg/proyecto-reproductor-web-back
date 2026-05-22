package com.musicstreaming.domain.repository;

import com.musicstreaming.domain.model.AlbumTrack;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AlbumTrackRepository extends R2dbcRepository<AlbumTrack, Long> {

    Flux<AlbumTrack> findByAlbumIdOrderByPosition(Long albumId);

    Mono<Void> deleteByAlbumId(Long albumId);

    Mono<Long> countByAlbumId(Long albumId);
}
