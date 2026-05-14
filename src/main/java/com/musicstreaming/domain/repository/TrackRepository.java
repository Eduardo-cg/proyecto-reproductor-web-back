package com.musicstreaming.domain.repository;

import com.musicstreaming.domain.model.Track;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TrackRepository extends R2dbcRepository<Track, Long> {

    Flux<Track> findAllBy(Pageable pageable);

    Flux<Track> findByArtist(String artist);

    Flux<Track> findByAlbum(String album);

    @Query("SELECT * FROM tracks WHERE title ILIKE :query OR artist ILIKE :query OR album ILIKE :query")
    Flux<Track> searchTracks(String query);

    Mono<Long> count();
}