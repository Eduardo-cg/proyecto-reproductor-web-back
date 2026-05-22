package com.musicstreaming.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musicstreaming.adapter.dto.ArtistDTO;
import com.musicstreaming.adapter.dto.PageResponse;
import com.musicstreaming.adapter.dto.TrackDTO;
import com.musicstreaming.domain.model.Artist;
import com.musicstreaming.domain.model.Track;
import com.musicstreaming.domain.model.TrackArtist;
import com.musicstreaming.domain.repository.ArtistRepository;
import com.musicstreaming.domain.repository.TrackArtistRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AudioService {

    private static final Logger log = LoggerFactory.getLogger(AudioService.class);

    private final TrackRepository trackRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final ArtistRepository artistRepository;
    private final ArtistService artistService;
    private final DataBufferFactory dataBufferFactory;
    private final Cache<Long, byte[]> coverCache;
    private final Cache<Long, FileMetadata> fileMetadataCache;

    public record FileMetadata(long size, String mimeType) {
    }

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public AudioService(TrackRepository trackRepository,
                        TrackArtistRepository trackArtistRepository,
                        ArtistRepository artistRepository,
                        ArtistService artistService) {
        this.trackRepository = trackRepository;
        this.trackArtistRepository = trackArtistRepository;
        this.artistRepository = artistRepository;
        this.artistService = artistService;
        this.dataBufferFactory = DefaultDataBufferFactory.sharedInstance;
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
        String extension = getFileExtension(originalFilename);
        String trackId = UUID.randomUUID().toString();

        String relativePath = userId + "/tracks/" + trackId + "." + extension;
        Path fullPath = Paths.get(storageBasePath, relativePath);

        return Mono.fromCallable(() -> {
                    Files.createDirectories(fullPath.getParent());
                    return fullPath;
                })
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
                        String coverExt = getFileExtension(cover.filename());
                        String coverRelativePath = userId + "/covers/" + trackId + "." + coverExt;
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
                .flatMap(track -> saveTrackArtists(track.getId(), artistIds).thenReturn(track))
                .flatMap(this::trackToDtoWithCover)
                .doOnSuccess(t -> log.info("Track uploaded: {} with {} artists", t.getTitle(), t.getArtists().size()));
    }

    public Mono<TrackDTO> getTrackMetadata(Long trackId, Long userId) {
        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new RuntimeException("Track not found")))
                .flatMap(track -> {
                    if (!track.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Track not found"));
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
                .switchIfEmpty(Mono.error(new RuntimeException("Track not found")))
                .flatMap(track -> {
                    if (!track.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Track not found"));
                    }
                    Path fullPath = Paths.get(storageBasePath, track.getFilePath());
                    return trackArtistRepository.deleteByTrackId(trackId)
                            .then(Mono.fromCallable(() -> {
                                Files.deleteIfExists(fullPath);
                                if (track.getCoverPath() != null) {
                                    Path coverPath = Paths.get(storageBasePath, track.getCoverPath());
                                    Files.deleteIfExists(coverPath);
                                }
                                return true;
                            }))
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
                .switchIfEmpty(Mono.error(new RuntimeException("Track not found")))
                .flatMap(track -> {
                    if (!track.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Track not found"));
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
                    long size = Files.size(path);
                    String mime = Files.probeContentType(path);
                    if (mime == null) mime = "audio/mpeg";
                    FileMetadata meta = new FileMetadata(size, mime);
                    fileMetadataCache.put(trackId, meta);
                    return meta;
                })
        );
    }

    private Mono<Void> saveTrackArtists(Long trackId, List<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()) {
            return Mono.empty();
        }

        List<Mono<TrackArtist>> saves = new ArrayList<>();
        for (int i = 0; i < artistIds.size(); i++) {
            Long artistId = artistIds.get(i);
            int position = i + 1;

            TrackArtist ta = new TrackArtist();
            ta.setTrackId(trackId);
            ta.setArtistId(artistId);
            ta.setPosition(position);
            ta.setCreatedAt(LocalDateTime.now());

            saves.add(trackArtistRepository.save(ta));
        }

        return Flux.concat(saves).then();
    }

    private Mono<List<ArtistDTO>> getTrackArtists(Long trackId) {
        return trackArtistRepository.findByTrackIdOrderByPosition(trackId)
                .flatMap(ta -> artistRepository.findById(ta.getArtistId()))
                .collectList()
                .flatMap(artists -> {
                    List<Mono<ArtistDTO>> dtos = new ArrayList<>();
                    for (Artist a : artists) {
                        dtos.add(artistToDtoSimple(a));
                    }
                    return Flux.concat(dtos).collectList();
                });
    }

    private Mono<ArtistDTO> artistToDtoSimple(Artist artist) {
        return Mono.fromCallable(() -> {
            ArtistDTO dto = new ArtistDTO();
            dto.setId(artist.getId());
            dto.setName(artist.getName());
            dto.setUserId(artist.getUserId());

            if (artist.getImagePath() != null) {
                try {
                    Path path = Paths.get(storageBasePath, artist.getImagePath());
                    byte[] bytes = Files.readAllBytes(path);
                    String ext = getFileExtension(artist.getImagePath());
                    String mimeType = getCoverMimeType(ext);
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    dto.setImage("data:" + mimeType + ";base64," + base64);
                } catch (Exception e) {
                    log.warn("Could not load image for artist {}", artist.getId());
                }
            }
            return dto;
        });
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "mp3";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "mp3";
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
        TrackDTO baseDto = TrackDTO.fromEntity(track);

        Mono<List<ArtistDTO>> artistsMono = getTrackArtists(track.getId());

        if (track.getCoverPath() != null) {
            return Mono.zip(
                    artistsMono,
                    getCoverBytes(track),
                    (artists, coverBytes) -> {
                        String ext = getFileExtension(track.getCoverPath());
                        String mimeType = getCoverMimeType(ext);
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
