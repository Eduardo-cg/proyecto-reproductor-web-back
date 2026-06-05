package com.musicstreaming.album.controller;

import com.musicstreaming.album.dto.AlbumDTO;
import com.musicstreaming.album.dto.AlbumMetadata;
import com.musicstreaming.album.dto.AlbumWithTracksDTO;
import com.musicstreaming.album.service.AlbumService;
import com.musicstreaming.auth.dto.UserPrincipal;
import com.musicstreaming.common.dto.PageResponse;
import com.musicstreaming.common.util.MetadataParser;
import com.musicstreaming.track.dto.TrackDTO;
import com.musicstreaming.track.dto.TrackMetadata;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/albums")
@RequiredArgsConstructor
@Validated
@Slf4j
public class AlbumController {

    private final AlbumService albumService;

    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public Mono<ResponseEntity<AlbumDTO>> createAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestPart("title") String title,
            @RequestPart(value = "artistIds", required = false) String artistIdsJson,
            @RequestPart(value = "releaseDate", required = false) String releaseDateStr,
            @RequestPart(value = "cover", required = false) FilePart cover) {

        AlbumMetadata meta = new AlbumMetadata(
                title,
                MetadataParser.parseLongList(artistIdsJson),
                MetadataParser.parseOptionalDate(releaseDateStr)
        );

        return albumService.createAlbum(meta, cover, principal.getId())
                .map(album -> ResponseEntity.status(HttpStatus.CREATED).body(album));
    }

    @GetMapping
    public Mono<ResponseEntity<PageResponse<AlbumDTO>>> getUserAlbums(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "artistIds", required = false) List<Long> artistIds,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(value = "sortBy", defaultValue = "title") String sortBy,
            @RequestParam(value = "sortDirection", defaultValue = "asc") String sortDirection) {

        return albumService.getUserAlbums(principal.getId(), page, size, search, artistIds, sortBy, sortDirection)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/list")
    public Mono<ResponseEntity<PageResponse<AlbumDTO>>> getUserAlbumsList(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "artistIds", required = false) List<Long> artistIds,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "10") @Min(1) @Max(100) int size) {
        return albumService.getUserAlbumsList(principal.getId(), search, artistIds, page, size)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<AlbumWithTracksDTO>> getAlbumWithTracks(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id) {
        return albumService.getAlbumWithTracks(id, principal.getId())
                .map(ResponseEntity::ok);
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public Mono<ResponseEntity<AlbumDTO>> updateAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            @RequestPart(value = "title", required = false) String title,
            @RequestPart(value = "artistIds", required = false) String artistIdsJson,
            @RequestPart(value = "releaseDate", required = false) String releaseDateStr,
            @RequestPart(value = "cover", required = false) FilePart cover) {

        AlbumMetadata meta = new AlbumMetadata(
                title,
                MetadataParser.parseLongList(artistIdsJson),
                MetadataParser.parseOptionalDate(releaseDateStr)
        );

        return albumService.updateAlbum(id, meta, cover, principal.getId())
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id) {
        return albumService.deleteAlbum(id, principal.getId())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @GetMapping("/{id}/download")
    public Mono<Void> downloadAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            ServerHttpResponse response) {
        return albumService.downloadAlbum(id, principal.getId(), response);
    }

    @PostMapping(value = "/{id}/tracks", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public Mono<ResponseEntity<TrackDTO>> addTrackToAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            @RequestPart("title") String title,
            @RequestPart(value = "artistIds", required = false) String artistIdsJson,
            @RequestPart("duration") String duration,
            @RequestPart("file") FilePart file,
            @RequestPart(value = "position", required = false) String positionStr,
            @RequestPart(value = "releaseDate", required = false) String releaseDateStr) {

        TrackMetadata meta = new TrackMetadata(
                title,
                MetadataParser.parseLongList(artistIdsJson),
                null,
                MetadataParser.parseRequiredInt(duration, "duration"),
                MetadataParser.parseOptionalInt(positionStr),
                MetadataParser.parseOptionalDate(releaseDateStr)
        );

        return albumService.addTrackToAlbum(id, meta, file, principal.getId())
                .map(track -> ResponseEntity.status(HttpStatus.CREATED).body(track));
    }

    @DeleteMapping("/{id}/tracks/{trackId}")
    public Mono<ResponseEntity<Void>> removeTrackFromAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            @PathVariable @Positive Long trackId) {
        return albumService.removeTrackFromAlbum(id, trackId, principal.getId())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @PutMapping("/{id}/tracks/reorder")
    public Mono<ResponseEntity<Void>> reorderAlbumTracks(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            @RequestBody List<Long> trackIds) {
        return albumService.reorderAlbumTracks(id, trackIds, principal.getId())
                .then(Mono.just(ResponseEntity.ok().build()));
    }
}
