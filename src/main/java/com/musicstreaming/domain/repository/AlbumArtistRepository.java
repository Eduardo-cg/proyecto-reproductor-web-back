package com.musicstreaming.domain.repository;

import com.musicstreaming.domain.model.AlbumArtist;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AlbumArtistRepository extends R2dbcRepository<AlbumArtist, Long> {

    Flux<AlbumArtist> findByAlbumIdOrderByPosition(Long albumId);

    Flux<AlbumArtist> findByArtistId(Long artistId);

    Mono<Void> deleteByAlbumId(Long albumId);

    Mono<Void> deleteByArtistId(Long artistId);

    Mono<Long> countByAlbumId(Long albumId);
}
