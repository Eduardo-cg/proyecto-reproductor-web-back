package com.musicstreaming.album.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.musicstreaming.album.dto.AlbumDTO;
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
import com.musicstreaming.common.util.OwnershipValidator;
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
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class AlbumService {

    private static final Logger log = LoggerFactory.getLogger(AlbumService.class);

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
    private final Cache<Long, byte[]> albumCoverCache;
    private final Cache<Long, byte[]> trackCoverCache;

    private record AlbumStats(List<ArtistDTO> artists, int trackCount, long totalSize) {
    }

    private record AlbumTracksData(List<TrackDTO> tracks, List<ArtistDTO> artists,
                                   long trackCount, String cover, long totalSize) {
    }

    public Mono<AlbumDTO> createAlbum(String title, List<Long> artistIds, LocalDate releaseDate,
                                      FilePart cover, Long userId) {
        Album album = new Album();
        album.setTitle(title);
        album.setReleaseDate(releaseDate);
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
                .flatMap(savedAlbum -> artistLinkService.saveAlbumArtists(savedAlbum.getId(), artistIds).thenReturn(savedAlbum))
                .flatMap(this::albumToDto);
    }

    public Mono<AlbumDTO> updateAlbum(Long albumId, String title, List<Long> artistIds,
                                      LocalDate releaseDate, FilePart cover, Long userId) {
        return OwnershipValidator.requireOwnership(
                        albumRepository.findById(albumId), userId, "Album", albumId, Album::getUserId)
                .flatMap(album -> {
                    if (title != null && !title.trim().isEmpty()) {
                        album.setTitle(title.trim());
                    }
                    if (releaseDate != null) {
                        album.setReleaseDate(releaseDate);
                    }
                    album.setUpdatedAt(LocalDateTime.now());

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

                    return saveMono.flatMap(savedAlbum -> {
                        if (artistIds != null && !artistIds.isEmpty()) {
                            return albumArtistRepository.deleteByAlbumId(albumId)
                                    .then(artistLinkService.saveAlbumArtists(albumId, artistIds))
                                    .thenReturn(savedAlbum);
                        }
                        return Mono.just(savedAlbum);
                    });
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

    public Mono<TrackDTO> addTrackToAlbum(Long albumId, String title, List<Long> artistIds,
                                          Integer duration, FilePart file, Integer position,
                                          LocalDate releaseDate, Long userId) {
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
                                return reactiveFileService.uploadFileWithSize(file, userId, "tracks", actualSize)
                                        .flatMap(relativePath -> {
                                            Track track = createTrackForAlbum(album, title, duration,
                                                    relativePath, actualSize.get(), userId, releaseDate);
                                            return trackRepository.save(track);
                                        })
                                        .flatMap(track -> artistLinkService.saveTrackArtists(track.getId(), artistIds).thenReturn(track))
                                        .flatMap(track -> linkTrackToAlbum(albumId, track.getId(), position).thenReturn(track))
                                        .flatMap(this::trackToDtoWithCover)
                                        .doOnSuccess(t -> log.info("Track added to album userId={} albumId={} title={}", userId, albumId, t.getTitle()));
                            });
                });
    }

    public Mono<Void> deleteAlbum(Long albumId, Long userId) {
        return OwnershipValidator.requireOwnership(
                        albumRepository.findById(albumId), userId, "Album", albumId, Album::getUserId)
                .flatMap(album -> {
                    Mono<Void> deleteCover = Mono.empty();
                    if (album.getCoverPath() != null) {
                        deleteCover = reactiveFileService.deleteFile(reactiveFileService.resolvePath(album.getCoverPath()));
                    }

                    return deleteCover
                            .then(albumArtistRepository.deleteByAlbumId(albumId))
                            .then(deleteAlbumData(album));
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
                .flatMap(album -> albumTrackRepository.deleteByAlbumId(albumId)
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
                        .then());
    }

    private Track createTrackForAlbum(Album album, String title, Integer duration,
                                      String relativePath, Long fileSize, Long userId,
                                      LocalDate releaseDate) {
        Track track = new Track();
        track.setTitle(title);
        track.setAlbum(album.getTitle());
        track.setDuration(duration);
        track.setFilePath(relativePath);
        track.setFileSize(fileSize);
        track.setCoverPath(album.getCoverPath());
        track.setUserId(userId);
        track.setReleaseDate(releaseDate);
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
                .flatMap(at -> trackArtistRepository.deleteByTrackId(at.getTrackId())
                        .then(trackRepository.findById(at.getTrackId()))
                        .flatMap(track -> {
                            Mono<Void> deleteFile = Mono.empty();
                            if (track.getFilePath() != null) {
                                deleteFile = reactiveFileService.deleteFile(reactiveFileService.resolvePath(track.getFilePath()));
                            }
                            return deleteFile.then(trackRepository.delete(track));
                        }))
                .then(albumTrackRepository.deleteByAlbumId(album.getId()))
                .then(albumRepository.deleteById(album.getId()))
                .doOnSuccess(v -> log.info("Album deleted userId={} albumId={}", album.getUserId(), album.getId()));
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

    public Mono<AlbumDownloadData> getAlbumDownloadData(Long albumId, Long userId) {
        return OwnershipValidator.requireOwnership(
                        albumRepository.findById(albumId), userId, "Album", albumId, Album::getUserId)
                .flatMap(album -> {
                    Mono<List<AlbumDownloadEntry>> entriesMono = albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                            .concatMap(at -> trackRepository.findById(at.getTrackId())
                                    .map(track -> new AlbumDownloadEntry(
                                            track.getTitle(),
                                            reactiveFileService.resolvePath(track.getFilePath())
                                    )))
                            .collectList();
                    return entriesMono.map(entries -> new AlbumDownloadData(album.getTitle(), entries));
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
