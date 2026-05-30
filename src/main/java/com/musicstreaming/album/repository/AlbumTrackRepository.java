package com.musicstreaming.album.repository;

import com.musicstreaming.album.entity.AlbumTrack;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AlbumTrackRepository extends R2dbcRepository<AlbumTrack, Long> {

    Flux<AlbumTrack> findByAlbumIdOrderByPosition(Long albumId);

    Mono<Void> deleteByAlbumId(Long albumId);

    Mono<Long> countByAlbumId(Long albumId);

    Mono<Void> deleteByAlbumIdAndTrackId(Long albumId, Long trackId);

    @Query("SELECT track_id FROM album_tracks")
    Flux<Long> findAllTrackIds();
}
