package com.musicstreaming.track.repository;

import com.musicstreaming.track.entity.Track;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TrackRepository extends R2dbcRepository<Track, Long> {

    Flux<Track> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT * FROM tracks WHERE user_id = :userId AND (title ILIKE :query OR album ILIKE :query)")
    Flux<Track> searchTracksByUser(Long userId, String query);

    Mono<Long> countByUserId(Long userId);

    @Query("SELECT COUNT(*) FROM tracks WHERE cover_path = :coverPath AND id != :trackId")
    Mono<Long> countByCoverPathAndIdNot(String coverPath, Long trackId);
}