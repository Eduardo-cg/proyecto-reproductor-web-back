package com.musicstreaming.adapter.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicstreaming.adapter.dto.AlbumDTO;
import com.musicstreaming.adapter.dto.AlbumWithTracksDTO;
import com.musicstreaming.adapter.dto.PageResponse;
import com.musicstreaming.adapter.dto.TrackDTO;
import com.musicstreaming.adapter.security.UserPrincipal;
import com.musicstreaming.domain.service.AlbumService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/albums")
public class AlbumController {

    private static final Logger log = LoggerFactory.getLogger(AlbumController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AlbumService albumService;

    public AlbumController(AlbumService albumService) {
        this.albumService = albumService;
    }

    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public Mono<ResponseEntity<AlbumDTO>> createAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestPart("title") String title,
            @RequestPart(value = "artistIds", required = false) String artistIdsJson,
            @RequestPart(value = "releaseDate", required = false) String releaseDateStr,
            @RequestPart(value = "cover", required = false) FilePart cover) {

        List<Long> artistIds = parseArtistIds(artistIdsJson);

        LocalDate releaseDate = null;
        if (releaseDateStr != null && !releaseDateStr.isEmpty()) {
            releaseDate = LocalDate.parse(releaseDateStr);
        }

        return albumService.createAlbum(title, artistIds, releaseDate, cover, principal.getId())
                .map(album -> ResponseEntity.status(HttpStatus.CREATED).body(album));
    }

    @GetMapping
    public Mono<ResponseEntity<PageResponse<AlbumDTO>>> getUserAlbums(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return albumService.getUserAlbums(principal.getId(), page, size)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<AlbumWithTracksDTO>> getAlbumWithTracks(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {

        return albumService.getAlbumWithTracks(id, principal.getId())
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {

        return albumService.deleteAlbum(id, principal.getId())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @PostMapping(value = "/{id}/tracks", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public Mono<ResponseEntity<TrackDTO>> addTrackToAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestPart("title") String title,
            @RequestPart(value = "artistIds", required = false) String artistIdsJson,
            @RequestPart("duration") String duration,
            @RequestPart("file") FilePart file,
            @RequestPart(value = "position", required = false) String positionStr,
            @RequestPart(value = "releaseDate", required = false) String releaseDateStr) {

        List<Long> artistIds = parseArtistIds(artistIdsJson);

        Integer position = null;
        if (positionStr != null && !positionStr.isEmpty()) {
            position = Integer.valueOf(positionStr);
        }

        LocalDate releaseDate = null;
        if (releaseDateStr != null && !releaseDateStr.isEmpty()) {
            releaseDate = LocalDate.parse(releaseDateStr);
        }

        return albumService.addTrackToAlbum(
                        id, title, artistIds, Integer.valueOf(duration), file,
                        position, releaseDate, principal.getId())
                .map(track -> ResponseEntity.status(HttpStatus.CREATED).body(track));
    }

    private List<Long> parseArtistIds(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("Could not parse artistIds: {}", json);
            return new ArrayList<>();
        }
    }
}
