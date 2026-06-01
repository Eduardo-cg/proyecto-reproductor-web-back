package com.musicstreaming.artist.controller;

import com.musicstreaming.album.dto.AlbumDTO;
import com.musicstreaming.artist.dto.ArtistDTO;
import com.musicstreaming.artist.service.ArtistService;
import com.musicstreaming.auth.dto.UserPrincipal;
import com.musicstreaming.common.dto.PageResponse;
import com.musicstreaming.track.dto.TrackDTO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/artists")
@RequiredArgsConstructor
@Validated
public class ArtistController {

    private final ArtistService artistService;

    @GetMapping
    public Mono<ResponseEntity<PageResponse<ArtistDTO>>> getUserArtists(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "search", required = false) String searchQuery,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return artistService.getUserArtists(principal.getId(), searchQuery, page, size)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/list")
    public Mono<ResponseEntity<PageResponse<ArtistDTO>>> getUserArtistsList(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "10") @Min(1) @Max(100) int size) {
        return artistService.getUserArtistsList(principal.getId(), search, page, size)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<ArtistDTO>> getArtistById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id) {
        return artistService.getArtistById(id, principal.getId())
                .map(ResponseEntity::ok);
    }

    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public Mono<ResponseEntity<ArtistDTO>> createArtist(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestPart("name") String name,
            @RequestPart(value = "image", required = false) FilePart image) {
        return artistService.createArtist(name.trim(), image, principal.getId())
                .map(artist -> ResponseEntity.status(HttpStatus.CREATED).body(artist));
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public Mono<ResponseEntity<ArtistDTO>> updateArtist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            @RequestPart(value = "name", required = false) String name,
            @RequestPart(value = "image", required = false) FilePart image) {
        return artistService.updateArtist(id, name, image, principal.getId())
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteArtist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id) {
        return artistService.deleteArtist(id, principal.getId())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @GetMapping("/{id}/tracks")
    public Mono<ResponseEntity<PageResponse<TrackDTO>>> getArtistTracks(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return artistService.getArtistTracks(id, principal.getId(), page, size)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}/albums")
    public Mono<ResponseEntity<PageResponse<AlbumDTO>>> getArtistAlbums(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return artistService.getArtistAlbums(id, principal.getId(), page, size)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}/download")
    public Mono<Void> downloadArtist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            ServerHttpResponse response) {
        return artistService.downloadArtist(id, principal.getId(), response);
    }
}
