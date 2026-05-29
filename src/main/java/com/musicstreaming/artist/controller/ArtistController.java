package com.musicstreaming.artist.controller;

import com.musicstreaming.album.dto.AlbumDTO;
import com.musicstreaming.artist.dto.ArtistDTO;
import com.musicstreaming.artist.service.ArtistService;
import com.musicstreaming.auth.dto.UserPrincipal;
import com.musicstreaming.common.dto.PageResponse;
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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/artists")
public class ArtistController {

    private static final Logger log = LoggerFactory.getLogger(ArtistController.class);

    private final ArtistService artistService;

    public ArtistController(ArtistService artistService) {
        this.artistService = artistService;
    }

    @GetMapping
    public Mono<ResponseEntity<PageResponse<ArtistDTO>>> getUserArtists(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "search", required = false) String searchQuery,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return artistService.getUserArtists(principal.getId(), searchQuery, page, size)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/list")
    public Mono<ResponseEntity<List<ArtistDTO>>> getUserArtistsList(
            @AuthenticationPrincipal UserPrincipal principal) {
        return artistService.getUserArtistsList(principal.getId())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<ArtistDTO>> getArtistById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
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
            @PathVariable Long id,
            @RequestPart(value = "name", required = false) String name,
            @RequestPart(value = "image", required = false) FilePart image) {
        return artistService.updateArtist(id, name, image, principal.getId())
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteArtist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return artistService.deleteArtist(id, principal.getId())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @GetMapping("/{id}/tracks")
    public Mono<ResponseEntity<PageResponse<TrackDTO>>> getArtistTracks(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return artistService.getArtistTracks(id, principal.getId(), page, size)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}/albums")
    public Mono<ResponseEntity<PageResponse<AlbumDTO>>> getArtistAlbums(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return artistService.getArtistAlbums(id, principal.getId(), page, size)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}/download")
    public Mono<Void> downloadArtist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            ServerHttpResponse response) {

        return artistService.getArtistDownloadData(id, principal.getId())
                .flatMap(data -> {
                    if (data.entries().isEmpty()) {
                        response.setStatusCode(HttpStatus.NO_CONTENT);
                        return Mono.empty();
                    }

                    String zipName = data.artistName() + ".zip";

                    return Mono.fromCallable(() -> {
                                Path tempZip = Files.createTempFile("artist-", ".zip");
                                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip.toFile()))) {
                                    for (ArtistService.ArtistDownloadEntry entry : data.entries()) {
                                        Path filePath = entry.filePath();
                                        if (Files.exists(filePath)) {
                                            ZipEntry zipEntry = new ZipEntry(entry.zipPath().replace("\\", "/"));
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
}
