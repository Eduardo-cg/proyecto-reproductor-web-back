package com.musicstreaming.album.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.musicstreaming.album.dto.AlbumDTO;
import com.musicstreaming.album.dto.AlbumMetadata;
import com.musicstreaming.album.dto.AlbumWithTracksDTO;
import com.musicstreaming.album.entity.Album;
import com.musicstreaming.album.entity.AlbumTrack;
import com.musicstreaming.album.repository.AlbumArtistRepository;
import com.musicstreaming.album.repository.AlbumRepository;
import com.musicstreaming.album.repository.AlbumTrackRepository;
import com.musicstreaming.artist.dto.ArtistDTO;
import com.musicstreaming.artist.service.ArtistLinkService;
import com.musicstreaming.auth.service.StorageService;
import com.musicstreaming.common.cache.CoverService;
import com.musicstreaming.common.dto.PageResponse;
import com.musicstreaming.common.exception.StorageLimitExceededException;
import com.musicstreaming.common.service.ReactiveFileService;
import com.musicstreaming.common.service.ZipDownloadService;
import com.musicstreaming.common.util.FileUtils;
import com.musicstreaming.common.util.FilenameSanitizer;
import com.musicstreaming.common.util.OwnershipValidator;
import com.musicstreaming.common.util.SortHelper;
import com.musicstreaming.track.dto.TrackDTO;
import com.musicstreaming.track.dto.TrackMetadata;
import com.musicstreaming.track.entity.Track;
import com.musicstreaming.track.repository.TrackArtistRepository;
import com.musicstreaming.track.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlbumService {

    private static final Map<String, String> ALBUM_SORT_MAPPING = Map.of(
            "artist", "title",
            "year", "release_date",
            "title", "title"
    );

    private final AlbumRepository albumRepository;
    private final AlbumTrackRepository albumTrackRepository;
    private final TrackRepository trackRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final AlbumArtistRepository albumArtistRepository;
    private final ArtistLinkService artistLinkService;
    private final StorageService storageService;
    private final ReactiveFileService reactiveFileService;
    private final CoverService coverService;
    private final ZipDownloadService zipDownloadService;
    private final TransactionalOperator transactionalOperator;
    private final Cache<Long, byte[]> albumCoverCache;
    private final Cache<Long, byte[]> trackCoverCache;

    private record AlbumStats(List<ArtistDTO> artists, int trackCount, long totalSize) {
    }

    private record AlbumTracksData(List<TrackDTO> tracks, List<ArtistDTO> artists,
                                   long trackCount, String cover, long totalSize) {
    }

    public Mono<AlbumDTO> createAlbum(AlbumMetadata meta, FilePart cover, Long userId) {
        Album album = new Album();
        album.setTitle(meta.title());
        album.setReleaseDate(meta.releaseDate());
        album.setUserId(userId);
        album.setCreatedAt(LocalDateTime.now());
        album.setUpdatedAt(LocalDateTime.now());

        Mono<Album> saveAlbumMono;

        if (cover != null) {
            saveAlbumMono = reactiveFileService.uploadCover(cover, userId, "covers")
                    .flatMap(coverPath -> {
                        album.setCoverPath(coverPath);
                        return albumRepository.save(album);
                    });
        } else {
            saveAlbumMono = albumRepository.save(album);
        }

        return saveAlbumMono
                .flatMap(savedAlbum -> artistLinkService.saveAlbumArtists(savedAlbum.getId(), meta.artistIds()).thenReturn(savedAlbum))
                .flatMap(this::albumToDto);
    }

    public Mono<AlbumDTO> updateAlbum(Long albumId, AlbumMetadata meta, FilePart cover, Long userId) {
        return OwnershipValidator.requireOwnership(
                        albumRepository.findById(albumId), userId, "Album", albumId, Album::getUserId)
                .flatMap(album -> {
                    String oldTitle = album.getTitle();
                    if (meta.title() != null && !meta.title().trim().isEmpty()) {
                        album.setTitle(meta.title().trim());
                    }
                    if (meta.releaseDate() != null) {
                        album.setReleaseDate(meta.releaseDate());
                    }
                    album.setUpdatedAt(LocalDateTime.now());

                    String newTitle = album.getTitle();
                    boolean titleChanged = !oldTitle.equals(newTitle);

                    Mono<Album> saveMono;
                    if (cover != null) {
                        saveMono = reactiveFileService.replaceCover(
                                        cover, userId, "covers", album.getCoverPath(), albumId, albumCoverCache)
                                .flatMap(newCoverPath -> {
                                    album.setCoverPath(newCoverPath);
                                    return albumRepository.save(album);
                                });
                    } else {
                        saveMono = albumRepository.save(album);
                    }

                    Mono<Album> postProcess = saveMono.flatMap(savedAlbum -> {
                        Mono<Void> artistsUpdate = (meta.artistIds() != null && !meta.artistIds().isEmpty())
                                ? albumArtistRepository.deleteByAlbumId(albumId)
                                  .then(artistLinkService.saveAlbumArtists(albumId, meta.artistIds()))
                                : Mono.empty();

                        Mono<Void> tracksUpdate = titleChanged
                                ? albumTrackRepository.findByAlbumIdOrderByPosition(albumId)
                                  .flatMap(at -> trackRepository.findById(at.getTrackId())
                                                 .flatMap(track -> {
                                                     track.setAlbum(newTitle);
                                                     track.setUpdatedAt(LocalDateTime.now());
                                                     return trackRepository.save(track);
                                                 }))
                                  .then()
                                : Mono.empty();

                        return artistsUpdate.then(tracksUpdate).thenReturn(savedAlbum);
                    });

                    return transactionalOperator.transactional(postProcess);
                })
                .flatMap(this::albumToDto)
                .doOnSuccess(a -> log.info("Album updated userId={} title={} id={}", userId, a.getTitle(), a.getId()));
    }

    public Mono<PageResponse<AlbumDTO>> getUserAlbums(Long userId, int page, int size, String search, List<Long> artistIds, String sortBy, String sortDirection) {
        boolean sortByArtist = "artist".equalsIgnoreCase(sortBy);
        Sort sort = sortByArtist ? Sort.unsorted() : SortHelper.build(sortBy, sortDirection, ALBUM_SORT_MAPPING, "title");
        Pageable pageable = PageRequest.of(page, size, sort);

        return findAlbumsPaged(userId, search, artistIds, pageable, sortByArtist, sortDirection, this::albumToDto);
    }

    public Mono<PageResponse<AlbumDTO>> getUserAlbumsList(Long userId, String search, List<Long> artistIds, int page, int size) {
        Sort sort = Sort.by(Sort.Direction.ASC, "title");
        PageRequest pageable = PageRequest.of(page, size, sort);

        return findAlbumsPaged(userId, search, artistIds, pageable, false, null, this::mapToSimpleAlbumDto);
    }

    private Mono<PageResponse<AlbumDTO>> findAlbumsPaged(Long userId, String search, List<Long> artistIds,
                                                         Pageable pageable, boolean sortByArtist, String sortDirection,
                                                         java.util.function.Function<Album, Mono<AlbumDTO>> mapper) {
        boolean hasSearch = search != null && !search.trim().isEmpty();
        boolean hasArtistIds = artistIds != null && !artistIds.isEmpty();
        String query = hasSearch ? search.trim().replace(" ", "") : null;
        String dir = sortDirection != null ? sortDirection.toLowerCase() : "asc";

        Mono<List<AlbumDTO>> content;
        Mono<Long> total;

        if (hasSearch && hasArtistIds) {
            content = (sortByArtist
                    ? albumRepository.searchByUserIdAndArtistIdsOrderByArtist(userId, query, artistIds, dir, pageable)
                    : albumRepository.searchByUserIdAndArtistIds(userId, query, artistIds, pageable))
                    .concatMap(mapper).collectList();
            total = albumRepository.countSearchByUserIdAndArtistIds(userId, query, artistIds);
        } else if (hasSearch) {
            content = (sortByArtist
                    ? albumRepository.searchAlbumsByUserOrderByArtist(userId, query, dir, pageable)
                    : albumRepository.searchAlbumsByUser(userId, query, pageable))
                    .concatMap(mapper).collectList();
            total = albumRepository.countSearchAlbumsByUser(userId, query);
        } else if (hasArtistIds) {
            content = (sortByArtist
                    ? albumRepository.findByUserIdAndArtistIdsOrderByArtist(userId, artistIds, dir, pageable)
                    : albumRepository.findByUserIdAndArtistIds(userId, artistIds, pageable))
                    .concatMap(mapper).collectList();
            total = albumRepository.countByUserIdAndArtistIds(userId, artistIds);
        } else {
            content = (sortByArtist
                    ? albumRepository.findByUserIdOrderByArtist(userId, dir, pageable)
                    : albumRepository.findByUserId(userId, pageable))
                    .concatMap(mapper).collectList();
            total = albumRepository.countByUserId(userId);
        }

        return Mono.zip(content, total, (albums, totalElements) -> PageResponse.of(albums, totalElements, pageable.getPageNumber(), pageable.getPageSize()));
    }

    private Mono<AlbumDTO> mapToSimpleAlbumDto(Album album) {
        AlbumDTO dto = new AlbumDTO();
        dto.setId(album.getId());
        dto.setTitle(album.getTitle());
        return Mono.just(dto);
    }

    public Mono<AlbumWithTracksDTO> getAlbumWithTracks(Long albumId, Long userId) {
        return OwnershipValidator.requireOwnership(
                        albumRepository.findById(albumId), userId, "Album", albumId, Album::getUserId)
                .flatMap(this::enrichWithTracks);
    }

    public Mono<TrackDTO> addTrackToAlbum(Long albumId, TrackMetadata meta, FilePart file, Long userId) {
        long fileSize = file.headers().getContentLength();

        return storageService.checkLimit(userId, fileSize)
                .flatMap(canUpload -> {
                    if (Boolean.FALSE.equals(canUpload)) {
                        return Mono.error(new StorageLimitExceededException(
                                "Storage limit exceeded. Cannot upload " + (fileSize / 1024 / 1024) + " MB."));
                    }

                    return OwnershipValidator.requireOwnership(
                                    albumRepository.findById(albumId), userId, "Album", albumId, Album::getUserId)
                            .flatMap(album -> {
                                AtomicLong actualSize = new AtomicLong(fileSize);
                                Mono<TrackDTO> work = reactiveFileService.uploadFileWithSize(file, userId, "tracks", actualSize)
                                        .flatMap(relativePath -> {
                                            Track track = createTrackForAlbum(album, meta,
                                                    relativePath, actualSize.get(), userId);
                                            return trackRepository.save(track);
                                        })
                                        .flatMap(track -> artistLinkService.saveTrackArtists(track.getId(), meta.artistIds()).thenReturn(track))
                                        .flatMap(track -> resolveNextPosition(albumId, meta.position())
                                                .flatMap(pos -> linkTrackToAlbum(albumId, track.getId(), pos).thenReturn(track)))
                                        .flatMap(this::trackToDtoWithCover);

                                return transactionalOperator.transactional(work)
                                        .doOnSuccess(t -> log.info("Track added to album userId={} albumId={} title={}", userId, albumId, t.getTitle()));
                            });
                });
    }

    private Mono<Integer> resolveNextPosition(Long albumId, Integer requestedPosition) {
        if (requestedPosition != null) {
            return Mono.just(requestedPosition);
        }
        return albumTrackRepository.findByAlbumIdOrderByPosition(albumId)
                .map(AlbumTrack::getPosition)
                .reduce(0, Math::max)
                .map(max -> max + 1);
    }

    public Mono<Void> deleteAlbum(Long albumId, Long userId) {
        return OwnershipValidator.requireOwnership(
                        albumRepository.findById(albumId), userId, "Album", albumId, Album::getUserId)
                .flatMap(album -> {
                    Mono<Void> deleteCover = album.getCoverPath() != null
                            ? reactiveFileService.deleteFile(reactiveFileService.resolvePath(album.getCoverPath()))
                            : Mono.empty();
                    Mono<Void> work = deleteCover
                            .then(albumArtistRepository.deleteByAlbumId(albumId))
                            .then(deleteAlbumData(album));
                    return transactionalOperator.transactional(work);
                });
    }

    public Mono<Void> removeTrackFromAlbum(Long albumId, Long trackId, Long userId) {
        return OwnershipValidator.requireOwnership(
                        albumRepository.findById(albumId), userId, "Album", albumId, Album::getUserId)
                .flatMap(album -> OwnershipValidator.requireOwnership(
                                trackRepository.findById(trackId), userId, "Track", trackId, Track::getUserId)
                        .flatMap(track -> {
                            Mono<Void> deleteFile = Mono.empty();
                            if (track.getFilePath() != null) {
                                deleteFile = reactiveFileService.deleteFile(reactiveFileService.resolvePath(track.getFilePath()));
                            }
                            return deleteFile
                                    .then(trackArtistRepository.deleteByTrackId(trackId))
                                    .then(albumTrackRepository.deleteByAlbumIdAndTrackId(albumId, trackId))
                                    .then(trackRepository.delete(track));
                        }))
                .then();
    }

    public Mono<Void> reorderAlbumTracks(Long albumId, List<Long> trackIds, Long userId) {
        return OwnershipValidator.requireOwnership(
                        albumRepository.findById(albumId), userId, "Album", albumId, Album::getUserId)
                .flatMap(album -> {
                    Mono<Void> work = albumTrackRepository.deleteByAlbumId(albumId)
                            .thenMany(Flux.fromIterable(trackIds)
                                    .index()
                                    .flatMap(tuple -> {
                                        AlbumTrack at = new AlbumTrack();
                                        at.setAlbumId(albumId);
                                        at.setTrackId(tuple.getT2());
                                        at.setPosition(tuple.getT1().intValue() + 1);
                                        at.setCreatedAt(LocalDateTime.now());
                                        return albumTrackRepository.save(at);
                                    }))
                            .then();
                    return transactionalOperator.transactional(work);
                });
    }

    private Track createTrackForAlbum(Album album, TrackMetadata meta,
                                      String relativePath, Long fileSize, Long userId) {
        Track track = new Track();
        track.setTitle(meta.title());
        track.setAlbum(album.getTitle());
        track.setDuration(meta.duration());
        track.setFilePath(relativePath);
        track.setFileSize(fileSize);
        track.setCoverPath(album.getCoverPath());
        track.setUserId(userId);
        track.setReleaseDate(meta.releaseDate());
        track.setCreatedAt(LocalDateTime.now());
        track.setUpdatedAt(LocalDateTime.now());
        return track;
    }

    private Mono<AlbumTrack> linkTrackToAlbum(Long albumId, Long trackId, Integer position) {
        AlbumTrack albumTrack = new AlbumTrack();
        albumTrack.setAlbumId(albumId);
        albumTrack.setTrackId(trackId);
        albumTrack.setPosition(position != null ? position : 1);
        albumTrack.setCreatedAt(LocalDateTime.now());
        return albumTrackRepository.save(albumTrack);
    }

    private Mono<Void> deleteAlbumData(Album album) {
        return albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                .flatMap(at -> albumTrackRepository.countByTrackId(at.getTrackId())
                        .flatMap(albumRefCount -> {
                            if (albumRefCount <= 1) {
                                return trackRepository.findById(at.getTrackId())
                                        .flatMap(this::deleteTrackFully);
                            }
                            return Mono.empty();
                        }))
                .then(albumTrackRepository.deleteByAlbumId(album.getId()))
                .then(albumRepository.deleteById(album.getId()))
                .doOnSuccess(v -> log.info("Album deleted userId={} albumId={}", album.getUserId(), album.getId()));
    }

    private Mono<Void> deleteTrackFully(Track track) {
        Mono<Void> deleteFile = track.getFilePath() != null
                ? reactiveFileService.deleteFile(reactiveFileService.resolvePath(track.getFilePath()))
                : Mono.empty();
        return deleteFile
                .then(trackArtistRepository.deleteByTrackId(track.getId()))
                .then(trackRepository.delete(track))
                .onErrorResume(e -> {
                    log.warn("Failed to fully delete track {} during cascade: {}", track.getId(), e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<AlbumStats> fetchAlbumStats(Long albumId) {
        Mono<List<ArtistDTO>> artistsMono = artistLinkService.getAlbumArtists(albumId);
        Mono<Integer> countMono = albumTrackRepository.countByAlbumId(albumId).map(Long::intValue);
        Mono<Long> totalSizeMono = albumTrackRepository.findByAlbumIdOrderByPosition(albumId)
                .flatMap(at -> trackRepository.findById(at.getTrackId()))
                .map(track -> track.getFileSize() != null ? track.getFileSize() : 0L)
                .reduce(0L, Long::sum);

        return Mono.zip(artistsMono, countMono, totalSizeMono)
                .map(tuple -> new AlbumStats(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    private Mono<AlbumDTO> albumToDto(Album album) {
        return fetchAlbumStats(album.getId())
                .flatMap(stats -> {
                    AlbumDTO dto = new AlbumDTO();
                    dto.setId(album.getId());
                    dto.setTitle(album.getTitle());
                    dto.setArtistsNames(stats.artists());
                    dto.setReleaseDate(album.getReleaseDate());
                    dto.setUserId(album.getUserId());
                    dto.setTrackCount(stats.trackCount());
                    dto.setTotalSize(stats.totalSize());
                    return coverService.toBase64DataUri(album.getCoverPath(), album.getId(), albumCoverCache)
                            .map(cover -> {
                                dto.setCover(cover);
                                return dto;
                            });
                });
    }

    private Mono<AlbumTracksData> fetchAlbumTracksData(Album album) {
        Mono<List<TrackDTO>> tracksMono = albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                .concatMap(at -> trackRepository.findById(at.getTrackId()))
                .concatMap(this::trackToDtoWithCover)
                .collectList();

        Mono<List<ArtistDTO>> artistsMono = artistLinkService.getAlbumArtists(album.getId());
        Mono<Long> countMono = albumTrackRepository.countByAlbumId(album.getId());
        Mono<String> coverMono = coverService.toBase64DataUri(album.getCoverPath(), album.getId(), albumCoverCache);
        Mono<Long> totalSizeMono = albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                .flatMap(at -> trackRepository.findById(at.getTrackId()))
                .map(track -> track.getFileSize() != null ? track.getFileSize() : 0L)
                .reduce(0L, Long::sum);

        return Mono.zip(tracksMono, artistsMono, countMono, coverMono, totalSizeMono)
                .map(tuple -> new AlbumTracksData(
                        tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(), tuple.getT5()));
    }

    private Mono<AlbumWithTracksDTO> enrichWithTracks(Album album) {
        return fetchAlbumTracksData(album)
                .map(data -> {
                    AlbumWithTracksDTO dto = new AlbumWithTracksDTO();
                    dto.setId(album.getId());
                    dto.setTitle(album.getTitle());
                    dto.setArtistsNames(data.artists());
                    dto.setReleaseDate(album.getReleaseDate());
                    dto.setUserId(album.getUserId());
                    dto.setTrackCount((int) data.trackCount());
                    dto.setTotalSize(data.totalSize());
                    dto.setTracks(data.tracks());
                    dto.setCover(data.cover());
                    return dto;
                });
    }

    private record AlbumDownloadData(String albumTitle, List<AlbumDownloadEntry> entries) {
    }

    private record AlbumDownloadEntry(String trackTitle, Path filePath) {
    }

    private Mono<AlbumDownloadData> getAlbumDownloadData(Long albumId, Long userId) {
        return OwnershipValidator.requireOwnership(
                        albumRepository.findById(albumId), userId, "Album", albumId, Album::getUserId)
                .flatMap(album -> {
                    Mono<List<AlbumDownloadEntry>> entriesMono = albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                            .concatMap(at -> trackRepository.findById(at.getTrackId())
                                    .map(track -> new AlbumDownloadEntry(
                                            FilenameSanitizer.sanitize(track.getTitle()),
                                            reactiveFileService.resolvePath(track.getFilePath())
                                    )))
                            .collectList();
                    return entriesMono.map(entries -> new AlbumDownloadData(FilenameSanitizer.sanitize(album.getTitle()), entries));
                });
    }

    public Mono<Void> downloadAlbum(Long albumId, Long userId, ServerHttpResponse response) {
        return getAlbumDownloadData(albumId, userId)
                .flatMap(data -> {
                    String zipName = data.albumTitle() + ".zip";
                    List<ZipDownloadService.ZipEntryData> entries = data.entries().stream()
                            .map(e -> {
                                String ext = FileUtils.getExtension(e.filePath().toString());
                                return new ZipDownloadService.ZipEntryData(e.trackTitle() + "." + ext, e.filePath());
                            })
                            .toList();
                    return zipDownloadService.createAndSendZip(entries, zipName, response);
                });
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
