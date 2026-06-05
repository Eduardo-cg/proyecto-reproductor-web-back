package com.musicstreaming.album.repository;

import com.musicstreaming.album.entity.AlbumArtist;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

public interface AlbumArtistRepository extends R2dbcRepository<AlbumArtist, Long> {

    Flux<AlbumArtist> findByAlbumIdOrderByPosition(Long albumId);

    Flux<AlbumArtist> findByArtistId(Long artistId);

    Flux<AlbumArtist> findByArtistId(Long artistId, Pageable pageable);

    Mono<Void> deleteByAlbumId(Long albumId);

    Mono<Void> deleteByArtistId(Long artistId);

    Mono<Long> countByAlbumId(Long albumId);

    @Query("SELECT COUNT(*) FROM album_artists WHERE artist_id = :artistId")
    Mono<Long> countByArtistId(Long artistId);

    @Query("SELECT * FROM album_artists WHERE album_id IN (:albumIds) ORDER BY album_id, position")
    Flux<AlbumArtist> findByAlbumIdIn(@Param("albumIds") Collection<Long> albumIds);
}
