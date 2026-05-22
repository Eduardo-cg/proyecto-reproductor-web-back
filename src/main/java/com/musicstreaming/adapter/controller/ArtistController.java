package com.musicstreaming.adapter.controller;

import com.musicstreaming.adapter.dto.ArtistDTO;
import com.musicstreaming.adapter.security.UserPrincipal;
import com.musicstreaming.domain.service.ArtistService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/artists")
public class ArtistController {

    private final ArtistService artistService;

    public ArtistController(ArtistService artistService) {
        this.artistService = artistService;
    }

    @GetMapping
    public Flux<ArtistDTO> getUserArtists(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "search", required = false) String searchQuery) {
        return artistService.getUserArtists(principal.getId(), searchQuery);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<ArtistDTO>> getArtistById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return artistService.getArtistById(id, principal.getId())
                .map(ResponseEntity::ok)
                .onErrorResume(e -> Mono.just(ResponseEntity.notFound().build()));
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
            @PathVariable Long id,
            @RequestPart(value = "name", required = false) String name,
            @RequestPart(value = "image", required = false) FilePart image) {
        return artistService.updateArtist(id, name, image, principal.getId())
                .map(ResponseEntity::ok)
                .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().build()));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteArtist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return artistService.deleteArtist(id, principal.getId())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
