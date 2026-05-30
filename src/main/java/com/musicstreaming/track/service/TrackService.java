package com.musicstreaming.track.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.musicstreaming.album.repository.AlbumRepository;
import com.musicstreaming.artist.dto.ArtistDTO;
import com.musicstreaming.artist.service.ArtistLinkService;
import com.musicstreaming.auth.service.StorageService;
import com.musicstreaming.common.cache.CoverService;
import com.musicstreaming.common.dto.PageResponse;
import com.musicstreaming.common.exception.StorageLimitExceededException;
import com.musicstreaming.common.service.ReactiveFileService;
import com.musicstreaming.common.util.FileUtils;
import com.musicstreaming.common.util.OwnershipValidator;
import com.musicstreaming.common.util.RangeHeaderParser;
import com.musicstreaming.common.util.ResponseHeaderHelper;
import com.musicstreaming.common.util.SortHelper;
import com.musicstreaming.track.dto.TrackDTO;
import com.musicstreaming.track.entity.Track;
import com.musicstreaming.track.repository.TrackArtistRepository;
import com.musicstreaming.track.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ZeroCopyHttpOutputMessage;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class TrackService {

    private static final Logger log = LoggerFactory.getLogger(TrackService.class);

    private static final Map<String, String> TRACK_SORT_MAPPING = Map.of(
            "artist", "title",
            "album", "album",
            "year", "release_date",
            "duration", "duration",
            "title", "title"
    );

    private final TrackRepository trackRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final AlbumRepository albumRepository;
    private final ArtistLinkService artistLinkService;
    private final StorageService storageService;
    private final ReactiveFileService reactiveFileService;
    private final CoverService coverService;
    private final Cache<Long, byte[]> trackCoverCache;
    private final Cache<Long, FileMetadata> fileMetadataCache;

    public record FileMetadata(long size, String mimeType) {
    }

    private record TrackQueryResult(Flux<Track> content, Mono<Long> total) {
    }

    public Mono<TrackDTO> uploadTrack(String title, List<Long> artistIds, String album,
                                      Integer duration, FilePart file,
                                      FilePart cover, Long userId, LocalDate releaseDate) {
        long fileSize = file.headers().getContentLength();

        return storageService.checkLimit(userId, fileSize)
                .flatMap(canUpload -> {
                    if (!canUpload) {
                        return Mono.error(new StorageLimitExceededException(
                                "Storage limit exceeded. Cannot upload " + (fileSize / 1024 / 1024) + " MB."));
                    }

                    AtomicLong actualSize = new AtomicLong(fileSize);
                    return reactiveFileService.uploadFileWithSize(file, userId, "tracks", actualSize)
                            .flatMap(relativePath -> {
                                Track track = createTrackFromUpload(title, album, duration,
                                        relativePath, actualSize.get(), userId, releaseDate);

                                if (cover != null) {
                                    return reactiveFileService.uploadCover(cover, userId, "covers")
                                            .flatMap(coverPath -> {
                                                track.setCoverPath(coverPath);
                                                return trackRepository.save(track);
                                            });
                                }
                                return trackRepository.save(track);
                            })
                            .flatMap(track -> artistLinkService.saveTrackArtists(track.getId(), artistIds).thenReturn(track))
                            .flatMap(this::trackToDtoWithCover)
                            .doOnSuccess(t -> log.info("Track uploaded: {} with {} artists", t.getTitle(), t.getArtists().size()));
                });
    }

    public Mono<TrackDTO> getTrackMetadata(Long trackId, Long userId) {
        return OwnershipValidator.requireOwnership(
                        trackRepository.findById(trackId), userId, "Track", trackId, Track::getUserId)
                .flatMap(this::trackToDtoWithCover);
    }

    public Mono<PageResponse<TrackDTO>> getUserTracks(Long userId, int page, int size, String search, List<Long> artistIds, List<Long> albumIds, String sortBy, String sortDirection) {
        boolean sortByArtist = "artist".equalsIgnoreCase(sortBy);
        String dir = sortDirection != null ? sortDirection.toLowerCase() : "asc";
        Sort sort = sortByArtist ? Sort.unsorted() : SortHelper.build(sortBy, sortDirection, TRACK_SORT_MAPPING, "title");
        Pageable pageable = PageRequest.of(page, size, sort);

        boolean hasSearch = search != null && !search.trim().isEmpty();
        boolean hasArtistIds = artistIds != null && !artistIds.isEmpty();
        boolean hasAlbumIds = albumIds != null && !albumIds.isEmpty();
        String query = hasSearch ? search.trim().replace(" ", "") : null;

        TrackQueryResult result = resolveTrackQuery(userId, query, artistIds, albumIds,
                hasSearch, hasArtistIds, hasAlbumIds, sortByArtist, dir, pageable);

        return result.content().concatMap(this::trackToDtoWithCover).collectList()
                .zipWith(result.total(), (tracks, total) -> PageResponse.of(tracks, total, page, size));
    }

    public Mono<Long> getUserTrackCount(Long userId) {
        return trackRepository.countByUserId(userId);
    }

    public Mono<TrackDTO> updateTrack(Long trackId, String title, List<Long> artistIds,
                                      String album, LocalDate releaseDate,
                                      FilePart cover, Long userId) {
        return OwnershipValidator.requireOwnership(
                        trackRepository.findById(trackId), userId, "Track", trackId, Track::getUserId)
                .flatMap(track -> {
                    applyTrackUpdates(track, title, album, releaseDate);

                    Mono<Track> saveMono;
                    if (cover != null) {
                        saveMono = reactiveFileService.replaceCover(
                                cover, userId, "covers", track.getCoverPath(), trackId, trackCoverCache)
                                .flatMap(newCoverPath -> {
                                    track.setCoverPath(newCoverPath);
                                    return trackRepository.save(track);
                                });
                    } else {
                        saveMono = trackRepository.save(track);
                    }

                    return saveMono.flatMap(savedTrack -> {
                        if (artistIds != null && !artistIds.isEmpty()) {
                            return trackArtistRepository.deleteByTrackId(trackId)
                                    .then(artistLinkService.saveTrackArtists(trackId, artistIds))
                                    .thenReturn(savedTrack);
                        }
                        return Mono.just(savedTrack);
                    });
                })
                .flatMap(this::trackToDtoWithCover)
                .doOnSuccess(t -> log.info("Track updated: {} (id={})", t.getTitle(), t.getId()));
    }

    public Mono<Void> deleteTrack(Long trackId, Long userId) {
        return OwnershipValidator.requireOwnership(
                        trackRepository.findById(trackId), userId, "Track", trackId, Track::getUserId)
                .flatMap(track -> {
                    Path fullPath = reactiveFileService.resolvePath(track.getFilePath());
                    return trackArtistRepository.deleteByTrackId(trackId)
                            .then(reactiveFileService.deleteFile(fullPath))
                            .then(deleteCoverIfOrphaned(track, trackId))
                            .then(trackRepository.deleteById(trackId));
                })
                .doOnSuccess(v -> {
                    fileMetadataCache.invalidate(trackId);
                    trackCoverCache.invalidate(trackId);
                    log.info("Track deleted: {}", trackId);
                });
    }

    public Mono<Void> downloadTrack(Long trackId, Long userId, ServerHttpResponse response) {
        return getTrackMetadata(trackId, userId)
                .flatMap(track -> getTrackFilePath(trackId, userId)
                        .flatMap(path -> {
                            String ext = FileUtils.getExtension(path.toString());
                            String filename = track.getTitle() + "." + ext;
                            long fileSize = path.toFile().length();
                            ResponseHeaderHelper.setDownloadHeaders(response, filename, fileSize);

                            if (response instanceof ZeroCopyHttpOutputMessage zeroCopy) {
                                return zeroCopy.writeWith(path, 0, fileSize);
                            }
                            return response.writeWith(reactiveFileService.readFile(path, 0, fileSize - 1, true));
                        }))
                .doOnSuccess(v -> log.info("Download completed for track {}", trackId))
                .doOnError(e -> log.error("Download error for track {}: {}", trackId, e.getMessage()));
    }

    public Mono<Void> streamTrack(Long trackId, Long userId, String rangeHeader, ServerHttpResponse response) {
        log.debug("Stream request for track {} with Range: {}", trackId, rangeHeader);

        return getFileMetadata(trackId, userId)
                .flatMap(meta -> {
                    response.getHeaders().setContentType(MediaType.parseMediaType(meta.mimeType()));
                    response.getHeaders().add("Accept-Ranges", "bytes");
                    response.getHeaders().add("Cache-Control", "public, max-age=3600");

                    if (rangeHeader == null || rangeHeader.isEmpty()) {
                        return streamFullContent(trackId, meta.size(), response);
                    }

                    Optional<long[]> parsed = RangeHeaderParser.parseOptional(rangeHeader, meta.size());
                    if (parsed.isEmpty()) {
                        response.setStatusCode(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
                        return Mono.empty();
                    }

                    long[] range = parsed.get();
                    return streamPartialContent(trackId, range[0], range[1], meta.size(), response);
                })
                .doOnSuccess(v -> log.info("Stream completed for track {}", trackId))
                .doOnCancel(() -> log.info("Stream cancelled by client for track {}", trackId))
                .doOnError(e -> log.error("Stream error for track {}: {}", trackId, e.getMessage()));
    }

    private Mono<Void> streamFullContent(Long trackId, long size, ServerHttpResponse response) {
        response.getHeaders().setContentLength(size);
        return getTrackFilePath(trackId, null)
                .flatMap(path -> {
                    if (response instanceof ZeroCopyHttpOutputMessage zeroCopy) {
                        return zeroCopy.writeWith(path, 0, size);
                    }
                    return response.writeWith(reactiveFileService.readFile(path, 0, size - 1, true));
                });
    }

    private Mono<Void> streamPartialContent(Long trackId, long start, long end, long totalSize, ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.PARTIAL_CONTENT);
        long contentLength = end - start + 1;
        response.getHeaders().setContentLength(contentLength);
        response.getHeaders().add("Content-Range",
                "bytes " + start + "-" + end + "/" + totalSize);

        boolean sequential = (start == 0);
        return getTrackFilePath(trackId, null)
                .flatMap(path -> response.writeWith(
                        reactiveFileService.readFile(path, start, end, sequential)));
    }

    private Track createTrackFromUpload(String title, String album, Integer duration,
                                         String relativePath, Long fileSize, Long userId,
                                         LocalDate releaseDate) {
        Track track = new Track();
        track.setTitle(title);
        track.setAlbum(album);
        track.setDuration(duration);
        track.setFilePath(relativePath);
        track.setFileSize(fileSize);
        track.setUserId(userId);
        track.setReleaseDate(releaseDate);
        track.setCreatedAt(LocalDateTime.now());
        track.setUpdatedAt(LocalDateTime.now());
        return track;
    }

    private void applyTrackUpdates(Track track, String title, String album, LocalDate releaseDate) {
        if (title != null && !title.trim().isEmpty()) {
            track.setTitle(title.trim());
        }
        if (album != null) {
            track.setAlbum(album.trim());
        }
        if (releaseDate != null) {
            track.setReleaseDate(releaseDate);
        }
        track.setUpdatedAt(LocalDateTime.now());
    }

    private TrackQueryResult resolveTrackQuery(Long userId, String query, List<Long> artistIds,
                                                List<Long> albumIds, boolean hasSearch,
                                                boolean hasArtistIds, boolean hasAlbumIds,
                                                boolean sortByArtist, String dir, Pageable pageable) {
        if (hasSearch && hasArtistIds && hasAlbumIds) {
            return new TrackQueryResult(
                    sortByArtist
                            ? trackRepository.searchByUserIdAndArtistIdsAndAlbumIdsOrderByArtist(userId, query, artistIds, albumIds, dir, pageable)
                            : trackRepository.searchByUserIdAndArtistIdsAndAlbumIds(userId, query, artistIds, albumIds, pageable),
                    trackRepository.countSearchByUserIdAndArtistIdsAndAlbumIds(userId, query, artistIds, albumIds));
        } else if (hasSearch && hasArtistIds) {
            return new TrackQueryResult(
                    sortByArtist
                            ? trackRepository.searchByUserIdAndArtistIdsOrderByArtist(userId, query, artistIds, dir, pageable)
                            : trackRepository.searchByUserIdAndArtistIds(userId, query, artistIds, pageable),
                    trackRepository.countSearchByUserIdAndArtistIds(userId, query, artistIds));
        } else if (hasSearch && hasAlbumIds) {
            return new TrackQueryResult(
                    sortByArtist
                            ? trackRepository.searchByUserIdAndAlbumIdsOrderByArtist(userId, query, albumIds, dir, pageable)
                            : trackRepository.searchByUserIdAndAlbumIds(userId, query, albumIds, pageable),
                    trackRepository.countSearchByUserIdAndAlbumIds(userId, query, albumIds));
        } else if (hasSearch) {
            return new TrackQueryResult(
                    sortByArtist
                            ? trackRepository.searchTracksByUserOrderByArtist(userId, query, dir, pageable)
                            : trackRepository.searchTracksByUser(userId, query, pageable),
                    trackRepository.countSearchTracksByUser(userId, query));
        } else if (hasArtistIds && hasAlbumIds) {
            return new TrackQueryResult(
                    sortByArtist
                            ? trackRepository.findByUserIdAndArtistIdsAndAlbumIdsOrderByArtist(userId, artistIds, albumIds, dir, pageable)
                            : trackRepository.findByUserIdAndArtistIdsAndAlbumIds(userId, artistIds, albumIds, pageable),
                    trackRepository.countByUserIdAndArtistIdsAndAlbumIds(userId, artistIds, albumIds));
        } else if (hasArtistIds) {
            return new TrackQueryResult(
                    sortByArtist
                            ? trackRepository.findByUserIdAndArtistIdsOrderByArtist(userId, artistIds, dir, pageable)
                            : trackRepository.findByUserIdAndArtistIds(userId, artistIds, pageable),
                    trackRepository.countByUserIdAndArtistIds(userId, artistIds));
        } else if (hasAlbumIds) {
            return new TrackQueryResult(
                    sortByArtist
                            ? trackRepository.findByUserIdAndAlbumIdsOrderByArtist(userId, albumIds, dir, pageable)
                            : trackRepository.findByUserIdAndAlbumIds(userId, albumIds, pageable),
                    trackRepository.countByUserIdAndAlbumIds(userId, albumIds));
        } else {
            return new TrackQueryResult(
                    sortByArtist
                            ? trackRepository.findByUserIdOrderByArtist(userId, dir, pageable)
                            : trackRepository.findByUserId(userId, pageable),
                    trackRepository.countByUserId(userId));
        }
    }

    private Mono<Void> deleteCoverIfOrphaned(Track track, Long trackId) {
        if (track.getCoverPath() == null) {
            return Mono.empty();
        }
        String coverPath = track.getCoverPath();
        return Mono.zip(
                trackRepository.countByCoverPathAndIdNot(coverPath, trackId),
                albumRepository.countByCoverPath(coverPath),
                Long::sum
        ).flatMap(refCount -> {
            if (refCount == 0) {
                return reactiveFileService.deleteFile(reactiveFileService.resolvePath(coverPath));
            }
            return Mono.empty();
        });
    }

    private Mono<Path> getTrackFilePath(Long trackId, Long userId) {
        Mono<Track> trackMono = userId != null
                ? OwnershipValidator.requireOwnership(
                        trackRepository.findById(trackId), userId, "Track", trackId, Track::getUserId)
                : trackRepository.findById(trackId)
                        .switchIfEmpty(Mono.error(new com.musicstreaming.common.exception.ResourceNotFoundException("Track", trackId)));
        return trackMono.map(track -> reactiveFileService.resolvePath(track.getFilePath()));
    }

    private Mono<FileMetadata> getFileMetadata(Long trackId, Long userId) {
        FileMetadata cached = fileMetadataCache.getIfPresent(trackId);
        if (cached != null) {
            return Mono.just(cached);
        }
        return getTrackFilePath(trackId, userId).flatMap(path ->
                Mono.fromCallable(() -> {
                    long size = FileUtils.size(path);
                    String mime = FileUtils.probeContentType(path);
                    if (mime == null) mime = "audio/mpeg";
                    FileMetadata meta = new FileMetadata(size, mime);
                    fileMetadataCache.put(trackId, meta);
                    return meta;
                }).subscribeOn(Schedulers.boundedElastic())
        );
    }

    private Mono<TrackDTO> trackToDtoWithCover(Track track) {
        Mono<List<ArtistDTO>> artistsMono = artistLinkService.getTrackArtists(track.getId());

        return coverService.toBase64DataUri(track.getCoverPath(), track.getId(), trackCoverCache)
                .zipWith(artistsMono)
                .map(tuple -> {
                    TrackDTO dto = TrackDTO.fromEntity(track, tuple.getT1());
                    dto.setArtists(tuple.getT2());
                    return dto;
                });
    }
}
