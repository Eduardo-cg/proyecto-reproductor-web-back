package com.musicstreaming.artist.service;

import com.musicstreaming.album.entity.AlbumArtist;
import com.musicstreaming.album.repository.AlbumArtistRepository;
import com.musicstreaming.artist.dto.ArtistDTO;
import com.musicstreaming.artist.entity.Artist;
import com.musicstreaming.artist.repository.ArtistRepository;
import com.musicstreaming.common.util.FileUtils;
import com.musicstreaming.track.entity.TrackArtist;
import com.musicstreaming.track.repository.TrackArtistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class ArtistLinkService {

    private static final Logger log = LoggerFactory.getLogger(ArtistLinkService.class);

    private final ArtistRepository artistRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final AlbumArtistRepository albumArtistRepository;

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public ArtistLinkService(ArtistRepository artistRepository,
                             TrackArtistRepository trackArtistRepository,
                             AlbumArtistRepository albumArtistRepository) {
        this.artistRepository = artistRepository;
        this.trackArtistRepository = trackArtistRepository;
        this.albumArtistRepository = albumArtistRepository;
    }

    public Mono<Void> saveTrackArtists(Long trackId, List<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()) {
            return Mono.empty();
        }
        List<Mono<TrackArtist>> saves = new ArrayList<>();
        for (int i = 0; i < artistIds.size(); i++) {
            Long artistId = artistIds.get(i);
            TrackArtist ta = new TrackArtist();
            ta.setTrackId(trackId);
            ta.setArtistId(artistId);
            ta.setPosition(i + 1);
            ta.setCreatedAt(LocalDateTime.now());
            saves.add(trackArtistRepository.save(ta));
        }
        return Flux.concat(saves).then();
    }

    public Mono<Void> saveAlbumArtists(Long albumId, List<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()) {
            return Mono.empty();
        }
        List<Mono<AlbumArtist>> saves = new ArrayList<>();
        for (int i = 0; i < artistIds.size(); i++) {
            Long artistId = artistIds.get(i);
            AlbumArtist aa = new AlbumArtist();
            aa.setAlbumId(albumId);
            aa.setArtistId(artistId);
            aa.setPosition(i + 1);
            aa.setCreatedAt(LocalDateTime.now());
            saves.add(albumArtistRepository.save(aa));
        }
        return Flux.concat(saves).then();
    }

    public Mono<List<ArtistDTO>> getTrackArtists(Long trackId) {
        return trackArtistRepository.findByTrackIdOrderByPosition(trackId)
                .concatMap(ta -> artistRepository.findById(ta.getArtistId()))
                .collectList()
                .flatMap(this::toArtistDtoList);
    }

    public Mono<List<ArtistDTO>> getAlbumArtists(Long albumId) {
        return albumArtistRepository.findByAlbumIdOrderByPosition(albumId)
                .concatMap(aa -> artistRepository.findById(aa.getArtistId()))
                .collectList()
                .flatMap(this::toArtistDtoList);
    }

    private Mono<List<ArtistDTO>> toArtistDtoList(List<Artist> artists) {
        List<Mono<ArtistDTO>> dtos = new ArrayList<>();
        for (Artist a : artists) {
            dtos.add(artistToDtoSimple(a));
        }
        return Flux.concat(dtos).collectList();
    }

    public Mono<ArtistDTO> artistToDtoSimple(Artist artist) {
        return Mono.fromCallable(() -> {
            ArtistDTO dto = new ArtistDTO();
            dto.setId(artist.getId());
            dto.setName(artist.getName());
            dto.setUserId(artist.getUserId());
            if (artist.getImagePath() != null) {
                try {
                    Path path = Paths.get(storageBasePath, artist.getImagePath());
                    byte[] bytes = FileUtils.readAllBytes(path);
                    String mimeType = FileUtils.getMimeType(artist.getImagePath());
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    dto.setImage("data:" + mimeType + ";base64," + base64);
                } catch (Exception e) {
                    log.warn("Could not load image for artist {}", artist.getId());
                }
            }
            return dto;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
