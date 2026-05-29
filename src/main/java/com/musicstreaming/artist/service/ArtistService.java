package com.musicstreaming.artist.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musicstreaming.album.dto.AlbumDTO;
import com.musicstreaming.album.entity.Album;
import com.musicstreaming.album.repository.AlbumArtistRepository;
import com.musicstreaming.album.repository.AlbumRepository;
import com.musicstreaming.album.repository.AlbumTrackRepository;
import com.musicstreaming.artist.dto.ArtistDTO;
import com.musicstreaming.artist.entity.Artist;
import com.musicstreaming.artist.repository.ArtistRepository;
import com.musicstreaming.common.dto.PageResponse;
import com.musicstreaming.common.exception.ResourceAlreadyExistsException;
import com.musicstreaming.common.exception.ResourceNotFoundException;
import com.musicstreaming.common.util.FileUtils;
import com.musicstreaming.track.entity.Track;
import com.musicstreaming.track.repository.TrackArtistRepository;
import com.musicstreaming.track.dto.TrackDTO;
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class ArtistService {

    private static final Logger log = LoggerFactory.getLogger(ArtistService.class);

    private final ArtistRepository artistRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final AlbumArtistRepository albumArtistRepository;
    private final TrackRepository trackRepository;
    private final AlbumRepository albumRepository;
    private final AlbumTrackRepository albumTrackRepository;
    private final Cache<Long, byte[]> imageCache;
    private final Cache<Long, byte[]> coverCache;

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public ArtistService(ArtistRepository artistRepository,
                         TrackArtistRepository trackArtistRepository,
                         AlbumArtistRepository albumArtistRepository,
                         TrackRepository trackRepository,
                         AlbumRepository albumRepository,
                         AlbumTrackRepository albumTrackRepository) {
        this.artistRepository = artistRepository;
        this.trackArtistRepository = trackArtistRepository;
        this.albumArtistRepository = albumArtistRepository;
        this.trackRepository = trackRepository;
        this.albumRepository = albumRepository;
        this.albumTrackRepository = albumTrackRepository;
        this.imageCache = Caffeine.newBuilder()
                .maximumSize(300)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
        this.coverCache = Caffeine.newBuilder()
                .maximumSize(300)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    public Mono<PageResponse<ArtistDTO>> getUserArtists(Long userId, String searchQuery, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            String query = searchQuery.trim();
            Mono<List<ArtistDTO>> content = artistRepository.searchByUserId(userId, query, pageable)
                    .flatMap(this::artistToDto)
                    .collectList();
            Mono<Long> total = artistRepository.countByUserIdAndSearch(userId, query);
            return Mono.zip(content, total,
                    (artists, totalElements) -> PageResponse.of(artists, totalElements, page, size));
        }
        Mono<List<ArtistDTO>> content = artistRepository.findByUserId(userId, pageable)
                .flatMap(this::artistToDto)
                .collectList();
        Mono<Long> total = artistRepository.countByUserId(userId);
        return Mono.zip(content, total,
                (artists, totalElements) -> PageResponse.of(artists, totalElements, page, size));
    }

    public Mono<List<ArtistDTO>> getUserArtistsList(Long userId) {
        return artistRepository.findSimpleByUserId(userId)
                .map(artist -> {
                    ArtistDTO dto = new ArtistDTO();
                    dto.setId(artist.getId());
                    dto.setName(artist.getName());
                    return dto;
                })
                .collectList();
    }

    public Mono<ArtistDTO> getArtistById(Long artistId, Long userId) {
        return artistRepository.findById(artistId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Artist", artistId)))
                .flatMap(artist -> {
                    if (!artist.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Artist", artistId));
                    }
                    return artistToDto(artist);
                });
    }

    public Mono<ArtistDTO> createArtist(String name, FilePart image, Long userId) {
        return artistRepository.findByUserIdAndName(userId, name)
                .flatMap(existing -> Mono.<Artist>error(new ResourceAlreadyExistsException("Artist with this name already exists")))
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
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Artist", artistId)))
                .flatMap(artist -> {
                    if (!artist.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Artist", artistId));
                    }

                    if (name != null && !name.trim().isEmpty() && !name.equals(artist.getName())) {
                        return artistRepository.findByUserIdAndName(userId, name.trim())
                                .flatMap(existing -> {
                                    if (!existing.getId().equals(artistId)) {
                                        return Mono.<Artist>error(new ResourceAlreadyExistsException("Artist with this name already exists"));
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
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Artist", artistId)))
                .flatMap(artist -> {
                    if (!artist.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Artist", artistId));
                    }

                    return cascadeDeleteArtistTracks(artistId)
                            .then(cascadeDeleteArtistAlbums(artistId))
                            .then(trackArtistRepository.deleteByArtistId(artistId))
                            .then(albumArtistRepository.deleteByArtistId(artistId))
                            .then(Mono.fromCallable(() -> {
                                if (artist.getImagePath() != null) {
                                    Path imgPath = Paths.get(storageBasePath, artist.getImagePath());
                                    FileUtils.deleteIfExists(imgPath);
                                    imageCache.invalidate(artistId);
                                }
                                return true;
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .then(artistRepository.deleteById(artistId));
                })
                .doOnSuccess(v -> log.info("Artist cascade deleted: {} by user {}", artistId, userId));
    }

    private Mono<Void> cascadeDeleteArtistTracks(Long artistId) {
        return trackArtistRepository.findByArtistId(artistId)
                .flatMap(ta -> trackArtistRepository.countByTrackId(ta.getTrackId())
                        .filter(count -> count <= 1)
                        .flatMap(count -> deleteSingleTrack(ta.getTrackId())))
                .then();
    }

    private Mono<Void> deleteSingleTrack(Long trackId) {
        return trackRepository.findById(trackId)
                .flatMap(track -> {
                    Path trackPath = Paths.get(storageBasePath, track.getFilePath());
                    return trackArtistRepository.deleteByTrackId(trackId)
                            .then(Mono.fromCallable(() -> {
                                FileUtils.deleteIfExists(trackPath);
                                if (track.getCoverPath() != null) {
                                    Path coverPath = Paths.get(storageBasePath, track.getCoverPath());
                                    FileUtils.deleteIfExists(coverPath);
                                }
                                return true;
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .then(trackRepository.deleteById(trackId));
                })
                .onErrorResume(e -> {
                    log.warn("Could not delete track {} during cascade: {}", trackId, e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> cascadeDeleteArtistAlbums(Long artistId) {
        return albumArtistRepository.findByArtistId(artistId)
                .flatMap(aa -> albumArtistRepository.countByAlbumId(aa.getAlbumId())
                        .filter(count -> count <= 1)
                        .flatMap(count -> deleteSingleAlbum(aa.getAlbumId())))
                .then();
    }

    private Mono<Void> deleteSingleAlbum(Long albumId) {
        return albumRepository.findById(albumId)
                .flatMap(album -> {
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
                            .then(deleteAlbumTracks(album));
                })
                .onErrorResume(e -> {
                    log.warn("Could not delete album {} during cascade: {}", albumId, e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> deleteAlbumTracks(Album album) {
        return albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                .flatMap(at -> trackArtistRepository.countByTrackId(at.getTrackId())
                        .flatMap(count -> {
                            if (count <= 1) {
                                return trackArtistRepository.deleteByTrackId(at.getTrackId())
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
                                        });
                            }
                            return Mono.empty();
                        }))
                .then(albumTrackRepository.deleteByAlbumId(album.getId()))
                .then(albumRepository.deleteById(album.getId()));
    }

    private Mono<Artist> saveArtistImage(Artist artist, FilePart image) {
        String imageExt = FileUtils.getExtension(image.filename());
        String imageId = UUID.randomUUID().toString();
        String relativePath = artist.getUserId() + "/artists/" + imageId + "." + imageExt;
        Path fullPath = Paths.get(storageBasePath, relativePath);

        return Mono.fromCallable(() -> {
                    FileUtils.createDirectories(fullPath.getParent());
                    return fullPath;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(fp -> image.transferTo(fp).thenReturn(fp))
                .flatMap(fp -> {
                    if (artist.getImagePath() != null) {
                        try {
                            Path oldPath = Paths.get(storageBasePath, artist.getImagePath());
                            FileUtils.deleteIfExists(oldPath);
                            imageCache.invalidate(artist.getId());
                        } catch (Exception ignored) {
                        }
                    }
                    artist.setImagePath(relativePath);
                    return artistRepository.save(artist);
                });
    }

    public Mono<PageResponse<TrackDTO>> getArtistTracks(Long artistId, Long userId, int page, int size) {
        return artistRepository.findById(artistId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Artist", artistId)))
                .flatMap(artist -> {
                    if (!artist.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Artist", artistId));
                    }
                    Pageable pageable = PageRequest.of(page, size);
                    Mono<List<TrackDTO>> content = trackArtistRepository.findByArtistId(artistId, pageable)
                            .flatMap(ta -> trackRepository.findById(ta.getTrackId()))
                            .collectList()
                            .flatMap(tracks -> {
                                List<Mono<TrackDTO>> dtos = tracks.stream()
                                        .map(this::trackToDto)
                                        .toList();
                                return Flux.concat(dtos).collectList();
                            });
                    Mono<Long> total = trackArtistRepository.countByArtistId(artistId);
                    return Mono.zip(content, total,
                            (tracks, totalElements) -> PageResponse.of(tracks, totalElements, page, size));
                });
    }

    public Mono<PageResponse<AlbumDTO>> getArtistAlbums(Long artistId, Long userId, int page, int size) {
        return artistRepository.findById(artistId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Artist", artistId)))
                .flatMap(artist -> {
                    if (!artist.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Artist", artistId));
                    }
                    Pageable pageable = PageRequest.of(page, size);
                    Mono<List<AlbumDTO>> content = albumArtistRepository.findByArtistId(artistId, pageable)
                            .flatMap(aa -> albumRepository.findById(aa.getAlbumId()))
                            .collectList()
                            .flatMap(albums -> {
                                List<Mono<AlbumDTO>> dtos = albums.stream()
                                        .map(this::albumToSimpleDto)
                                        .toList();
                                return Flux.concat(dtos).collectList();
                            });
                    Mono<Long> total = albumArtistRepository.countByArtistId(artistId);
                    return Mono.zip(content, total,
                            (albums, totalElements) -> PageResponse.of(albums, totalElements, page, size));
                });
    }

    private Mono<TrackDTO> trackToDto(Track track) {
        Mono<List<ArtistDTO>> artistsMono = trackArtistRepository.findByTrackIdOrderByPosition(track.getId())
                .concatMap(ta -> artistRepository.findById(ta.getArtistId()))
                .collectList()
                .flatMap(this::artistListToDtoList);

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
            TrackDTO dto = TrackDTO.fromEntity(track);
            dto.setArtists(artists);
            return dto;
        });
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

    private Mono<List<ArtistDTO>> artistListToDtoList(List<Artist> artists) {
        List<Mono<ArtistDTO>> dtos = artists.stream()
                .map(a -> Mono.fromCallable(() -> {
                    ArtistDTO adto = new ArtistDTO();
                    adto.setId(a.getId());
                    adto.setName(a.getName());
                    return adto;
                }))
                .toList();
        return Flux.concat(dtos).collectList();
    }

    private Mono<AlbumDTO> albumToSimpleDto(Album album) {
        AlbumDTO dto = new AlbumDTO();
        dto.setId(album.getId());
        dto.setTitle(album.getTitle());
        dto.setReleaseDate(album.getReleaseDate());
        dto.setUserId(album.getUserId());
        Mono<List<ArtistDTO>> artistsMono = albumArtistRepository.findByAlbumIdOrderByPosition(album.getId())
                .concatMap(aa -> artistRepository.findById(aa.getArtistId()))
                .collectList()
                .flatMap(this::artistListToDtoList);
        Mono<Integer> countMono = albumTrackRepository.countByAlbumId(album.getId()).map(Long::intValue);
        return Mono.zip(artistsMono, countMono, (artists, count) -> {
            dto.setArtists(artists);
            dto.setTrackCount(count);
            return dto;
        });
    }

    public record ArtistDownloadEntry(String zipPath, Path filePath) {
    }

    public record ArtistDownloadData(String artistName, List<ArtistDownloadEntry> entries) {
    }

    public Mono<ArtistDownloadData> getArtistDownloadData(Long artistId, Long userId) {
        return artistRepository.findById(artistId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Artist", artistId)))
                .flatMap(artist -> {
                    if (!artist.getUserId().equals(userId)) {
                        return Mono.error(new ResourceNotFoundException("Artist", artistId));
                    }

                    Mono<List<ArtistDownloadEntry>> albumEntries = albumArtistRepository.findByArtistId(artistId)
                            .flatMap(aa -> albumRepository.findById(aa.getAlbumId()))
                            .flatMap(album -> albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                                    .concatMap(at -> trackRepository.findById(at.getTrackId())
                                            .map(track -> new ArtistDownloadEntry(
                                                    album.getTitle() + "/" + track.getTitle() + "."
                                                            + FileUtils.getExtension(track.getFilePath()),
                                                    Paths.get(storageBasePath, track.getFilePath())
                                            )))
                            )
                            .collectList();

                    Mono<List<ArtistDownloadEntry>> standaloneEntries = albumTrackRepository.findAllTrackIds()
                            .collectList()
                            .flatMap(albumTrackIds -> {
                                Set<Long> albumTrackIdSet = new HashSet<>(albumTrackIds);
                                return trackArtistRepository.findByArtistId(artistId)
                                        .filter(ta -> !albumTrackIdSet.contains(ta.getTrackId()))
                                        .flatMap(ta -> trackRepository.findById(ta.getTrackId()))
                                        .map(track -> new ArtistDownloadEntry(
                                                track.getTitle() + "."
                                                        + FileUtils.getExtension(track.getFilePath()),
                                                Paths.get(storageBasePath, track.getFilePath())
                                        ))
                                        .collectList();
                            });

                    return Mono.zip(albumEntries, standaloneEntries, (albums, standalones) -> {
                        List<ArtistDownloadEntry> all = new java.util.ArrayList<>();
                        all.addAll(albums);
                        all.addAll(standalones);
                        return new ArtistDownloadData(artist.getName(), all);
                    });
                });
    }

    private Mono<ArtistDTO> artistToDto(Artist artist) {
        Mono<Long> trackCountMono = trackArtistRepository.countByArtistId(artist.getId());
        Mono<Long> albumCountMono = albumArtistRepository.countByArtistId(artist.getId());

        return Mono.zip(trackCountMono, albumCountMono)
                .flatMap(counts -> Mono.fromCallable(() -> {
                    ArtistDTO dto = new ArtistDTO();
                    dto.setId(artist.getId());
                    dto.setName(artist.getName());
                    dto.setUserId(artist.getUserId());
                    dto.setTrackCount(counts.getT1().intValue());
                    dto.setAlbumCount(counts.getT2().intValue());

                    if (artist.getImagePath() != null) {
                        byte[] cached = imageCache.getIfPresent(artist.getId());
                        if (cached == null) {
                            try {
                                Path path = Paths.get(storageBasePath, artist.getImagePath());
                                cached = FileUtils.readAllBytes(path);
                                imageCache.put(artist.getId(), cached);
                            } catch (Exception e) {
                                log.warn("Could not load image for artist {}", artist.getId());
                            }
                        }
                        if (cached != null) {
                            String mimeType = FileUtils.getMimeType(artist.getImagePath());
                            String base64 = Base64.getEncoder().encodeToString(cached);
                            dto.setImage("data:" + mimeType + ";base64," + base64);
                        }
                    }
                    return dto;
                }).subscribeOn(Schedulers.boundedElastic()));
    }
}
