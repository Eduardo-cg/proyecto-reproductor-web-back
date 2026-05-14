package com.musicstreaming.domain.repository;

import com.musicstreaming.domain.model.PlaylistTrack;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PlaylistTrackRepository extends R2dbcRepository<PlaylistTrack, Long> {

    Flux<PlaylistTrack> findByPlaylistIdOrderByPositionAsc(Long playlistId);

    Mono<PlaylistTrack> findByPlaylistIdAndTrackId(Long playlistId, Long trackId);

    Mono<Void> deleteByPlaylistIdAndTrackId(Long playlistId, Long trackId);

    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM playlist_tracks WHERE playlist_id = :playlistId")
    Mono<Integer> getNextPosition(Long playlistId);

    Mono<Void> deleteByPlaylistId(Long playlistId);
}