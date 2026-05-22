package com.musicstreaming.adapter.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicstreaming.adapter.dto.PageResponse;
import com.musicstreaming.adapter.dto.TrackDTO;
import com.musicstreaming.adapter.security.UserPrincipal;
import com.musicstreaming.domain.service.AudioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/tracks")
public class AudioController {

    private static final Logger log = LoggerFactory.getLogger(AudioController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AudioService audioService;

    public AudioController(AudioService audioService) {
        this.audioService = audioService;
    }

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

        List<Long> artistIds = parseArtistIds(artistIdsJson);

        LocalDate releaseDate = null;
        if (releaseDateStr != null && !releaseDateStr.isEmpty()) {
            releaseDate = LocalDate.parse(releaseDateStr);
        }

        return audioService.uploadTrack(
                        title,
                        artistIds,
                        album,
                        Integer.valueOf(duration),
                        file,
                        cover,
                        principal.getId(),
                        releaseDate
                )
                .map(track -> ResponseEntity.status(HttpStatus.CREATED).body(track));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<TrackDTO>> getTrackMetadata(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return audioService.getTrackMetadata(id, principal.getId())
                .map(ResponseEntity::ok);
    }

    @GetMapping
    public Mono<ResponseEntity<PageResponse<TrackDTO>>> getUserTracks(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return audioService.getUserTracks(principal.getId(), page, size)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/count")
    public Mono<ResponseEntity<Long>> getUserTrackCount(
            @AuthenticationPrincipal UserPrincipal principal) {
        return audioService.getUserTrackCount(principal.getId())
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteTrack(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return audioService.deleteTrack(id, principal.getId())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @GetMapping("/{id}/stream")
    public Mono<Void> streamTrack(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            ServerHttpResponse response) {

        log.debug("Stream request for track {} with Range: {}", id, rangeHeader);

        Flux<DataBuffer> dataBufferFlux = audioService.getTrackFilePath(id, principal.getId())
                .flatMapMany(path -> {
                    try {
                        long fileSize = Files.size(path);
                        String mimeType = Files.probeContentType(path);
                        if (mimeType == null) {
                            mimeType = "audio/mpeg";
                        }

                        response.getHeaders().setContentType(MediaType.parseMediaType(mimeType));
                        response.getHeaders().add("Accept-Ranges", "bytes");

                        if (rangeHeader == null || rangeHeader.isEmpty()) {
                            response.getHeaders().setContentLength(fileSize);
                            return readFile(path, 0, fileSize - 1);
                        }

                        return parseRangeHeader(rangeHeader, fileSize)
                                .map(range -> {
                                    long start = range[0];
                                    long end = range[1];

                                    response.setStatusCode(HttpStatus.PARTIAL_CONTENT);
                                    long contentLength = end - start + 1;
                                    response.getHeaders().setContentLength(contentLength);
                                    response.getHeaders().add("Content-Range",
                                            "bytes " + start + "-" + end + "/" + fileSize);

                                    return readFile(path, start, end);
                                })
                                .orElseGet(() -> {
                                    response.setStatusCode(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
                                    return Flux.<DataBuffer>empty();
                                });

                    } catch (IOException e) {
                        log.error("Error streaming track {}: {}", id, e.getMessage());
                        return Flux.<DataBuffer>error(e);
                    }
                })
                .doOnNext(buffer -> log.debug("Sent {} bytes", buffer.readableByteCount()))
                .doOnComplete(() -> log.info("Stream completed for track {}", id))
                .doOnError(e -> log.error("Stream error for track {}: {}", id, e.getMessage()));

        return response.writeWith(dataBufferFlux);
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

    private Flux<DataBuffer> readFile(Path path, long start, long end) {
        int chunkSize = 64 * 1024;

        return Flux.<DataBuffer, RandomAccessFile>generate(
                        () -> {
                            RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
                            file.seek(start);
                            return file;
                        },
                        (file, sink) -> {
                            try {
                                long remaining = end - file.getFilePointer() + 1;
                                if (remaining <= 0) {
                                    sink.complete();
                                    return file;
                                }
                                int readSize = (int) Math.min(chunkSize, remaining);
                                byte[] chunk = new byte[readSize];
                                int bytesRead = file.read(chunk);
                                if (bytesRead <= 0) {
                                    sink.complete();
                                } else {
                                    byte[] actual = bytesRead < readSize
                                            ? java.util.Arrays.copyOf(chunk, bytesRead)
                                            : chunk;
                                    sink.next(DefaultDataBufferFactory.sharedInstance.wrap(actual));
                                    if (file.getFilePointer() > end) {
                                        sink.complete();
                                    }
                                }
                            } catch (IOException e) {
                                sink.error(e);
                            }
                            return file;
                        },
                        file -> {
                            try {
                                file.close();
                            } catch (IOException ignored) {
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Optional<long[]> parseRangeHeader(String rangeHeader, long fileSize) {
        try {
            String rangePart = rangeHeader.replace("bytes=", "").trim();
            String[] parts = rangePart.split("-");

            long start = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
            long end = parts.length > 1 && !parts[1].isEmpty()
                    ? Long.parseLong(parts[1])
                    : fileSize - 1;

            if (start > end || start >= fileSize || end >= fileSize) {
                return Optional.empty();
            }

            return Optional.of(new long[]{start, end});
        } catch (Exception e) {
            log.warn("Invalid range header: {}", rangeHeader);
            return Optional.empty();
        }
    }
}
