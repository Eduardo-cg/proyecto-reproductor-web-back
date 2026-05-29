package com.musicstreaming.album.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musicstreaming.album.dto.AlbumDTO;
import com.musicstreaming.album.dto.AlbumWithTracksDTO;
import com.musicstreaming.album.entity.Album;
import com.musicstreaming.album.entity.AlbumTrack;
import com.musicstreaming.album.repository.AlbumArtistRepository;
import com.musicstreaming.album.repository.AlbumRepository;
import com.musicstreaming.album.repository.AlbumTrackRepository;
import com.musicstreaming.artist.dto.ArtistDTO;
import com.musicstreaming.artist.service.ArtistLinkService;
import com.musicstreaming.common.dto.PageResponse;
import com.musicstreaming.common.exception.ResourceNotFoundException;
import com.musicstreaming.common.util.FileUtils;
import com.musicstreaming.track.dto.TrackDTO;
import com.musicstreaming.track.entity.Track;
import com.musicstreaming.track.repository.TrackArtistRepository;
import com.musicstreaming.track.repository.TrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AlbumService {

    private static final Logger log = LoggerFactory.getLogger(AlbumService.class);

    private final AlbumRepository albumRepository;
    private final AlbumTrackRepository albumTrackRepository;
    private final TrackRepository trackRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final AlbumArtistRepository albumArtistRepository;
    private final ArtistLinkService artistLinkService;
    private final Cache<Long, byte[]> coverCache;

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public AlbumService(AlbumRepository albumRepository,
                        AlbumTrackRepository albumTrackRepository,
                        TrackRepository trackRepository,
                        TrackArtistRepository trackArtistRepository,
                        AlbumArtistRepository albumArtistRepository,
                        ArtistLinkService artistLinkService) {
        this.albumRepository = albumRepository;
        this.albumTrackRepository = albumTrackRepository;
        this.trackRepository = trackRepository;
        this.trackArtistRepository = trackArtistRepository;
        this.albumArtistRepository = albumArtistRepository;
        this.artistLinkService = artistLinkService;
        this.coverCache = Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
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
            String coverExt = FileUtils.getExtension(cover.filename());
            String coverId = UUID.randomUUID().toString();
            String coverRelativePath = userId + "/covers/" + coverId + "." + coverExt;
            Path coverFullPath = Paths.get(storageBasePath, coverRelativePath);

            saveAlbumMono = Mono.fromCallable(() -> {
                        FileUtils.createDirectories(coverFullPath.getParent());
                        return coverFullPath;
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(cp -> cover.transferTo(cp).thenReturn(cp))
                    .flatMap(cp -> {
                        album.setCoverPath(coverRelativePath);
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
        return albumRepository.findById(albumId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Album", albumId)))
                .flatMap(album -> {
                    if (!album.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Album", albumId));
                    }
                    if (title != null && !title.trim().isEmpty()) {
                        album.setTitle(title.trim());
                    }
                    if (releaseDate != null) {
                        album.setReleaseDate(releaseDate);
                    }
                    album.setUpdatedAt(LocalDateTime.now());

                    Mono<Album> saveMono;
                    if (cover != null) {
                        String coverExt = FileUtils.getExtension(cover.filename());
                        String coverId = UUID.randomUUID().toString();
                        String coverRelativePath = userId + "/covers/" + coverId + "." + coverExt;
                        Path coverFullPath = Paths.get(storageBasePath, coverRelativePath);

                        saveMono = Mono.fromCallable(() -> {
                                    FileUtils.createDirectories(coverFullPath.getParent());
                                    return coverFullPath;
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(cp -> cover.transferTo(cp).thenReturn(cp))
                                .flatMap(cp -> {
                                    if (album.getCoverPath() != null) {
                                        Path oldCover = Paths.get(storageBasePath, album.getCoverPath());
                                        try {
                                            FileUtils.deleteIfExists(oldCover);
                                            coverCache.invalidate(albumId);
                                        } catch (Exception ignored) {
                                        }
                                    }
                                    album.setCoverPath(coverRelativePath);
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
                .doOnSuccess(a -> log.info("Album updated: {} (id={})", a.getTitle(), a.getId()));
    }

    public Mono<PageResponse<AlbumDTO>> getUserAlbums(Long userId, int page, int size, String search, List<Long> artistIds) {
        Pageable pageable = PageRequest.of(page, size);
        boolean hasSearch = search != null && !search.trim().isEmpty();
        boolean hasArtistIds = artistIds != null && !artistIds.isEmpty();
        String query = hasSearch ? search.trim() : null;

        Mono<List<AlbumDTO>> content;
        Mono<Long> total;

        if (hasSearch && hasArtistIds) {
            content = albumRepository.searchByUserIdAndArtistIds(userId, query, artistIds, pageable)
                    .flatMap(this::albumToDto).collectList();
            total = albumRepository.countSearchByUserIdAndArtistIds(userId, query, artistIds);
        } else if (hasSearch) {
            content = albumRepository.searchAlbumsByUser(userId, query, pageable)
                    .flatMap(this::albumToDto).collectList();
            total = albumRepository.countSearchAlbumsByUser(userId, query);
        } else if (hasArtistIds) {
            content = albumRepository.findByUserIdAndArtistIds(userId, artistIds, pageable)
                    .flatMap(this::albumToDto).collectList();
            total = albumRepository.countByUserIdAndArtistIds(userId, artistIds);
        } else {
            content = albumRepository.findByUserId(userId, pageable)
                    .flatMap(this::albumToDto).collectList();
            total = albumRepository.countByUserId(userId);
        }

        return Mono.zip(content, total,
                (albums, totalElements) -> PageResponse.of(albums, totalElements, page, size));
    }

    public Mono<List<AlbumDTO>> getUserAlbumsList(Long userId, List<Long> artistIds) {
        Flux<Album> albums;
        if (artistIds != null && !artistIds.isEmpty()) {
            albums = albumRepository.findSimpleByUserIdAndArtistIds(userId, artistIds);
        } else {
            albums = albumRepository.findSimpleByUserId(userId);
        }
        return albums.map(album -> {
            AlbumDTO dto = new AlbumDTO();
            dto.setId(album.getId());
            dto.setTitle(album.getTitle());
            return dto;
        }).collectList();
    }

    public Mono<AlbumWithTracksDTO> getAlbumWithTracks(Long albumId, Long userId) {
        return albumRepository.findById(albumId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Album", albumId)))
                .flatMap(album -> {
                    if (!album.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Album", albumId));
                    }
                    return enrichWithTracks(album);
                });
    }

    public Mono<TrackDTO> addTrackToAlbum(Long albumId, String title, List<Long> artistIds,
                                          Integer duration, FilePart file, Integer position,
                                          LocalDate releaseDate, Long userId) {
        return albumRepository.findById(albumId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Album", albumId)))
                .flatMap(album -> {
                    if (!album.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Album", albumId));
                    }

                    String originalFilename = file.filename();
                    String extension = FileUtils.getExtension(originalFilename);
                    String trackId = UUID.randomUUID().toString();
                    String relativePath = userId + "/tracks/" + trackId + "." + extension;
                    Path fullPath = Paths.get(storageBasePath, relativePath);

                    return Mono.fromCallable(() -> {
                                FileUtils.createDirectories(fullPath.getParent());
                                return fullPath;
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(path -> file.transferTo(path).thenReturn(path))
                            .flatMap(path -> {
                                Track track = new Track();
                                track.setTitle(title);
                                track.setAlbum(album.getTitle());
                                track.setDuration(duration);
                                track.setFilePath(relativePath);
                                track.setCoverPath(album.getCoverPath());
                                track.setUserId(userId);
                                track.setReleaseDate(releaseDate);
                                track.setCreatedAt(LocalDateTime.now());
                                track.setUpdatedAt(LocalDateTime.now());

                                return trackRepository.save(track);
                            })
                            .flatMap(track -> artistLinkService.saveTrackArtists(track.getId(), artistIds).thenReturn(track))
                            .flatMap(track -> {
                                AlbumTrack albumTrack = new AlbumTrack();
                                albumTrack.setAlbumId(albumId);
                                albumTrack.setTrackId(track.getId());
                                albumTrack.setPosition(position != null ? position : 1);
                                albumTrack.setCreatedAt(LocalDateTime.now());
                                return albumTrackRepository.save(albumTrack)
                                        .thenReturn(track);
                            })
                            .flatMap(this::trackToDtoWithCover)
                            .doOnSuccess(t -> log.info("Track added to album {}: {}", albumId, t.getTitle()));
                });
    }

    public Mono<Void> deleteAlbum(Long albumId, Long userId) {
        return albumRepository.findById(albumId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Album", albumId)))
                .flatMap(album -> {
                    if (!album.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Album", albumId));
                    }

                    Mono<Void> deleteCover = Mono.empty();
                    if (album.getCoverPath() != null) {
                        Path coverPath = Paths.get(storageBasePath, album.getCoverPath());
                        deleteCover = Mono.fromCallable(() -> {
                                    FileUtils.deleteIfExists(coverPath);
                                    return true;
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .then();
                    }

                    return deleteCover
                            .then(albumArtistRepository.deleteByAlbumId(albumId))
                            .then(deleteAlbumData(album));
                });
    }

    public Mono<Void> removeTrackFromAlbum(Long albumId, Long trackId, Long userId) {
        return albumRepository.findById(albumId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Album", albumId)))
                .flatMap(album -> {
                    if (!album.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Album", albumId));
                    }
                    return trackRepository.findById(trackId)
                            .switchIfEmpty(Mono.error(new ResourceNotFoundException("Track", trackId)))
                            .flatMap(track -> {
                                if (!track.getUserId().equals(userId)) {
                                    return Mono.error(new ResourceNotFoundException("Track", trackId));
                                }
                                Mono<Void> deleteFile = Mono.empty();
                                if (track.getFilePath() != null) {
                                    Path trackPath = Paths.get(storageBasePath, track.getFilePath());
                                    deleteFile = Mono.fromCallable(() -> {
                                                FileUtils.deleteIfExists(trackPath);
                                                return true;
                                            })
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .then();
                                }
                                return deleteFile
                                        .then(trackArtistRepository.deleteByTrackId(trackId))
                                        .then(albumTrackRepository.deleteByAlbumIdAndTrackId(albumId, trackId))
                                        .then(trackRepository.delete(track));
                            });
                })
                .then();
    }

    public Mono<Void> reorderAlbumTracks(Long albumId, List<Long> trackIds, Long userId) {
        return albumRepository.findById(albumId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Album", albumId)))
                .flatMap(album -> {
                    if (!album.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Album", albumId));
                    }
                    return albumTrackRepository.deleteByAlbumId(albumId)
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
                });
    }

    private Mono<Void> deleteAlbumData(Album album) {
        return albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                .flatMap(at -> trackArtistRepository.deleteByTrackId(at.getTrackId())
                        .then(trackRepository.findById(at.getTrackId()))
                        .flatMap(track -> {
                            Mono<Void> deleteFile = Mono.empty();
                            if (track.getFilePath() != null) {
                                Path trackPath = Paths.get(storageBasePath, track.getFilePath());
                                deleteFile = Mono.fromCallable(() -> {
                                            FileUtils.deleteIfExists(trackPath);
                                            return true;
                                        })
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .then();
                            }
                            return deleteFile.then(trackRepository.delete(track));
                        }))
                .then(albumTrackRepository.deleteByAlbumId(album.getId()))
                .then(albumRepository.deleteById(album.getId()))
                .doOnSuccess(v -> log.info("Album deleted: {}", album.getId()));
    }

    private Mono<AlbumDTO> albumToDto(Album album) {
        Mono<List<ArtistDTO>> artistsMono = artistLinkService.getAlbumArtists(album.getId());
        Mono<Integer> countMono = albumTrackRepository.countByAlbumId(album.getId()).map(Long::intValue);

        return Mono.zip(artistsMono, countMono, (artists, count) -> {
            AlbumDTO dto = new AlbumDTO();
            dto.setId(album.getId());
            dto.setTitle(album.getTitle());
            dto.setArtists(artists);
            dto.setReleaseDate(album.getReleaseDate());
            dto.setUserId(album.getUserId());
            dto.setTrackCount(count);
            return dto;
        }).flatMap(dto -> {
            if (album.getCoverPath() != null) {
                return getCoverBytes(album.getCoverPath(), album.getId())
                        .map(coverBytes -> {
                            String mimeType = FileUtils.getMimeType(album.getCoverPath());
                            String base64 = Base64.getEncoder().encodeToString(coverBytes);
                            dto.setCover("data:" + mimeType + ";base64," + base64);
                            return dto;
                        });
            }
            return Mono.just(dto);
        });
    }

    private Mono<AlbumWithTracksDTO> enrichWithTracks(Album album) {
        Mono<List<TrackDTO>> tracksMono = albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                .concatMap(at -> trackRepository.findById(at.getTrackId()))
                .concatMap(this::trackToDtoWithCover)
                .collectList();

        Mono<List<ArtistDTO>> artistsMono = artistLinkService.getAlbumArtists(album.getId());
        Mono<Long> countMono = albumTrackRepository.countByAlbumId(album.getId());
        Mono<String> coverMono = getCoverBase64(album.getCoverPath(), album.getId());

        return Mono.zip(tracksMono, artistsMono, countMono, coverMono)
                .flatMap(objects -> {
                    var tracks = objects.getT1();
                    var artists = objects.getT2();
                    var count = objects.getT3();
                    var cover = objects.getT4();

                    AlbumWithTracksDTO dto = new AlbumWithTracksDTO();
                    dto.setId(album.getId());
                    dto.setTitle(album.getTitle());
                    dto.setArtists(artists);
                    dto.setReleaseDate(album.getReleaseDate());
                    dto.setUserId(album.getUserId());
                    dto.setTrackCount(count.intValue());
                    dto.setTracks(tracks);
                    dto.setCover(cover);
                    return Mono.just(dto);
                });
    }

    private Mono<byte[]> getCoverBytes(String coverPath, Long albumId) {
        byte[] cached = coverCache.getIfPresent(albumId);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.fromCallable(() -> {
                    Path path = Paths.get(storageBasePath, coverPath);
                    byte[] bytes = FileUtils.readAllBytes(path);
                    coverCache.put(albumId, bytes);
                    return bytes;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> getCoverBase64(String coverPath, Long albumId) {
        if (coverPath == null) {
            return Mono.just("");
        }
        return getCoverBytes(coverPath, albumId).map(bytes -> {
            String mimeType = FileUtils.getMimeType(coverPath);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return "data:" + mimeType + ";base64," + base64;
        });
    }

    public record AlbumDownloadData(String albumTitle, List<AlbumDownloadEntry> entries) {
    }

    public record AlbumDownloadEntry(String trackTitle, Path filePath) {
    }

    public Mono<AlbumDownloadData> getAlbumDownloadData(Long albumId, Long userId) {
        return albumRepository.findById(albumId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Album", albumId)))
                .flatMap(album -> {
                    if (!album.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Album", albumId));
                    }
                    Mono<List<AlbumDownloadEntry>> entriesMono = albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                            .concatMap(at -> trackRepository.findById(at.getTrackId())
                                    .map(track -> new AlbumDownloadEntry(
                                            track.getTitle(),
                                            Paths.get(storageBasePath, track.getFilePath())
                                    )))
                            .collectList();
                    return entriesMono.map(entries -> new AlbumDownloadData(album.getTitle(), entries));
                });
    }

    private Mono<TrackDTO> trackToDtoWithCover(Track track) {
        TrackDTO baseDto = TrackDTO.fromEntity(track);
        Mono<List<ArtistDTO>> artistsMono = artistLinkService.getTrackArtists(track.getId());

        if (track.getCoverPath() != null) {
            Mono<byte[]> coverMono = Mono.fromCallable(() -> {
                Path coverPath = Paths.get(storageBasePath, track.getCoverPath());
                return FileUtils.readAllBytes(coverPath);
            }).subscribeOn(Schedulers.boundedElastic());

            return Mono.zip(artistsMono, coverMono,
                    (artists, coverBytes) -> {
                        String mimeType = FileUtils.getMimeType(track.getCoverPath());
                        String base64 = Base64.getEncoder().encodeToString(coverBytes);
                        TrackDTO dto = TrackDTO.fromEntity(track, "data:" + mimeType + ";base64," + base64);
                        dto.setArtists(artists);
                        return dto;
                    }
            );
        }

        return artistsMono.map(artists -> {
            baseDto.setArtists(artists);
            return baseDto;
        });
    }
}
