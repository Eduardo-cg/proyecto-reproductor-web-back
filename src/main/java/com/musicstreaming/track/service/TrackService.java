package com.musicstreaming.track.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musicstreaming.album.repository.AlbumRepository;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class TrackService {

    private static final Logger log = LoggerFactory.getLogger(TrackService.class);

    private final TrackRepository trackRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final AlbumRepository albumRepository;
    private final ArtistLinkService artistLinkService;
    private final Cache<Long, byte[]> coverCache;
    private final Cache<Long, FileMetadata> fileMetadataCache;

    public record FileMetadata(long size, String mimeType) {
    }

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public TrackService(TrackRepository trackRepository,
                        TrackArtistRepository trackArtistRepository,
                        AlbumRepository albumRepository,
                        ArtistLinkService artistLinkService) {
        this.trackRepository = trackRepository;
        this.trackArtistRepository = trackArtistRepository;
        this.albumRepository = albumRepository;
        this.artistLinkService = artistLinkService;
        this.coverCache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
        this.fileMetadataCache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    public Mono<TrackDTO> uploadTrack(String title, List<Long> artistIds, String album,
                                      Integer duration, FilePart file,
                                      FilePart cover, Long userId, LocalDate releaseDate) {
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
                    track.setAlbum(album);
                    track.setDuration(duration);
                    track.setFilePath(relativePath);
                    track.setUserId(userId);
                    track.setReleaseDate(releaseDate);
                    track.setCreatedAt(LocalDateTime.now());
                    track.setUpdatedAt(LocalDateTime.now());

                    if (cover != null) {
                        String coverExt = FileUtils.getExtension(cover.filename());
                        String coverRelativePath = userId + "/covers/" + trackId + "." + coverExt;
                        Path coverFullPath = Paths.get(storageBasePath, coverRelativePath);
                        return Mono.fromCallable(() -> {
                                    FileUtils.createDirectories(coverFullPath.getParent());
                                    return coverFullPath;
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(cp -> cover.transferTo(cp).thenReturn(cp))
                                .flatMap(cp -> {
                                    track.setCoverPath(coverRelativePath);
                                    return trackRepository.save(track);
                                });
                    }

                    return trackRepository.save(track);
                })
                .flatMap(track -> artistLinkService.saveTrackArtists(track.getId(), artistIds).thenReturn(track))
                .flatMap(this::trackToDtoWithCover)
                .doOnSuccess(t -> log.info("Track uploaded: {} with {} artists", t.getTitle(), t.getArtists().size()));
    }

    public Mono<TrackDTO> getTrackMetadata(Long trackId, Long userId) {
        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Track", trackId)))
                .flatMap(track -> {
                    if (!track.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Track", trackId));
                    }
                    return trackToDtoWithCover(track);
                });
    }

    public Mono<PageResponse<TrackDTO>> getUserTracks(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Mono<List<TrackDTO>> content = trackRepository.findByUserId(userId, pageable)
                .flatMap(this::trackToDtoWithCover)
                .collectList();
        Mono<Long> total = trackRepository.countByUserId(userId);
        return Mono.zip(content, total, (tracks, totalElements) -> PageResponse.of(tracks, totalElements, page, size));
    }

    public Mono<Long> getUserTrackCount(Long userId) {
        return trackRepository.countByUserId(userId);
    }

    public Mono<TrackDTO> updateTrack(Long trackId, String title, List<Long> artistIds,
                                      String album, LocalDate releaseDate,
                                      FilePart cover, Long userId) {
        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Track", trackId)))
                .flatMap(track -> {
                    if (!track.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Track", trackId));
                    }
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

                    Mono<Track> saveMono;
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
                                    if (track.getCoverPath() != null) {
                                        Path oldCover = Paths.get(storageBasePath, track.getCoverPath());
                                        try {
                                            FileUtils.deleteIfExists(oldCover);
                                            coverCache.invalidate(trackId);
                                        } catch (Exception ignored) {
                                        }
                                    }
                                    track.setCoverPath(coverRelativePath);
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
        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Track", trackId)))
                .flatMap(track -> {
                    if (!track.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Track", trackId));
                    }
                    Path fullPath = Paths.get(storageBasePath, track.getFilePath());
                    return trackArtistRepository.deleteByTrackId(trackId)
                            .then(Mono.fromCallable(() -> {
                                FileUtils.deleteIfExists(fullPath);
                                return track;
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .flatMap(t -> deleteCoverIfOrphaned(t, trackId))
                            .then(trackRepository.deleteById(trackId));
                })
                .doOnSuccess(v -> {
                    fileMetadataCache.invalidate(trackId);
                    coverCache.invalidate(trackId);
                    log.info("Track deleted: {}", trackId);
                });
    }

    private Mono<Void> deleteCoverIfOrphaned(Track track, Long trackId) {
        if (track.getCoverPath() == null) {
            return Mono.empty();
        }
        String coverPath = track.getCoverPath();
        Mono.zip(trackRepository.countByCoverPathAndIdNot(coverPath, trackId), albumRepository.countByCoverPath(coverPath), Long::sum).flatMap(refCount -> {
            if (refCount == 0) {
                return Mono.fromRunnable(() ->
                        {
                            try {
                                FileUtils.deleteIfExists(Paths.get(storageBasePath, coverPath));
                            } catch (Exception ignored) {
                            }
                        }
                ).subscribeOn(Schedulers.boundedElastic());
            }
            return Mono.empty();
        });
        return Mono.empty();
    }

    public Mono<Path> getTrackFilePath(Long trackId, Long userId) {
        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Track", trackId)))
                .flatMap(track -> {
                    if (!track.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Track", trackId));
                    }
                    return Mono.just(Paths.get(storageBasePath, track.getFilePath()));
                });
    }

    public Mono<FileMetadata> getFileMetadata(Long trackId, Long userId) {
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

    private Mono<List<ArtistDTO>> getTrackArtists(Long trackId) {
        return artistLinkService.getTrackArtists(trackId);
    }

    private Mono<byte[]> getCoverBytes(Track track) {
        Long trackId = track.getId();
        byte[] cached = coverCache.getIfPresent(trackId);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.fromCallable(() -> {
                    Path coverPath = Paths.get(storageBasePath, track.getCoverPath());
                    return FileUtils.readAllBytes(coverPath);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(bytes -> coverCache.put(trackId, bytes));
    }

    private Mono<TrackDTO> trackToDtoWithCover(Track track) {
        TrackDTO baseDto = TrackDTO.fromEntity(track);

        Mono<List<ArtistDTO>> artistsMono = getTrackArtists(track.getId());

        if (track.getCoverPath() != null) {
            return Mono.zip(
                    artistsMono,
                    getCoverBytes(track),
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
