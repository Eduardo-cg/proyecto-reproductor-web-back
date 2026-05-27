package com.musicstreaming.album.repository;

import com.musicstreaming.album.entity.AlbumArtist;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AlbumArtistRepository extends R2dbcRepository<AlbumArtist, Long> {

    Flux<AlbumArtist> findByAlbumIdOrderByPosition(Long albumId);

    Flux<AlbumArtist> findByArtistId(Long artistId);

    Flux<AlbumArtist> findByArtistId(Long artistId, Pageable pageable);

    Mono<Void> deleteByAlbumId(Long albumId);

    Mono<Void> deleteByArtistId(Long artistId);

    Mono<Long> countByAlbumId(Long albumId);

    @Query("SELECT COUNT(*) FROM album_artists WHERE artist_id = :artistId")
    Mono<Long> countByArtistId(Long artistId);
}
