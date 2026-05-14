package com.musicstreaming.domain.service;

import com.musicstreaming.adapter.dto.PlaylistDTO;
import com.musicstreaming.adapter.dto.PlaylistCreateRequest;
import com.musicstreaming.adapter.dto.PlaylistUpdateRequest;
import com.musicstreaming.adapter.dto.TrackDTO;
import com.musicstreaming.domain.model.Playlist;
import com.musicstreaming.domain.model.PlaylistTrack;
import com.musicstreaming.domain.model.Track;
import com.musicstreaming.domain.repository.PlaylistRepository;
import com.musicstreaming.domain.repository.PlaylistTrackRepository;
import com.musicstreaming.domain.repository.TrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PlaylistService {

    private static final Logger log = LoggerFactory.getLogger(PlaylistService.class);

    private final PlaylistRepository playlistRepository;
    private final PlaylistTrackRepository playlistTrackRepository;
    private final TrackRepository trackRepository;

    public PlaylistService(PlaylistRepository playlistRepository,
                          PlaylistTrackRepository playlistTrackRepository,
                          TrackRepository trackRepository) {
        this.playlistRepository = playlistRepository;
        this.playlistTrackRepository = playlistTrackRepository;
        this.trackRepository = trackRepository;
    }

    public Mono<PlaylistDTO> createPlaylist(Long userId, PlaylistCreateRequest request) {
        Playlist playlist = new Playlist(
                userId,
                request.getName(),
                request.getDescription(),
                request.getIsPublic()
        );

        return playlistRepository.save(playlist)
                .map(PlaylistDTO::fromEntity)
                .doOnSuccess(p -> log.info("Playlist created: {} for user {}", p.getName(), userId));
    }

    @Cacheable(value = "playlists", key = "#playlistId")
    public Mono<PlaylistDTO> getPlaylist(Long playlistId, Long userId) {
        return playlistRepository.findById(playlistId)
                .switchIfEmpty(Mono.error(new RuntimeException("Playlist not found")))
                .flatMap(playlist -> {
                    if (!playlist.getIsPublic() && !playlist.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Access denied"));
                    }
                    return getPlaylistWithTracks(playlist);
                });
    }

    public Flux<PlaylistDTO> getUserPlaylists(Long userId) {
        return playlistRepository.findByUserId(userId)
                .map(PlaylistDTO::fromEntity);
    }

    @Cacheable(value = "playlists", key = "#playlist.id")
    public Mono<PlaylistDTO> getPlaylistWithTracks(Playlist playlist) {
        return playlistTrackRepository.findByPlaylistIdOrderByPositionAsc(playlist.getId())
                .flatMap(pt -> trackRepository.findById(pt.getTrackId()).map(TrackDTO::fromEntity))
                .collectList()
                .map(tracks -> PlaylistDTO.fromEntity(playlist).withTracks(tracks));
    }

    @CacheEvict(value = "playlists", key = "#playlistId")
    public Mono<PlaylistDTO> updatePlaylist(Long playlistId, Long userId, PlaylistUpdateRequest request) {
        return playlistRepository.findById(playlistId)
                .switchIfEmpty(Mono.error(new RuntimeException("Playlist not found")))
                .flatMap(playlist -> {
                    if (!playlist.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Access denied"));
                    }

                    if (request.getName() != null) {
                        playlist.setName(request.getName());
                    }
                    if (request.getDescription() != null) {
                        playlist.setDescription(request.getDescription());
                    }
                    if (request.getIsPublic() != null) {
                        playlist.setIsPublic(request.getIsPublic());
                    }
                    playlist.setUpdatedAt(LocalDateTime.now());

                    return playlistRepository.save(playlist);
                })
                .map(PlaylistDTO::fromEntity)
                .doOnSuccess(p -> log.info("Playlist updated: {}", p.getName()));
    }

    @CacheEvict(value = "playlists", key = "#playlistId")
    public Mono<Void> deletePlaylist(Long playlistId, Long userId) {
        return playlistRepository.findById(playlistId)
                .switchIfEmpty(Mono.error(new RuntimeException("Playlist not found")))
                .flatMap(playlist -> {
                    if (!playlist.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Access denied"));
                    }
                    return playlistTrackRepository.deleteByPlaylistId(playlistId)
                            .then(playlistRepository.deleteById(playlistId));
                })
                .doOnSuccess(v -> log.info("Playlist deleted: {}", playlistId));
    }

    @CacheEvict(value = "playlists", key = "#playlistId")
    public Mono<PlaylistDTO> addTrack(Long playlistId, Long trackId, Long userId) {
        return playlistRepository.findById(playlistId)
                .switchIfEmpty(Mono.error(new RuntimeException("Playlist not found")))
                .flatMap(playlist -> {
                    if (!playlist.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Access denied"));
                    }
                    return trackRepository.findById(trackId)
                            .switchIfEmpty(Mono.error(new RuntimeException("Track not found")))
                            .flatMap(track -> playlistTrackRepository.findByPlaylistIdAndTrackId(playlistId, trackId)
                                    .flatMap(existing -> Mono.error(new RuntimeException("Track already in playlist")))
                                    .switchIfEmpty(Mono.defer(() -> {
                                        PlaylistTrack playlistTrack = new PlaylistTrack(playlistId, trackId, 0);
                                        return playlistTrackRepository.getNextPosition(playlistId)
                                                .flatMap(pos -> {
                                                    playlistTrack.setPosition(pos);
                                                    return playlistTrackRepository.save(playlistTrack);
                                                });
                                    }))
                            )
                            .then(getPlaylist(playlistId, userId));
                })
                .doOnSuccess(p -> log.info("Track {} added to playlist {}", trackId, playlistId));
    }

    @CacheEvict(value = "playlists", key = "#playlistId")
    public Mono<PlaylistDTO> removeTrack(Long playlistId, Long trackId, Long userId) {
        return playlistRepository.findById(playlistId)
                .switchIfEmpty(Mono.error(new RuntimeException("Playlist not found")))
                .flatMap(playlist -> {
                    if (!playlist.getUserId().equals(userId)) {
                        return Mono.error(new RuntimeException("Access denied"));
                    }
                    return playlistTrackRepository.deleteByPlaylistIdAndTrackId(playlistId, trackId)
                            .then(getPlaylist(playlistId, userId));
                })
                .doOnSuccess(p -> log.info("Track {} removed from playlist {}", trackId, playlistId));
    }
}