package com.musicstreaming.track.controller;

import com.musicstreaming.auth.dto.UserPrincipal;
import com.musicstreaming.common.dto.PageResponse;
import com.musicstreaming.common.util.MetadataParser;
import com.musicstreaming.track.dto.TrackDTO;
import com.musicstreaming.track.dto.TrackMetadata;
import com.musicstreaming.track.service.TrackService;
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
@RequestMapping("/api/tracks")
@RequiredArgsConstructor
@Validated
@Slf4j
public class TrackController {

    private final TrackService trackService;

    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public Mono<ResponseEntity<TrackDTO>> uploadTrack(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestPart("title") String title,
            @RequestPart(value = "artistIds", required = false) String artistIdsJson,
            @RequestPart(value = "album", required = false) String album,
            @RequestPart("duration") String duration,
            @RequestPart("file") FilePart file,
            @RequestPart(value = "cover", required = false) FilePart cover,
            @RequestPart(value = "releaseDate", required = false) String releaseDateStr) {

        TrackMetadata meta = new TrackMetadata(
                title,
                MetadataParser.parseLongList(artistIdsJson),
                album,
                MetadataParser.parseRequiredInt(duration, "duration"),
                null,
                MetadataParser.parseOptionalDate(releaseDateStr)
        );

        return trackService.uploadTrack(meta, file, cover, principal.getId())
                .map(track -> ResponseEntity.status(HttpStatus.CREATED).body(track));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<TrackDTO>> getTrackMetadata(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id) {
        return trackService.getTrackMetadata(id, principal.getId())
                .map(ResponseEntity::ok);
    }

    @GetMapping
    public Mono<ResponseEntity<PageResponse<TrackDTO>>> getUserTracks(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "artistIds", required = false) List<Long> artistIds,
            @RequestParam(value = "albumIds", required = false) List<Long> albumIds,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(value = "sortBy", defaultValue = "title") String sortBy,
            @RequestParam(value = "sortDirection", defaultValue = "asc") String sortDirection) {

        return trackService.getUserTracks(principal.getId(), page, size, search, artistIds, albumIds, sortBy, sortDirection)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/count")
    public Mono<ResponseEntity<Long>> getUserTrackCount(
            @AuthenticationPrincipal UserPrincipal principal) {
        return trackService.getUserTrackCount(principal.getId())
                .map(ResponseEntity::ok);
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public Mono<ResponseEntity<TrackDTO>> updateTrack(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            @RequestPart(value = "title", required = false) String title,
            @RequestPart(value = "artistIds", required = false) String artistIdsJson,
            @RequestPart(value = "album", required = false) String album,
            @RequestPart(value = "releaseDate", required = false) String releaseDateStr,
            @RequestPart(value = "cover", required = false) FilePart cover) {

        TrackMetadata meta = new TrackMetadata(
                title,
                MetadataParser.parseLongList(artistIdsJson),
                album,
                null,
                null,
                MetadataParser.parseOptionalDate(releaseDateStr)
        );

        return trackService.updateTrack(id, meta, cover, principal.getId())
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteTrack(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id) {
        return trackService.deleteTrack(id, principal.getId())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @GetMapping("/{id}/download")
    public Mono<Void> downloadTrack(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            ServerHttpResponse response) {
        return trackService.downloadTrack(id, principal.getId(), response);
    }

    @GetMapping("/{id}/stream")
    public Mono<Void> streamTrack(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive Long id,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            ServerHttpResponse response) {
        return trackService.streamTrack(id, principal.getId(), rangeHeader, response);
    }
}
