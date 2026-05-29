package com.musicstreaming.album.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicstreaming.album.dto.AlbumDTO;
import com.musicstreaming.album.dto.AlbumWithTracksDTO;
import com.musicstreaming.album.service.AlbumService;
import com.musicstreaming.auth.dto.UserPrincipal;
import com.musicstreaming.common.dto.PageResponse;
import com.musicstreaming.common.util.FileUtils;
import com.musicstreaming.track.dto.TrackDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ZeroCopyHttpOutputMessage;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "artistIds", required = false) List<Long> artistIds,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return albumService.getUserAlbums(principal.getId(), page, size, search, artistIds)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/list")
    public Mono<ResponseEntity<List<AlbumDTO>>> getUserAlbumsList(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "artistIds", required = false) List<Long> artistIds) {
        return albumService.getUserAlbumsList(principal.getId(), artistIds)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<AlbumWithTracksDTO>> getAlbumWithTracks(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {

        return albumService.getAlbumWithTracks(id, principal.getId())
                .map(ResponseEntity::ok);
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public Mono<ResponseEntity<AlbumDTO>> updateAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestPart(value = "title", required = false) String title,
            @RequestPart(value = "artistIds", required = false) String artistIdsJson,
            @RequestPart(value = "releaseDate", required = false) String releaseDateStr,
            @RequestPart(value = "cover", required = false) FilePart cover) {

        List<Long> artistIds = parseArtistIds(artistIdsJson);

        LocalDate releaseDate = null;
        if (releaseDateStr != null && !releaseDateStr.isEmpty()) {
            releaseDate = LocalDate.parse(releaseDateStr);
        }

        return albumService.updateAlbum(id, title, artistIds, releaseDate, cover, principal.getId())
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {

        return albumService.deleteAlbum(id, principal.getId())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @GetMapping("/{id}/download")
    public Mono<Void> downloadAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            ServerHttpResponse response) {

        return albumService.getAlbumDownloadData(id, principal.getId())
                .flatMap(data -> {
                    if (data.entries().isEmpty()) {
                        response.setStatusCode(HttpStatus.NO_CONTENT);
                        return Mono.empty();
                    }

                    String albumTitle = data.albumTitle();
                    String zipName = albumTitle + ".zip";

                    return Mono.fromCallable(() -> {
                                Path tempZip = Files.createTempFile("album-", ".zip");
                                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip.toFile()))) {
                                    for (AlbumService.AlbumDownloadEntry entry : data.entries()) {
                                        Path filePath = entry.filePath();
                                        if (Files.exists(filePath)) {
                                            String ext = FileUtils.getExtension(filePath.toString());
                                            ZipEntry zipEntry = new ZipEntry(entry.trackTitle() + "." + ext);
                                            zos.putNextEntry(zipEntry);
                                            Files.copy(filePath, zos);
                                            zos.closeEntry();
                                        }
                                    }
                                }
                                return tempZip;
                            }).subscribeOn(Schedulers.boundedElastic())
                            .flatMap(tempZip -> {
                                try {
                                    long fileSize = Files.size(tempZip);
                                    String encoded = URLEncoder.encode(zipName, StandardCharsets.UTF_8).replace("+", "%20");
                                    response.getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);
                                    response.getHeaders().add("Content-Disposition",
                                            "attachment; filename=\"" + zipName + "\"; filename*=UTF-8''" + encoded);
                                    response.getHeaders().setContentLength(fileSize);

                                    if (response instanceof ZeroCopyHttpOutputMessage zeroCopy) {
                                        return zeroCopy.writeWith(tempZip, 0, fileSize)
                                                .doFinally(s -> {
                                                    try {
                                                        Files.deleteIfExists(tempZip);
                                                    } catch (IOException ignored) {
                                                    }
                                                });
                                    }

                                    DataBufferFactory factory = DefaultDataBufferFactory.sharedInstance;
                                    Flux<DataBuffer> dataBufferFlux = Flux.<DataBuffer, FileChannel>generate(
                                            () -> FileChannel.open(tempZip, StandardOpenOption.READ),
                                            (channel, sink) -> {
                                                try {
                                                    ByteBuffer bb = ByteBuffer.allocate(8192);
                                                    int bytesRead = channel.read(bb);
                                                    if (bytesRead <= 0) {
                                                        sink.complete();
                                                    } else {
                                                        bb.flip();
                                                        sink.next(factory.wrap(bb));
                                                    }
                                                } catch (IOException e) {
                                                    sink.error(e);
                                                }
                                                return channel;
                                            },
                                            channel -> {
                                                try {
                                                    channel.close();
                                                } catch (IOException ignored) {
                                                }
                                                try {
                                                    Files.deleteIfExists(tempZip);
                                                } catch (IOException ignored) {
                                                }
                                            }
                                    ).subscribeOn(Schedulers.boundedElastic());
                                    return response.writeWith(dataBufferFlux);
                                } catch (IOException e) {
                                    return Mono.error(e);
                                }
                            });
                });
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

    @DeleteMapping("/{id}/tracks/{trackId}")
    public Mono<ResponseEntity<Void>> removeTrackFromAlbum(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @PathVariable Long trackId) {
        return albumService.removeTrackFromAlbum(id, trackId, principal.getId())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @PutMapping("/{id}/tracks/reorder")
    public Mono<ResponseEntity<Void>> reorderAlbumTracks(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody List<Long> trackIds) {
        return albumService.reorderAlbumTracks(id, trackIds, principal.getId())
                .then(Mono.just(ResponseEntity.ok().build()));
    }

    private List<Long> parseArtistIds(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            log.warn("Could not parse artistIds: {}", json);
            return new ArrayList<>();
        }
    }
}
