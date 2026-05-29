package com.musicstreaming.album.repository;

import com.musicstreaming.album.entity.Album;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface AlbumRepository extends R2dbcRepository<Album, Long> {

    Flux<Album> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT COUNT(*) FROM albums WHERE user_id = :userId")
    Mono<Long> countByUserId(Long userId);

    @Query("SELECT COUNT(*) FROM albums WHERE cover_path = :coverPath")
    Mono<Long> countByCoverPath(String coverPath);

    @Query("SELECT id, title FROM albums WHERE user_id = :userId ORDER BY title ASC")
    Flux<Album> findSimpleByUserId(Long userId);

    @Query("SELECT DISTINCT a.id, a.title FROM albums a " +
            "JOIN album_artists aa ON a.id = aa.album_id " +
            "WHERE a.user_id = :userId AND aa.artist_id IN (:artistIds) ORDER BY a.title ASC")
    Flux<Album> findSimpleByUserIdAndArtistIds(@Param("userId") Long userId, @Param("artistIds") List<Long> artistIds);

    @Query("SELECT * FROM albums WHERE user_id = :userId AND title ILIKE CONCAT('%', :query, '%')")
    Flux<Album> searchAlbumsByUser(Long userId, String query, Pageable pageable);

    @Query("SELECT COUNT(*) FROM albums WHERE user_id = :userId AND title ILIKE CONCAT('%', :query, '%')")
    Mono<Long> countSearchAlbumsByUser(Long userId, String query);

    @Query("SELECT DISTINCT a.* FROM albums a " +
            "JOIN album_artists aa ON a.id = aa.album_id " +
            "WHERE a.user_id = :userId AND aa.artist_id IN (:artistIds)")
    Flux<Album> findByUserIdAndArtistIds(@Param("userId") Long userId, @Param("artistIds") List<Long> artistIds, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT a.id) FROM albums a " +
            "JOIN album_artists aa ON a.id = aa.album_id " +
            "WHERE a.user_id = :userId AND aa.artist_id IN (:artistIds)")
    Mono<Long> countByUserIdAndArtistIds(@Param("userId") Long userId, @Param("artistIds") List<Long> artistIds);

    @Query("SELECT DISTINCT a.* FROM albums a " +
            "JOIN album_artists aa ON a.id = aa.album_id " +
            "WHERE a.user_id = :userId AND a.title ILIKE CONCAT('%', :query, '%') AND aa.artist_id IN (:artistIds)")
    Flux<Album> searchByUserIdAndArtistIds(@Param("userId") Long userId, @Param("query") String query, @Param("artistIds") List<Long> artistIds, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT a.id) FROM albums a " +
            "JOIN album_artists aa ON a.id = aa.album_id " +
            "WHERE a.user_id = :userId AND a.title ILIKE CONCAT('%', :query, '%') AND aa.artist_id IN (:artistIds)")
    Mono<Long> countSearchByUserIdAndArtistIds(@Param("userId") Long userId, @Param("query") String query, @Param("artistIds") List<Long> artistIds);
}
