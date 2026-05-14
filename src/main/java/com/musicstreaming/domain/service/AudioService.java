package com.musicstreaming.domain.service;

import com.musicstreaming.adapter.dto.TrackDTO;
import com.musicstreaming.domain.model.Track;
import com.musicstreaming.domain.repository.TrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AudioService {

    private static final Logger log = LoggerFactory.getLogger(AudioService.class);

    private final TrackRepository trackRepository;
    private final DataBufferFactory dataBufferFactory;

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public AudioService(TrackRepository trackRepository) {
        this.trackRepository = trackRepository;
        this.dataBufferFactory = DefaultDataBufferFactory.sharedInstance;
    }

    public Mono<TrackDTO> uploadTrack(String title, String artist, String album,
                                      Integer duration, MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        String mimeType = getMimeType(extension);

        String artistFolder = sanitizeFolderName(artist);
        String albumFolder = sanitizeFolderName(album != null ? album : "unknown");
        String trackId = UUID.randomUUID().toString();

        String relativePath = artistFolder + "/" + albumFolder + "/" + trackId + "." + extension;
        Path fullPath = Paths.get(storageBasePath, relativePath);

        return Mono.fromCallable(() -> {
                    Files.createDirectories(fullPath.getParent());
                    try (InputStream inputStream = file.getInputStream()) {
                        Files.copy(inputStream, fullPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return fullPath;
                })
                .flatMap(path -> {
                    Track track = new Track();
                    track.setTitle(title);
                    track.setArtist(artist);
                    track.setAlbum(album);
                    track.setDuration(duration);
                    track.setFilePath(relativePath);
                    track.setMimeType(mimeType);
                    track.setFileSize(file.getSize());
                    track.setCreatedAt(LocalDateTime.now());
                    track.setUpdatedAt(LocalDateTime.now());

                    return trackRepository.save(track);
                })
                .map(TrackDTO::fromEntity)
                .doOnSuccess(t -> log.info("Track uploaded: {} by {}", t.getTitle(), t.getArtist()));
    }

    public Mono<TrackDTO> getTrackMetadata(Long trackId) {
        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new RuntimeException("Track not found")))
                .map(TrackDTO::fromEntity);
    }

    public Flux<TrackDTO> getAllTracks(int page, int size) {
        return trackRepository.findAll()
                .skip((long) page * size)
                .take(size)
                .map(TrackDTO::fromEntity);
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
}