package com.musicstreaming.artist.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.musicstreaming.album.entity.AlbumArtist;
import com.musicstreaming.album.repository.AlbumArtistRepository;
import com.musicstreaming.artist.dto.ArtistDTO;
import com.musicstreaming.artist.entity.Artist;
import com.musicstreaming.artist.repository.ArtistRepository;
import com.musicstreaming.common.cache.CoverService;
import com.musicstreaming.track.entity.TrackArtist;
import com.musicstreaming.track.repository.TrackArtistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ArtistLinkService {

    private final ArtistRepository artistRepository;
    private final TrackArtistRepository trackArtistRepository;
    private final AlbumArtistRepository albumArtistRepository;
    private final CoverService coverService;
    private final Cache<Long, byte[]> artistImageCache;

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

    private Mono<ArtistDTO> artistToDtoSimple(Artist artist) {
        return coverService.toBase64DataUri(artist.getImagePath(), artist.getId(), artistImageCache)
                .map(imageUri -> {
                    ArtistDTO dto = new ArtistDTO();
                    dto.setId(artist.getId());
                    dto.setName(artist.getName());
                    dto.setUserId(artist.getUserId());
                    dto.setImage(imageUri);
                    return dto;
                });
    }
}
