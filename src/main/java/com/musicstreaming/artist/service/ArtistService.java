package com.musicstreaming.artist.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.musicstreaming.album.dto.AlbumDTO;
import com.musicstreaming.album.entity.Album;
import com.musicstreaming.album.entity.AlbumArtist;
import com.musicstreaming.album.entity.AlbumTrack;
import com.musicstreaming.album.repository.AlbumArtistRepository;
import com.musicstreaming.album.repository.AlbumRepository;
import com.musicstreaming.album.repository.AlbumTrackRepository;
import com.musicstreaming.artist.dto.ArtistDTO;
import com.musicstreaming.artist.entity.Artist;
import com.musicstreaming.artist.repository.ArtistRepository;
import com.musicstreaming.common.cache.CoverService;
import com.musicstreaming.common.dto.PageResponse;
import com.musicstreaming.common.exception.ResourceAlreadyExistsException;
import com.musicstreaming.common.service.ReactiveFileService;
import com.musicstreaming.common.service.ZipDownloadService;
import com.musicstreaming.common.util.FileUtils;
import com.musicstreaming.common.util.FilenameSanitizer;
import com.musicstreaming.common.util.OwnershipValidator;
import com.musicstreaming.track.dto.TrackDTO;
import com.musicstreaming.track.entity.Track;
import com.musicstreaming.track.entity.TrackArtist;
import com.musicstreaming.track.repository.TrackArtistRepository;
import com.musicstreaming.track.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArtistService {

    private final ArtistRepository artistRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final AlbumArtistRepository albumArtistRepository;
    private final TrackRepository trackRepository;
    private final AlbumRepository albumRepository;
    private final AlbumTrackRepository albumTrackRepository;
    private final ReactiveFileService reactiveFileService;
    private final CoverService coverService;
    private final ZipDownloadService zipDownloadService;
    private final TransactionalOperator transactionalOperator;
    private final Cache<Long, byte[]> artistImageCache;
    private final Cache<Long, byte[]> artistTrackCoverCache;

    public Mono<PageResponse<ArtistDTO>> getUserArtists(Long userId, String searchQuery, int page, int size) {
        Sort sort = Sort.by(Sort.Direction.ASC, "name");
        Pageable pageable = PageRequest.of(page, size, sort);

        boolean hasSearch = searchQuery != null && !searchQuery.trim().isEmpty();
        String query = hasSearch ? searchQuery.trim().replace(" ", "") : null;

        Mono<List<ArtistDTO>> content;
        Mono<Long> total;

        if (hasSearch) {
            content = artistRepository.searchByUserId(userId, query, pageable)
                    .concatMap(this::artistToDto)
                    .collectList();
            total = artistRepository.countByUserIdAndSearch(userId, query);
        } else {
            content = artistRepository.findByUserId(userId, pageable)
                    .concatMap(this::artistToDto)
                    .collectList();
            total = artistRepository.countByUserId(userId);
        }

        return Mono.zip(content, total, (artists, totalElements) -> PageResponse.of(artists, totalElements, page, size));
    }

    public Mono<PageResponse<ArtistDTO>> getUserArtistsList(Long userId, String search, int page, int size) {
        Sort sort = Sort.by(Sort.Direction.ASC, "name");
        PageRequest pageable = PageRequest.of(page, size, sort);

        boolean hasSearch = search != null && !search.trim().isEmpty();
        String query = hasSearch ? search.trim().replace(" ", "") : null;

        Mono<List<ArtistDTO>> content;
        Mono<Long> total;

        if (hasSearch) {
            content = artistRepository.searchByUserId(userId, query, pageable)
                    .concatMap(this::artistToSimpleDto)
                    .collectList();
            total = artistRepository.countByUserIdAndSearch(userId, query);
        } else {
            content = artistRepository.findByUserId(userId, pageable)
                    .concatMap(this::artistToSimpleDto)
                    .collectList();
            total = artistRepository.countByUserId(userId);
        }

        return Mono.zip(content, total, (artists, totalElements) -> PageResponse.of(artists, totalElements, page, size));
    }

    private Mono<ArtistDTO> artistToSimpleDto(Artist artist) {
        ArtistDTO dto = new ArtistDTO();
        dto.setId(artist.getId());
        dto.setName(artist.getName());
        return Mono.just(dto);
    }

    public Mono<ArtistDTO> getArtistById(Long artistId, Long userId) {
        return OwnershipValidator.requireOwnership(
                        artistRepository.findById(artistId), userId, "Artist", artistId, Artist::getUserId)
                .flatMap(this::artistToDto);
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
                .flatMap(this::artistToDto)
                .doOnSuccess(a -> log.info("Artist created userId={} artistId={} name={}", userId, a.getId(), a.getName()));
    }

    public Mono<ArtistDTO> updateArtist(Long artistId, String name, FilePart image, Long userId) {
        return OwnershipValidator.requireOwnership(
                        artistRepository.findById(artistId), userId, "Artist", artistId, Artist::getUserId)
                .flatMap(artist -> validateArtistNameChange(artist, name, artistId, userId))
                .flatMap(artist -> {
                    artist.setUpdatedAt(LocalDateTime.now());
                    if (image != null) {
                        return saveArtistImage(artist, image);
                    }
                    return artistRepository.save(artist);
                })
                .flatMap(this::artistToDto)
                .doOnSuccess(a -> log.info("Artist updated userId={} artistId={} name={}", userId, a.getId(), a.getName()));
    }

    public Mono<Void> deleteArtist(Long artistId, Long userId) {
        return OwnershipValidator.requireOwnership(
                        artistRepository.findById(artistId), userId, "Artist", artistId, Artist::getUserId)
                .flatMap(artist -> {
                    Mono<Void> work = cascadeDeleteArtistTracks(artistId)
                            .then(cascadeDeleteArtistAlbums(artistId))
                            .then(trackArtistRepository.deleteByArtistId(artistId))
                            .then(albumArtistRepository.deleteByArtistId(artistId))
                            .then(cleanupArtistImage(artist))
                            .then(artistRepository.deleteById(artistId));
                    return transactionalOperator.transactional(work);
                })
                .doOnSuccess(v -> log.info("Artist cascade deleted userId={} artistId={}", userId, artistId));
    }

    private Mono<Void> cleanupArtistImage(Artist artist) {
        return Mono.fromRunnable(() -> {
            if (artist.getImagePath() != null) {
                Path imgPath = reactiveFileService.resolvePath(artist.getImagePath());
                try {
                    FileUtils.deleteIfExists(imgPath);
                } catch (Exception e) {
                    log.warn("Failed to delete artist image for artist {}: {}", artist.getId(), e.getMessage());
                }
                artistImageCache.invalidate(artist.getId());
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then();
    }

    private Mono<Artist> validateArtistNameChange(Artist artist, String newName, Long artistId, Long userId) {
        if (newName != null && !newName.trim().isEmpty() && !newName.equals(artist.getName())) {
            return artistRepository.findByUserIdAndName(userId, newName.trim())
                    .flatMap(existing -> {
                        if (!existing.getId().equals(artistId)) {
                            return Mono.<Artist>error(new ResourceAlreadyExistsException("Artist with this name already exists"));
                        }
                        return Mono.just(artist);
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        artist.setName(newName.trim());
                        return Mono.just(artist);
                    }));
        }
        return Mono.just(artist);
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
                    Path trackPath = reactiveFileService.resolvePath(track.getFilePath());
                    return trackArtistRepository.deleteByTrackId(trackId)
                            .then(reactiveFileService.deleteFile(trackPath))
                            .then(deleteCoverIfOrphaned(track.getCoverPath(), trackId))
                            .then(trackRepository.deleteById(trackId));
                })
                .onErrorResume(e -> {
                    log.warn("Could not delete track {} during cascade: {}", trackId, e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> deleteCoverIfOrphaned(String coverPath, Long trackId) {
        if (coverPath == null) {
            return Mono.empty();
        }
        artistTrackCoverCache.invalidate(trackId);
        return Mono.fromRunnable(() -> {
            Path path = reactiveFileService.resolvePath(coverPath);
            try {
                FileUtils.deleteIfExists(path);
            } catch (Exception e) {
                log.warn("Failed to delete orphaned cover for track {}: {}", trackId, e.getMessage());
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then();
    }

    private Mono<Void> cascadeDeleteArtistAlbums(Long artistId) {
        return albumArtistRepository.findByArtistId(artistId)
                .flatMap(aa -> albumArtistRepository.countByAlbumId(aa.getAlbumId())
                        .filter(count -> count <= 1)
                        .flatMap(count -> deleteSingleAlbum(aa.getAlbumId())))
                .then();
    }

    private Mono<Void> deleteSingleAlbum(Long albumId) {
        return albumArtistRepository.countByAlbumId(albumId)
                .flatMap(artistCount -> albumRepository.findById(albumId)
                        .flatMap(album -> {
                            Mono<Void> deleteCover = (artistCount <= 1 && album.getCoverPath() != null)
                                    ? reactiveFileService.deleteFile(reactiveFileService.resolvePath(album.getCoverPath()))
                                    : Mono.empty();
                            return deleteCover
                                    .then(albumArtistRepository.deleteByAlbumId(albumId))
                                    .then(deleteAlbumTracks(album));
                        }))
                .onErrorResume(e -> {
                    log.warn("Could not delete album {} during cascade: {}", albumId, e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> deleteAlbumTracks(Album album) {
        return albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                .flatMap(at -> trackArtistRepository.countByTrackId(at.getTrackId())
                        .flatMap(count -> deleteTrackIfOrphaned(at.getTrackId(), count)))
                .then(albumTrackRepository.deleteByAlbumId(album.getId()))
                .then(albumRepository.deleteById(album.getId()));
    }

    private Mono<Void> deleteTrackIfOrphaned(Long trackId, Long referenceCount) {
        if (referenceCount > 1L) {
            return Mono.empty();
        }
        return trackArtistRepository.deleteByTrackId(trackId)
                .then(trackRepository.findById(trackId))
                .flatMap(track -> {
                    Mono<Void> deleteFile = Mono.empty();
                    if (track.getFilePath() != null) {
                        deleteFile = reactiveFileService.deleteFile(reactiveFileService.resolvePath(track.getFilePath()));
                    }
                    return deleteFile
                            .then(Mono.fromRunnable(() -> {
                                if (track.getCoverPath() != null) {
                                    artistTrackCoverCache.invalidate(trackId);
                                }
                            }))
                            .then(trackRepository.delete(track));
                });
    }

    private Mono<Artist> saveArtistImage(Artist artist, FilePart image) {
        return reactiveFileService.replaceCover(
                        image, artist.getUserId(), "artists", artist.getImagePath(), artist.getId(), artistImageCache)
                .flatMap(newImagePath -> {
                    artist.setImagePath(newImagePath);
                    return artistRepository.save(artist);
                });
    }

    public Mono<PageResponse<TrackDTO>> getArtistTracks(Long artistId, Long userId, int page, int size) {
        return OwnershipValidator.requireOwnership(
                        artistRepository.findById(artistId), userId, "Artist", artistId, Artist::getUserId)
                .flatMap(artist -> {
                    Pageable pageable = PageRequest.of(page, size);
                    Mono<List<TrackDTO>> content = trackArtistRepository.findByArtistId(artistId, pageable)
                            .map(TrackArtist::getTrackId)
                            .collectList()
                            .flatMap(this::buildTracksWithArtistsBatch);
                    Mono<Long> total = trackArtistRepository.countByArtistId(artistId);
                    return Mono.zip(content, total,
                            (tracks, totalElements) -> PageResponse.of(tracks, totalElements, page, size));
                });
    }

    public Mono<PageResponse<AlbumDTO>> getArtistAlbums(Long artistId, Long userId, int page, int size) {
        return OwnershipValidator.requireOwnership(
                        artistRepository.findById(artistId), userId, "Artist", artistId, Artist::getUserId)
                .flatMap(artist -> {
                    Pageable pageable = PageRequest.of(page, size);
                    Mono<List<AlbumDTO>> content = albumArtistRepository.findByArtistId(artistId, pageable)
                            .map(AlbumArtist::getAlbumId)
                            .collectList()
                            .flatMap(this::buildAlbumsWithArtistsBatch);
                    Mono<Long> total = albumArtistRepository.countByArtistId(artistId);
                    return Mono.zip(content, total,
                            (albums, totalElements) -> PageResponse.of(albums, totalElements, page, size));
                });
    }

    private Mono<List<TrackDTO>> buildTracksWithArtistsBatch(List<Long> trackIds) {
        if (trackIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return trackRepository.findAllById(trackIds)
                .collectList()
                .flatMap(tracks -> {
                    Map<Long, Track> trackMap = new HashMap<>();
                    for (Track t : tracks) {
                        trackMap.put(t.getId(), t);
                    }
                    return trackArtistRepository.findByTrackIdIn(trackIds)
                            .collectList()
                            .flatMap(tas -> {
                                Set<Long> artistIds = new HashSet<>();
                                for (TrackArtist ta : tas) {
                                    artistIds.add(ta.getArtistId());
                                }
                                Mono<Map<Long, Artist>> artistMapMono = artistIds.isEmpty()
                                        ? Mono.just(Map.of())
                                        : artistRepository.findAllById(artistIds)
                                          .collectList()
                                          .map(list -> {
                                              Map<Long, Artist> m = new HashMap<>();
                                              for (Artist a : list) {
                                                  m.put(a.getId(), a);
                                              }
                                              return m;
                                          });

                                return artistMapMono.map(artistMap -> {
                                    Map<Long, List<Artist>> artistsByTrack = new HashMap<>();
                                    for (TrackArtist ta : tas) {
                                        Artist a = artistMap.get(ta.getArtistId());
                                        if (a == null) continue;
                                        artistsByTrack.computeIfAbsent(ta.getTrackId(), k -> new ArrayList<>())
                                                .add(a);
                                    }
                                    return trackIds.stream()
                                            .map(trackMap::get)
                                            .filter(Objects::nonNull)
                                            .map(t -> mapTrackToDtoBatch(t, artistsByTrack.getOrDefault(t.getId(), List.of())))
                                            .toList();
                                });
                            });
                });
    }

    private TrackDTO mapTrackToDtoBatch(Track track, List<Artist> artists) {
        TrackDTO dto = TrackDTO.fromEntity(track, null);
        List<ArtistDTO> artistDtos = artists.stream()
                .map(this::toArtistDtoBlocking)
                .toList();
        dto.setArtists(artistDtos);
        return dto;
    }

    private ArtistDTO toArtistDtoBlocking(Artist artist) {
        ArtistDTO adto = new ArtistDTO();
        adto.setId(artist.getId());
        adto.setName(artist.getName());
        adto.setUserId(artist.getUserId());
        return adto;
    }

    private Mono<List<AlbumDTO>> buildAlbumsWithArtistsBatch(List<Long> albumIds) {
        if (albumIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return albumRepository.findAllById(albumIds)
                .collectList()
                .flatMap(albums -> {
                    Map<Long, Album> albumMap = new HashMap<>();
                    for (Album a : albums) {
                        albumMap.put(a.getId(), a);
                    }
                    return albumArtistRepository.findByAlbumIdIn(albumIds)
                            .collectList()
                            .flatMap(aas -> {
                                Set<Long> artistIds = new HashSet<>();
                                for (AlbumArtist aa : aas) {
                                    artistIds.add(aa.getArtistId());
                                }
                                Mono<Map<Long, Artist>> artistMapMono = artistIds.isEmpty()
                                        ? Mono.just(Map.of())
                                        : artistRepository.findAllById(artistIds)
                                          .collectList()
                                          .map(list -> {
                                              Map<Long, Artist> m = new HashMap<>();
                                              for (Artist a : list) {
                                                  m.put(a.getId(), a);
                                              }
                                              return m;
                                          });

                                return Mono.zip(artistMapMono, albumTrackRepository.findByAlbumIdIn(albumIds).collectList())
                                        .map(tuple -> {
                                            Map<Long, Artist> artistMap = tuple.getT1();
                                            List<AlbumTrack> albumTracks = tuple.getT2();
                                            Map<Long, Integer> trackCountByAlbum = new HashMap<>();
                                            for (AlbumTrack at : albumTracks) {
                                                trackCountByAlbum.merge(at.getAlbumId(), 1, Integer::sum);
                                            }
                                            Map<Long, List<Artist>> artistsByAlbum = new HashMap<>();
                                            for (AlbumArtist aa : aas) {
                                                Artist a = artistMap.get(aa.getArtistId());
                                                if (a == null) continue;
                                                artistsByAlbum.computeIfAbsent(aa.getAlbumId(), k -> new ArrayList<>())
                                                        .add(a);
                                            }
                                            return albumIds.stream()
                                                    .map(albumMap::get)
                                                    .filter(Objects::nonNull)
                                                    .map(album -> mapAlbumToSimpleDtoBatch(album,
                                                            artistsByAlbum.getOrDefault(album.getId(), List.of()),
                                                            trackCountByAlbum.getOrDefault(album.getId(), 0)))
                                                    .toList();
                                        });
                            });
                });
    }

    private AlbumDTO mapAlbumToSimpleDtoBatch(Album album, List<Artist> artists, int trackCount) {
        AlbumDTO dto = new AlbumDTO();
        dto.setId(album.getId());
        dto.setTitle(album.getTitle());
        dto.setReleaseDate(album.getReleaseDate());
        dto.setUserId(album.getUserId());
        dto.setTrackCount(trackCount);
        List<ArtistDTO> artistDtos = artists.stream()
                .map(this::toArtistDtoBlocking)
                .toList();
        dto.setArtistsNames(artistDtos);
        return dto;
    }

    private record ArtistDownloadEntry(String zipPath, Path filePath) {
    }

    private record ArtistDownloadData(String artistName, List<ArtistDownloadEntry> entries) {
    }

    private Mono<ArtistDownloadData> getArtistDownloadData(Long artistId, Long userId) {
        return OwnershipValidator.requireOwnership(
                        artistRepository.findById(artistId), userId, "Artist", artistId, Artist::getUserId)
                .flatMap(artist -> {
                    Mono<List<ArtistDownloadEntry>> albumEntries = fetchAlbumTrackEntries(artistId);
                    Mono<List<ArtistDownloadEntry>> standaloneEntries = fetchStandaloneTrackEntries(artistId);

                    return Mono.zip(albumEntries, standaloneEntries, (albums, standalones) -> {
                        List<ArtistDownloadEntry> all = new ArrayList<>();
                        all.addAll(albums);
                        all.addAll(standalones);
                        return new ArtistDownloadData(FilenameSanitizer.sanitize(artist.getName()), all);
                    });
                });
    }

    private Mono<List<ArtistDownloadEntry>> fetchAlbumTrackEntries(Long artistId) {
        return albumArtistRepository.findByArtistId(artistId)
                .flatMap(aa -> albumRepository.findById(aa.getAlbumId()))
                .flatMap(album -> albumTrackRepository.findByAlbumIdOrderByPosition(album.getId())
                        .concatMap(at -> trackRepository.findById(at.getTrackId())
                                .map(track -> new ArtistDownloadEntry(
                                        FilenameSanitizer.sanitize(album.getTitle()) + "/"
                                                + FilenameSanitizer.sanitize(track.getTitle()) + "."
                                                + FileUtils.getExtension(track.getFilePath()),
                                        reactiveFileService.resolvePath(track.getFilePath())
                                )))
                )
                .collectList();
    }

    private Mono<List<ArtistDownloadEntry>> fetchStandaloneTrackEntries(Long artistId) {
        return albumTrackRepository.findAllTrackIds()
                .collectList()
                .flatMap(albumTrackIds -> {
                    Set<Long> albumTrackIdSet = new HashSet<>(albumTrackIds);
                    return trackArtistRepository.findByArtistId(artistId)
                            .filter(ta -> !albumTrackIdSet.contains(ta.getTrackId()))
                            .flatMap(ta -> trackRepository.findById(ta.getTrackId()))
                            .map(track -> new ArtistDownloadEntry(
                                    FilenameSanitizer.sanitize(track.getTitle()) + "."
                                            + FileUtils.getExtension(track.getFilePath()),
                                    reactiveFileService.resolvePath(track.getFilePath())
                            ))
                            .collectList();
                });
    }

    public Mono<Void> downloadArtist(Long artistId, Long userId, ServerHttpResponse response) {
        return getArtistDownloadData(artistId, userId)
                .flatMap(data -> {
                    String zipName = data.artistName() + ".zip";
                    List<ZipDownloadService.ZipEntryData> entries = data.entries().stream()
                            .map(e -> new ZipDownloadService.ZipEntryData(
                                    e.zipPath().replace("\\", "/"), e.filePath()))
                            .toList();
                    return zipDownloadService.createAndSendZip(entries, zipName, response);
                });
    }

    private Mono<ArtistDTO> artistToDto(Artist artist) {
        Mono<Long> trackCountMono = trackArtistRepository.countByArtistId(artist.getId());
        Mono<Long> albumCountMono = albumArtistRepository.countByArtistId(artist.getId());

        return Mono.zip(trackCountMono, albumCountMono)
                .flatMap(counts -> coverService.toBase64DataUri(artist.getImagePath(), artist.getId(), artistImageCache)
                        .map(imageUri -> {
                            ArtistDTO dto = new ArtistDTO();
                            dto.setId(artist.getId());
                            dto.setName(artist.getName());
                            dto.setUserId(artist.getUserId());
                            dto.setTrackCount(counts.getT1().intValue());
                            dto.setAlbumCount(counts.getT2().intValue());
                            dto.setImage(imageUri);
                            return dto;
                        })
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
    }
}
