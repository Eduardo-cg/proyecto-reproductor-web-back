package com.musicstreaming.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musicstreaming.adapter.dto.TrackDTO;
import com.musicstreaming.domain.model.Track;
import com.musicstreaming.domain.repository.TrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Service
public class AudioService {

    private static final Logger log = LoggerFactory.getLogger(AudioService.class);

    private final TrackRepository trackRepository;
    private final DataBufferFactory dataBufferFactory;
    private final Cache<Long, byte[]> coverCache;

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public AudioService(TrackRepository trackRepository) {
        this.trackRepository = trackRepository;
        this.dataBufferFactory = DefaultDataBufferFactory.sharedInstance;
        this.coverCache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    public Mono<TrackDTO> uploadTrack(String title, String artist, String album, Integer duration, FilePart file, FilePart cover) {
        String originalFilename = file.filename();
        String extension = getFileExtension(originalFilename);
        String mimeType = getMimeType(extension);

        String artistFolder = sanitizeFolderName(artist);
        String albumFolder = sanitizeFolderName(album != null ? album : "unknown");
        String trackId = UUID.randomUUID().toString();

        String relativePath = artistFolder + "/" + albumFolder + "/" + trackId + "." + extension;
        Path fullPath = Paths.get(storageBasePath, relativePath);

        return Mono.fromCallable(() -> {
                    Files.createDirectories(fullPath.getParent());
                    return fullPath;
                })
                .flatMap(path -> file.transferTo(path).thenReturn(path))
                .flatMap(path -> {
                    Track track = new Track();
                    track.setTitle(title);
                    track.setArtist(artist);
                    track.setAlbum(album);
                    track.setDuration(duration);
                    track.setFilePath(relativePath);
                    track.setMimeType(mimeType);
                    track.setFileSize(file.headers().getContentLength());
                    track.setCreatedAt(LocalDateTime.now());
                    track.setUpdatedAt(LocalDateTime.now());

                    if (cover != null) {
                        String coverExt = getFileExtension(cover.filename());
                        String coverRelativePath = "covers/" + trackId + "." + coverExt;
                        Path coverFullPath = Paths.get(storageBasePath, coverRelativePath);
                        return Mono.fromCallable(() -> {
                                    Files.createDirectories(coverFullPath.getParent());
                                    return coverFullPath;
                                })
                                .flatMap(cp -> cover.transferTo(cp).thenReturn(cp))
                                .flatMap(cp -> {
                                    track.setCoverPath(coverRelativePath);
                                    return trackRepository.save(track);
                                });
                    }

                    return trackRepository.save(track);
                })
                .flatMap(this::trackToDtoWithCover)
                .doOnSuccess(t -> log.info("Track uploaded: {} by {}", t.getTitle(), t.getArtist()));
    }

    public Mono<TrackDTO> getTrackMetadata(Long trackId) {
        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new RuntimeException("Track not found")))
                .flatMap(this::trackToDtoWithCover);
    }

    public Flux<TrackDTO> getAllTracks(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return trackRepository.findAllBy(pageable)
                .flatMap(this::trackToDtoWithCover);
    }

    public Mono<Long> getTrackCount() {
        return trackRepository.count();
    }

    public Mono<Void> deleteTrack(Long trackId) {
        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new RuntimeException("Track not found")))
                .flatMap(track -> {
                    Path fullPath = Paths.get(storageBasePath, track.getFilePath());
                    return Mono.fromCallable(() -> {
                                Files.deleteIfExists(fullPath);
                                if (track.getCoverPath() != null) {
                                    Path coverPath = Paths.get(storageBasePath, track.getCoverPath());
                                    Files.deleteIfExists(coverPath);
                                }
                                return true;
                            })
                            .then(trackRepository.deleteById(trackId));
                })
                .doOnSuccess(v -> log.info("Track deleted: {}", trackId));
    }

    public Mono<Path> getTrackFilePath(Long trackId) {
        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new RuntimeException("Track not found")))
                .map(track -> Paths.get(storageBasePath, track.getFilePath()));
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "mp3";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "mp3";
    }

    private String getMimeType(String extension) {
        return switch (extension) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "m4a" -> "audio/mp4";
            default -> "audio/mpeg";
        };
    }

    private String sanitizeFolderName(String name) {
        if (name == null || name.isEmpty()) return "unknown";
        return name.replaceAll("[^a-zA-Z0-9\\-_]", "_");
    }

    private String getCoverMimeType(String extension) {
        return switch (extension.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    private Mono<byte[]> getCoverBytes(Track track) {
        Long trackId = track.getId();
        byte[] cached = coverCache.getIfPresent(trackId);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.fromCallable(() -> {
                    Path coverPath = Paths.get(storageBasePath, track.getCoverPath());
                    return Files.readAllBytes(coverPath);
                })
                .doOnNext(bytes -> coverCache.put(trackId, bytes));
    }

    private Mono<TrackDTO> trackToDtoWithCover(Track track) {
        if (track.getCoverPath() != null) {
            return getCoverBytes(track)
                    .map(coverBytes -> {
                        String ext = getFileExtension(track.getCoverPath());
                        String mimeType = getCoverMimeType(ext);
                        String base64 = Base64.getEncoder().encodeToString(coverBytes);
                        return TrackDTO.fromEntity(track, "data:" + mimeType + ";base64," + base64);
                    });
        }
        return Mono.just(TrackDTO.fromEntity(track));
    }
}