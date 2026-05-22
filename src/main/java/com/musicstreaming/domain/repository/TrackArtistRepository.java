package com.musicstreaming.domain.repository;

import com.musicstreaming.domain.model.TrackArtist;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TrackArtistRepository extends R2dbcRepository<TrackArtist, Long> {

    Flux<TrackArtist> findByTrackIdOrderByPosition(Long trackId);

    Flux<TrackArtist> findByArtistId(Long artistId);

    Mono<Void> deleteByTrackId(Long trackId);

    Mono<Void> deleteByArtistId(Long artistId);

    Mono<Long> countByTrackId(Long trackId);
}
