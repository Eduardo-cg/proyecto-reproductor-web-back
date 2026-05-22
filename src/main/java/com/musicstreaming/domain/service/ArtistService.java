package com.musicstreaming.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musicstreaming.adapter.dto.ArtistDTO;
import com.musicstreaming.domain.model.Artist;
import com.musicstreaming.domain.model.AlbumArtist;
import com.musicstreaming.domain.model.TrackArtist;
import com.musicstreaming.domain.repository.ArtistRepository;
import com.musicstreaming.domain.repository.AlbumArtistRepository;
import com.musicstreaming.domain.repository.TrackArtistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ArtistService {

    private static final Logger log = LoggerFactory.getLogger(ArtistService.class);

    private final ArtistRepository artistRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final AlbumArtistRepository albumArtistRepository;
    private final Cache<Long, byte[]> imageCache;

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public ArtistService(ArtistRepository artistRepository,
                         TrackArtistRepository trackArtistRepository,
                         AlbumArtistRepository albumArtistRepository) {
        this.artistRepository = artistRepository;
        this.trackArtistRepository = trackArtistRepository;
        this.albumArtistRepository = albumArtistRepository;
        this.imageCache = Caffeine.newBuilder()
                .maximumSize(300)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    public Flux<ArtistDTO> getUserArtists(Long userId, String searchQuery) {
        Flux<Artist> artists;
        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            artists = artistRepository.searchByUserId(userId, searchQuery.trim());
        } else {
            artists = artistRepository.findByUserId(userId);
        }
        return artists.flatMap(this::artistToDto);
    }

    public Mono<ArtistDTO> getArtistById(Long artistId, Long userId) {
        return artistRepository.findById(artistId)
                .switchIfEmpty(Mono.error(new RuntimeException("Artist not found")))
                .flatMap(artist -> {
                    if (!artist.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Artist not found"));
                    }
                    return artistToDto(artist);
                });
    }

    public Mono<ArtistDTO> createArtist(String name, FilePart image, Long userId) {
        return artistRepository.findByUserIdAndName(userId, name)
                .flatMap(existing -> Mono.<Artist>error(new RuntimeException("Artist with this name already exists")))
                .switchIfEmpty(Mono.defer(() -> {
                    Artist artist = new Artist();
                    artist.setName(name);
                    artist.setUserId(userId);
                    artist.setCreatedAt(LocalDateTime.now());
                    artist.setUpdatedAt(LocalDateTime.now());

                    if (image != null) {
                        return saveArtistImage(artist, image);
                    }
                    return artistRepository.save(artist);
                }))
                .cast(Artist.class)
                .flatMap(this::artistToDto)
                .doOnSuccess(a -> log.info("Artist created: {} by user {}", a.getName(), userId));
    }

    public Mono<ArtistDTO> updateArtist(Long artistId, String name, FilePart image, Long userId) {
        return artistRepository.findById(artistId)
                .switchIfEmpty(Mono.error(new RuntimeException("Artist not found")))
                .flatMap(artist -> {
                    if (!artist.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Artist not found"));
                    }

                    if (name != null && !name.trim().isEmpty() && !name.equals(artist.getName())) {
                        return artistRepository.findByUserIdAndName(userId, name.trim())
                                .flatMap(existing -> {
                                    if (!existing.getId().equals(artistId)) {
                                        return Mono.<Artist>error(new RuntimeException("Artist with this name already exists"));
                                    }
                                    return Mono.just(artist);
                                })
                                .switchIfEmpty(Mono.defer(() -> {
                                    artist.setName(name.trim());
                                    return Mono.just(artist);
                                }));
                    }
                    return Mono.just(artist);
                })
                .flatMap(artist -> {
                    artist.setUpdatedAt(LocalDateTime.now());
                    if (image != null) {
                        return saveArtistImage(artist, image);
                    }
                    return artistRepository.save(artist);
                })
                .flatMap(this::artistToDto)
                .doOnSuccess(a -> log.info("Artist updated: {} by user {}", a.getName(), userId));
    }

    public Mono<Void> deleteArtist(Long artistId, Long userId) {
        return artistRepository.findById(artistId)
                .switchIfEmpty(Mono.error(new RuntimeException("Artist not found")))
                .flatMap(artist -> {
                    if (!artist.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Artist not found"));
                    }

                    return trackArtistRepository.deleteByArtistId(artistId)
                            .then(albumArtistRepository.deleteByArtistId(artistId))
                            .then(Mono.fromRunnable(() -> {
                                if (artist.getImagePath() != null) {
                                    try {
                                        Path imgPath = Paths.get(storageBasePath, artist.getImagePath());
                                        Files.deleteIfExists(imgPath);
                                        imageCache.invalidate(artistId);
                                    } catch (Exception ignored) {}
                                }
                            }))
                            .then(artistRepository.deleteById(artistId));
                })
                .doOnSuccess(v -> log.info("Artist deleted: {} by user {}", artistId, userId));
    }

    private Mono<Artist> saveArtistImage(Artist artist, FilePart image) {
        String imageExt = getFileExtension(image.filename());
        String imageId = UUID.randomUUID().toString();
        String relativePath = artist.getUserId() + "/artists/" + imageId + "." + imageExt;
        Path fullPath = Paths.get(storageBasePath, relativePath);

        return Mono.fromCallable(() -> {
                    Files.createDirectories(fullPath.getParent());
                    return fullPath;
                })
                .flatMap(fp -> image.transferTo(fp).thenReturn(fp))
                .flatMap(fp -> {
                    if (artist.getImagePath() != null) {
                        try {
                            Path oldPath = Paths.get(storageBasePath, artist.getImagePath());
                            Files.deleteIfExists(oldPath);
                            imageCache.invalidate(artist.getId());
                        } catch (Exception ignored) {}
                    }
                    artist.setImagePath(relativePath);
                    return artistRepository.save(artist);
                });
    }

    private Mono<ArtistDTO> artistToDto(Artist artist) {
        return Mono.fromCallable(() -> {
            ArtistDTO dto = new ArtistDTO();
            dto.setId(artist.getId());
            dto.setName(artist.getName());
            dto.setUserId(artist.getUserId());

            if (artist.getImagePath() != null) {
                byte[] cached = imageCache.getIfPresent(artist.getId());
                if (cached == null) {
                    try {
                        Path path = Paths.get(storageBasePath, artist.getImagePath());
                        cached = Files.readAllBytes(path);
                        imageCache.put(artist.getId(), cached);
                    } catch (Exception e) {
                        log.warn("Could not load image for artist {}", artist.getId());
                    }
                }
                if (cached != null) {
                    String ext = getFileExtension(artist.getImagePath());
                    String mimeType = getImageMimeType(ext);
                    String base64 = Base64.getEncoder().encodeToString(cached);
                    dto.setImage("data:" + mimeType + ";base64," + base64);
                }
            }
            return dto;
        });
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "jpg";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "jpg";
    }

    private String getImageMimeType(String extension) {
        return switch (extension.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }
}
