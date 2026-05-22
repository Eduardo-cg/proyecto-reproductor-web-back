package com.musicstreaming.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musicstreaming.adapter.dto.ArtistDTO;
import com.musicstreaming.adapter.dto.PageResponse;
import com.musicstreaming.adapter.dto.TrackDTO;
import com.musicstreaming.domain.model.Track;
import com.musicstreaming.domain.repository.TrackArtistRepository;
import com.musicstreaming.domain.repository.TrackRepository;
import com.musicstreaming.shared.exception.ResourceNotFoundException;
import com.musicstreaming.shared.service.ArtistLinkService;
import com.musicstreaming.shared.util.FileUtils;
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
public class AudioService {

    private static final Logger log = LoggerFactory.getLogger(AudioService.class);

    private final TrackRepository trackRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final ArtistLinkService artistLinkService;
    private final Cache<Long, byte[]> coverCache;
    private final Cache<Long, FileMetadata> fileMetadataCache;

    public record FileMetadata(long size, String mimeType) {
    }

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public AudioService(TrackRepository trackRepository,
                        TrackArtistRepository trackArtistRepository,
                        ArtistLinkService artistLinkService) {
        this.trackRepository = trackRepository;
        this.trackArtistRepository = trackArtistRepository;
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
                                if (track.getCoverPath() != null) {
                                    Path coverPath = Paths.get(storageBasePath, track.getCoverPath());
                                    FileUtils.deleteIfExists(coverPath);
                                }
                                return true;
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .then(trackRepository.deleteById(trackId));
                })
                .doOnSuccess(v -> {
                    fileMetadataCache.invalidate(trackId);
                    coverCache.invalidate(trackId);
                    log.info("Track deleted: {}", trackId);
                });
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
