package com.musicstreaming.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musicstreaming.adapter.dto.*;
import com.musicstreaming.domain.model.*;
import com.musicstreaming.domain.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
public class AlbumService {

    private static final Logger log = LoggerFactory.getLogger(AlbumService.class);

    private final AlbumRepository albumRepository;
    private final AlbumTrackRepository albumTrackRepository;
    private final TrackRepository trackRepository;
    private final AlbumArtistRepository albumArtistRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final ArtistRepository artistRepository;
    private final Cache<Long, byte[]> coverCache;

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public AlbumService(AlbumRepository albumRepository,
                        AlbumTrackRepository albumTrackRepository,
                        TrackRepository trackRepository,
                        AlbumArtistRepository albumArtistRepository,
                        TrackArtistRepository trackArtistRepository,
                        ArtistRepository artistRepository) {
        this.albumRepository = albumRepository;
        this.albumTrackRepository = albumTrackRepository;
        this.trackRepository = trackRepository;
        this.albumArtistRepository = albumArtistRepository;
        this.trackArtistRepository = trackArtistRepository;
        this.artistRepository = artistRepository;
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
            String coverExt = getFileExtension(cover.filename());
            String coverId = UUID.randomUUID().toString();
            String coverRelativePath = userId + "/covers/" + coverId + "." + coverExt;
            Path coverFullPath = Paths.get(storageBasePath, coverRelativePath);

            saveAlbumMono = Mono.fromCallable(() -> {
                        Files.createDirectories(coverFullPath.getParent());
                        return coverFullPath;
                    })
                    .flatMap(cp -> cover.transferTo(cp).thenReturn(cp))
                    .flatMap(cp -> {
                        album.setCoverPath(coverRelativePath);
                        return albumRepository.save(album);
                    });
        } else {
            saveAlbumMono = albumRepository.save(album);
        }

        return saveAlbumMono
                .flatMap(savedAlbum -> saveAlbumArtists(savedAlbum.getId(), artistIds).thenReturn(savedAlbum))
                .flatMap(this::albumToDto);
    }

    public Mono<PageResponse<AlbumDTO>> getUserAlbums(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);

        Flux<AlbumDTO> content = albumRepository.findByUserId(userId, pageable)
                .flatMap(this::albumToDto);

        Mono<Long> total = albumRepository.countByUserId(userId);

        return Mono.zip(content.collectList(), total,
                (albums, totalElements) -> PageResponse.of(albums, totalElements, page, size));
    }

    public Mono<AlbumWithTracksDTO> getAlbumWithTracks(Long albumId, Long userId) {
        return albumRepository.findById(albumId)
                .switchIfEmpty(Mono.error(new RuntimeException("Album not found")))
                .flatMap(album -> {
                    if (!album.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Album not found"));
                    }
                    return enrichWithTracks(album);
                });
    }

    public Mono<TrackDTO> addTrackToAlbum(Long albumId, String title, List<Long> artistIds,
                                          Integer duration, FilePart file, Integer position,
                                          LocalDate releaseDate, Long userId) {
        return albumRepository.findById(albumId)
                .switchIfEmpty(Mono.error(new RuntimeException("Album not found")))
                .flatMap(album -> {
                    if (!album.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Album not found"));
                    }

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
                            .flatMap(track -> saveTrackArtists(track.getId(), artistIds).thenReturn(track))
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
                .switchIfEmpty(Mono.error(new RuntimeException("Album not found")))
                .flatMap(album -> {
                    if (!album.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Album not found"));
                    }

                    Mono<Void> deleteCover = Mono.empty();
                    if (album.getCoverPath() != null) {
                        Path coverPath = Paths.get(storageBasePath, album.getCoverPath());
                        deleteCover = Mono.fromRunnable(() -> {
                            try {
                                Files.deleteIfExists(coverPath);
                            } catch (Exception ignored) {
                            }
                        });
                    }

                    return deleteCover
                            .then(albumArtistRepository.deleteByAlbumId(albumId))
                            .then(deleteAlbumData(album));
                });
    }

    private Mono<Void> saveAlbumArtists(Long albumId, List<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()) {
            return Mono.empty();
        }

        List<Mono<AlbumArtist>> saves = new ArrayList<>();
        for (int i = 0; i < artistIds.size(); i++) {
            Long artistId = artistIds.get(i);
            int position = i + 1;

            AlbumArtist aa = new AlbumArtist();
            aa.setAlbumId(albumId);
            aa.setArtistId(artistId);
            aa.setPosition(position);
            aa.setCreatedAt(LocalDateTime.now());

            saves.add(albumArtistRepository.save(aa));
        }

        return Flux.concat(saves).then();
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

    private Mono<List<ArtistDTO>> getAlbumArtists(Long albumId) {
        return albumArtistRepository.findByAlbumIdOrderByPosition(albumId)
                .flatMap(aa -> artistRepository.findById(aa.getArtistId()))
                .collectList()
                .flatMap(artists -> {
                    List<Mono<ArtistDTO>> dtos = new ArrayList<>();
                    for (Artist a : artists) {
                        dtos.add(artistToDtoSimple(a));
                    }
                    return Flux.concat(dtos).collectList();
                });
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

    private Mono<Void> deleteAlbumData(Album album) {
        return albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                .flatMap(at -> {
                    return trackArtistRepository.deleteByTrackId(at.getTrackId())
                            .then(trackRepository.findById(at.getTrackId()))
                            .flatMap(track -> {
                                Mono<Void> deleteFile = Mono.empty();
                                if (track.getFilePath() != null) {
                                    Path trackPath = Paths.get(storageBasePath, track.getFilePath());
                                    deleteFile = Mono.fromRunnable(() -> {
                                        try {
                                            Files.deleteIfExists(trackPath);
                                        } catch (Exception ignored) {
                                        }
                                    });
                                }
                                return deleteFile.then(trackRepository.delete(track));
                            });
                })
                .then(albumTrackRepository.deleteByAlbumId(album.getId()))
                .then(albumRepository.deleteById(album.getId()))
                .doOnSuccess(v -> log.info("Album deleted: {}", album.getId()));
    }

    private Mono<AlbumDTO> albumToDto(Album album) {
        Mono<List<ArtistDTO>> artistsMono = getAlbumArtists(album.getId());
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
                            String ext = getFileExtension(album.getCoverPath());
                            String mimeType = getCoverMimeType(ext);
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
                .flatMap(at -> trackRepository.findById(at.getTrackId()))
                .flatMap(this::trackToDtoWithCover)
                .collectList();

        Mono<List<ArtistDTO>> artistsMono = getAlbumArtists(album.getId());
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
            byte[] bytes = Files.readAllBytes(path);
            coverCache.put(albumId, bytes);
            return bytes;
        });
    }

    private Mono<String> getCoverBase64(String coverPath, Long albumId) {
        if (coverPath == null) {
            return Mono.just("");
        }
        return getCoverBytes(coverPath, albumId).map(bytes -> {
            String ext = getFileExtension(coverPath);
            String mimeType = getCoverMimeType(ext);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return "data:" + mimeType + ";base64," + base64;
        });
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "jpg";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "jpg";
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

    private Mono<TrackDTO> trackToDtoWithCover(Track track) {
        TrackDTO baseDto = TrackDTO.fromEntity(track);
        Mono<List<ArtistDTO>> artistsMono = getTrackArtists(track.getId());

        if (track.getCoverPath() != null) {
            return Mono.zip(
                    artistsMono,
                    Mono.fromCallable(() -> {
                        Path coverPath = Paths.get(storageBasePath, track.getCoverPath());
                        return Files.readAllBytes(coverPath);
                    }),
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
