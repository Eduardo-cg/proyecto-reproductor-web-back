package com.musicstreaming.track.repository;

import com.musicstreaming.track.entity.Track;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface TrackRepository extends R2dbcRepository<Track, Long> {

    Flux<Track> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT * FROM tracks WHERE user_id = :userId AND (unaccent(title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(album) ILIKE '%' || unaccent(:query) || '%')")
    Flux<Track> searchTracksByUser(Long userId, String query, Pageable pageable);

    @Query("SELECT COUNT(*) FROM tracks WHERE user_id = :userId AND (unaccent(title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(album) ILIKE '%' || unaccent(:query) || '%')")
    Mono<Long> countSearchTracksByUser(Long userId, String query);

    Mono<Long> countByUserId(Long userId);

    @Query("SELECT COUNT(*) FROM tracks WHERE cover_path = :coverPath AND id != :trackId")
    Mono<Long> countByCoverPathAndIdNot(String coverPath, Long trackId);

    @Query("SELECT DISTINCT t.* FROM tracks t JOIN track_artists ta ON t.id = ta.track_id " +
            "WHERE t.user_id = :userId AND ta.artist_id IN (:artistIds)")
    Flux<Track> findByUserIdAndArtistIds(@Param("userId") Long userId, @Param("artistIds") List<Long> artistIds, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT t.id) FROM tracks t JOIN track_artists ta ON t.id = ta.track_id " +
            "WHERE t.user_id = :userId AND ta.artist_id IN (:artistIds)")
    Mono<Long> countByUserIdAndArtistIds(@Param("userId") Long userId, @Param("artistIds") List<Long> artistIds);

    @Query("SELECT DISTINCT t.* FROM tracks t JOIN album_tracks at ON t.id = at.track_id " +
            "WHERE t.user_id = :userId AND at.album_id IN (:albumIds)")
    Flux<Track> findByUserIdAndAlbumIds(@Param("userId") Long userId, @Param("albumIds") List<Long> albumIds, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT t.id) FROM tracks t JOIN album_tracks at ON t.id = at.track_id " +
            "WHERE t.user_id = :userId AND at.album_id IN (:albumIds)")
    Mono<Long> countByUserIdAndAlbumIds(@Param("userId") Long userId, @Param("albumIds") List<Long> albumIds);

    @Query("SELECT DISTINCT t.* FROM tracks t " +
            "JOIN track_artists ta ON t.id = ta.track_id " +
            "JOIN album_tracks at ON t.id = at.track_id " +
            "WHERE t.user_id = :userId AND ta.artist_id IN (:artistIds) AND at.album_id IN (:albumIds)")
    Flux<Track> findByUserIdAndArtistIdsAndAlbumIds(@Param("userId") Long userId, @Param("artistIds") List<Long> artistIds, @Param("albumIds") List<Long> albumIds, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT t.id) FROM tracks t " +
            "JOIN track_artists ta ON t.id = ta.track_id " +
            "JOIN album_tracks at ON t.id = at.track_id " +
            "WHERE t.user_id = :userId AND ta.artist_id IN (:artistIds) AND at.album_id IN (:albumIds)")
    Mono<Long> countByUserIdAndArtistIdsAndAlbumIds(@Param("userId") Long userId, @Param("artistIds") List<Long> artistIds, @Param("albumIds") List<Long> albumIds);

    @Query("SELECT DISTINCT t.* FROM tracks t JOIN track_artists ta ON t.id = ta.track_id " +
            "WHERE t.user_id = :userId AND (unaccent(t.title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(t.album) ILIKE '%' || unaccent(:query) || '%') " +
            "AND ta.artist_id IN (:artistIds)")
    Flux<Track> searchByUserIdAndArtistIds(@Param("userId") Long userId, @Param("query") String query, @Param("artistIds") List<Long> artistIds, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT t.id) FROM tracks t JOIN track_artists ta ON t.id = ta.track_id " +
            "WHERE t.user_id = :userId AND (unaccent(t.title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(t.album) ILIKE '%' || unaccent(:query) || '%') " +
            "AND ta.artist_id IN (:artistIds)")
    Mono<Long> countSearchByUserIdAndArtistIds(@Param("userId") Long userId, @Param("query") String query, @Param("artistIds") List<Long> artistIds);

    @Query("SELECT DISTINCT t.* FROM tracks t JOIN album_tracks at ON t.id = at.track_id " +
            "WHERE t.user_id = :userId AND (unaccent(t.title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(t.album) ILIKE '%' || unaccent(:query) || '%') " +
            "AND at.album_id IN (:albumIds)")
    Flux<Track> searchByUserIdAndAlbumIds(@Param("userId") Long userId, @Param("query") String query, @Param("albumIds") List<Long> albumIds, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT t.id) FROM tracks t JOIN album_tracks at ON t.id = at.track_id " +
            "WHERE t.user_id = :userId AND (unaccent(t.title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(t.album) ILIKE '%' || unaccent(:query) || '%') " +
            "AND at.album_id IN (:albumIds)")
    Mono<Long> countSearchByUserIdAndAlbumIds(@Param("userId") Long userId, @Param("query") String query, @Param("albumIds") List<Long> albumIds);

    @Query("SELECT DISTINCT t.* FROM tracks t " +
            "JOIN track_artists ta ON t.id = ta.track_id " +
            "JOIN album_tracks at ON t.id = at.track_id " +
            "WHERE t.user_id = :userId AND (unaccent(t.title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(t.album) ILIKE '%' || unaccent(:query) || '%') " +
            "AND ta.artist_id IN (:artistIds) AND at.album_id IN (:albumIds)")
    Flux<Track> searchByUserIdAndArtistIdsAndAlbumIds(@Param("userId") Long userId, @Param("query") String query, @Param("artistIds") List<Long> artistIds, @Param("albumIds") List<Long> albumIds, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT t.id) FROM tracks t " +
            "JOIN track_artists ta ON t.id = ta.track_id " +
            "JOIN album_tracks at ON t.id = at.track_id " +
            "WHERE t.user_id = :userId AND (unaccent(t.title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(t.album) ILIKE '%' || unaccent(:query) || '%') " +
            "AND ta.artist_id IN (:artistIds) AND at.album_id IN (:albumIds)")
    Mono<Long> countSearchByUserIdAndArtistIdsAndAlbumIds(@Param("userId") Long userId, @Param("query") String query, @Param("artistIds") List<Long> artistIds, @Param("albumIds") List<Long> albumIds);

    @Query("SELECT t.* FROM tracks t " +
            "LEFT JOIN track_artists ta ON t.id = ta.track_id " +
            "LEFT JOIN artists ar ON ta.artist_id = ar.id " +
            "WHERE t.user_id = :userId " +
            "GROUP BY t.id " +
            "ORDER BY " +
            "CASE WHEN :direction = 'asc' THEN MIN(ar.name) END ASC NULLS LAST, " +
            "CASE WHEN :direction = 'desc' THEN MIN(ar.name) END DESC NULLS LAST")
    Flux<Track> findByUserIdOrderByArtist(@Param("userId") Long userId, @Param("direction") String direction, Pageable pageable);

    @Query("SELECT t.* FROM tracks t " +
            "LEFT JOIN track_artists ta ON t.id = ta.track_id " +
            "LEFT JOIN artists ar ON ta.artist_id = ar.id " +
            "WHERE t.user_id = :userId AND (unaccent(t.title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(t.album) ILIKE '%' || unaccent(:query) || '%') " +
            "GROUP BY t.id " +
            "ORDER BY " +
            "CASE WHEN :direction = 'asc' THEN MIN(ar.name) END ASC NULLS LAST, " +
            "CASE WHEN :direction = 'desc' THEN MIN(ar.name) END DESC NULLS LAST")
    Flux<Track> searchTracksByUserOrderByArtist(@Param("userId") Long userId, @Param("query") String query, @Param("direction") String direction, Pageable pageable);

    @Query("SELECT t.* FROM tracks t " +
            "JOIN track_artists ta ON t.id = ta.track_id " +
            "LEFT JOIN artists ar ON ta.artist_id = ar.id " +
            "WHERE t.user_id = :userId AND ta.artist_id IN (:artistIds) " +
            "GROUP BY t.id " +
            "ORDER BY " +
            "CASE WHEN :direction = 'asc' THEN MIN(ar.name) END ASC NULLS LAST, " +
            "CASE WHEN :direction = 'desc' THEN MIN(ar.name) END DESC NULLS LAST")
    Flux<Track> findByUserIdAndArtistIdsOrderByArtist(@Param("userId") Long userId,
                                                       @Param("artistIds") List<Long> artistIds,
                                                       @Param("direction") String direction,
                                                       Pageable pageable);

    @Query("SELECT t.* FROM tracks t " +
            "JOIN album_tracks at ON t.id = at.track_id " +
            "LEFT JOIN track_artists ta ON t.id = ta.track_id " +
            "LEFT JOIN artists ar ON ta.artist_id = ar.id " +
            "WHERE t.user_id = :userId AND at.album_id IN (:albumIds) " +
            "GROUP BY t.id " +
            "ORDER BY " +
            "CASE WHEN :direction = 'asc' THEN MIN(ar.name) END ASC NULLS LAST, " +
            "CASE WHEN :direction = 'desc' THEN MIN(ar.name) END DESC NULLS LAST")
    Flux<Track> findByUserIdAndAlbumIdsOrderByArtist(@Param("userId") Long userId,
                                                      @Param("albumIds") List<Long> albumIds,
                                                      @Param("direction") String direction,
                                                      Pageable pageable);

    @Query("SELECT t.* FROM tracks t " +
            "JOIN track_artists ta ON t.id = ta.track_id " +
            "JOIN album_tracks at ON t.id = at.track_id " +
            "LEFT JOIN artists ar ON ta.artist_id = ar.id " +
            "WHERE t.user_id = :userId AND ta.artist_id IN (:artistIds) AND at.album_id IN (:albumIds) " +
            "GROUP BY t.id " +
            "ORDER BY " +
            "CASE WHEN :direction = 'asc' THEN MIN(ar.name) END ASC NULLS LAST, " +
            "CASE WHEN :direction = 'desc' THEN MIN(ar.name) END DESC NULLS LAST")
    Flux<Track> findByUserIdAndArtistIdsAndAlbumIdsOrderByArtist(@Param("userId") Long userId,
                                                                  @Param("artistIds") List<Long> artistIds,
                                                                  @Param("albumIds") List<Long> albumIds,
                                                                  @Param("direction") String direction,
                                                                  Pageable pageable);

    @Query("SELECT t.* FROM tracks t " +
            "JOIN track_artists ta ON t.id = ta.track_id " +
            "LEFT JOIN artists ar ON ta.artist_id = ar.id " +
            "WHERE t.user_id = :userId AND (unaccent(t.title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(t.album) ILIKE '%' || unaccent(:query) || '%') " +
            "AND ta.artist_id IN (:artistIds) " +
            "GROUP BY t.id " +
            "ORDER BY " +
            "CASE WHEN :direction = 'asc' THEN MIN(ar.name) END ASC NULLS LAST, " +
            "CASE WHEN :direction = 'desc' THEN MIN(ar.name) END DESC NULLS LAST")
    Flux<Track> searchByUserIdAndArtistIdsOrderByArtist(@Param("userId") Long userId,
                                                         @Param("query") String query,
                                                         @Param("artistIds") List<Long> artistIds,
                                                         @Param("direction") String direction,
                                                         Pageable pageable);

    @Query("SELECT t.* FROM tracks t " +
            "JOIN album_tracks at ON t.id = at.track_id " +
            "LEFT JOIN track_artists ta ON t.id = ta.track_id " +
            "LEFT JOIN artists ar ON ta.artist_id = ar.id " +
            "WHERE t.user_id = :userId AND (unaccent(t.title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(t.album) ILIKE '%' || unaccent(:query) || '%') " +
            "AND at.album_id IN (:albumIds) " +
            "GROUP BY t.id " +
            "ORDER BY " +
            "CASE WHEN :direction = 'asc' THEN MIN(ar.name) END ASC NULLS LAST, " +
            "CASE WHEN :direction = 'desc' THEN MIN(ar.name) END DESC NULLS LAST")
    Flux<Track> searchByUserIdAndAlbumIdsOrderByArtist(@Param("userId") Long userId,
                                                        @Param("query") String query,
                                                        @Param("albumIds") List<Long> albumIds,
                                                        @Param("direction") String direction,
                                                        Pageable pageable);

    @Query("SELECT t.* FROM tracks t " +
            "JOIN track_artists ta ON t.id = ta.track_id " +
            "JOIN album_tracks at ON t.id = at.track_id " +
            "LEFT JOIN artists ar ON ta.artist_id = ar.id " +
            "WHERE t.user_id = :userId AND (unaccent(t.title) ILIKE '%' || unaccent(:query) || '%' OR unaccent(t.album) ILIKE '%' || unaccent(:query) || '%') " +
            "AND ta.artist_id IN (:artistIds) AND at.album_id IN (:albumIds) " +
            "GROUP BY t.id " +
            "ORDER BY " +
            "CASE WHEN :direction = 'asc' THEN MIN(ar.name) END ASC NULLS LAST, " +
            "CASE WHEN :direction = 'desc' THEN MIN(ar.name) END DESC NULLS LAST")
    Flux<Track> searchByUserIdAndArtistIdsAndAlbumIdsOrderByArtist(@Param("userId") Long userId,
                                                                    @Param("query") String query,
                                                                    @Param("artistIds") List<Long> artistIds,
                                                                    @Param("albumIds") List<Long> albumIds,
                                                                    @Param("direction") String direction,
                                                                    Pageable pageable);
}