package com.musicstreaming.adapter.controller;

import com.musicstreaming.adapter.dto.PlaylistCreateRequest;
import com.musicstreaming.adapter.dto.PlaylistDTO;
import com.musicstreaming.adapter.dto.PlaylistUpdateRequest;
import com.musicstreaming.adapter.security.UserPrincipal;
import com.musicstreaming.domain.service.PlaylistService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/playlists")
public class PlaylistController {

    private static final Logger log = LoggerFactory.getLogger(PlaylistController.class);

    private final PlaylistService playlistService;

    public PlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @PostMapping
    public Mono<ResponseEntity<PlaylistDTO>> createPlaylist(
            @Valid @RequestBody PlaylistCreateRequest request,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return playlistService.createPlaylist(userPrincipal.getId(), request)
                .map(playlist -> ResponseEntity.status(HttpStatus.CREATED).body(playlist));
    }

    @GetMapping
    public Flux<ResponseEntity<PlaylistDTO>> getUserPlaylists(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return playlistService.getUserPlaylists(userPrincipal.getId())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<PlaylistDTO>> getPlaylist(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return playlistService.getPlaylist(id, userPrincipal.getId())
                .map(ResponseEntity::ok);
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<PlaylistDTO>> updatePlaylist(
            @PathVariable Long id,
            @Valid @RequestBody PlaylistUpdateRequest request,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return playlistService.updatePlaylist(id, userPrincipal.getId(), request)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deletePlaylist(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return playlistService.deletePlaylist(id, userPrincipal.getId())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @PostMapping("/{id}/tracks/{trackId}")
    public Mono<ResponseEntity<PlaylistDTO>> addTrack(
            @PathVariable Long id,
            @PathVariable Long trackId,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return playlistService.addTrack(id, trackId, userPrincipal.getId())
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}/tracks/{trackId}")
    public Mono<ResponseEntity<PlaylistDTO>> removeTrack(
            @PathVariable Long id,
            @PathVariable Long trackId,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return playlistService.removeTrack(id, trackId, userPrincipal.getId())
                .map(ResponseEntity::ok);
    }
}