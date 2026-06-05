package com.musicstreaming.track.repository;

import com.musicstreaming.track.entity.TrackArtist;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

public interface TrackArtistRepository extends R2dbcRepository<TrackArtist, Long> {

    Flux<TrackArtist> findByTrackIdOrderByPosition(Long trackId);

    Flux<TrackArtist> findByArtistId(Long artistId);

    Flux<TrackArtist> findByArtistId(Long artistId, Pageable pageable);

    Mono<Void> deleteByTrackId(Long trackId);

    Mono<Void> deleteByArtistId(Long artistId);

    Mono<Long> countByTrackId(Long trackId);

    @Query("SELECT COUNT(*) FROM track_artists WHERE artist_id = :artistId")
    Mono<Long> countByArtistId(Long artistId);

    @Query("SELECT * FROM track_artists WHERE track_id IN (:trackIds) ORDER BY track_id, position")
    Flux<TrackArtist> findByTrackIdIn(@Param("trackIds") Collection<Long> trackIds);
}
