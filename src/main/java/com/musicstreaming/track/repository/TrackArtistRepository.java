package com.musicstreaming.track.repository;

import com.musicstreaming.track.entity.TrackArtist;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TrackArtistRepository extends R2dbcRepository<TrackArtist, Long> {

    Flux<TrackArtist> findByTrackIdOrderByPosition(Long trackId);

    Flux<TrackArtist> findByArtistId(Long artistId);

    Flux<TrackArtist> findByArtistId(Long artistId, Pageable pageable);

    Mono<Void> deleteByTrackId(Long trackId);

    Mono<Void> deleteByArtistId(Long artistId);

    Mono<Long> countByTrackId(Long trackId);

    @Query("SELECT COUNT(*) FROM track_artists WHERE artist_id = :artistId")
    Mono<Long> countByArtistId(Long artistId);
}
