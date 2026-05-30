package com.musicstreaming.common.service;

import com.musicstreaming.common.util.ResponseHeaderHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ZeroCopyHttpOutputMessage;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ZipDownloadService {

    private static final Logger log = LoggerFactory.getLogger(ZipDownloadService.class);
    private static final DataBufferFactory dataBufferFactory = DefaultDataBufferFactory.sharedInstance;

    public record ZipEntryData(String entryName, Path filePath) {
    }

    public Mono<Void> createAndSendZip(List<ZipEntryData> entries, String zipName,
                                       ServerHttpResponse response) {
        if (entries.isEmpty()) {
            response.setStatusCode(HttpStatus.NO_CONTENT);
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
                    Path tempZip = Files.createTempFile("download-", ".zip");
                    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip.toFile()))) {
                        for (ZipEntryData entry : entries) {
                            if (Files.exists(entry.filePath())) {
                                ZipEntry zipEntry = new ZipEntry(entry.entryName());
                                zos.putNextEntry(zipEntry);
                                Files.copy(entry.filePath(), zos);
                                zos.closeEntry();
                            }
                        }
                    }
                    return tempZip;
                }).subscribeOn(Schedulers.boundedElastic())
                .flatMap(tempZip -> {
                    try {
                        long fileSize = Files.size(tempZip);
                        ResponseHeaderHelper.setDownloadHeaders(response, zipName, fileSize);

                        if (response instanceof ZeroCopyHttpOutputMessage zeroCopy) {
                            return zeroCopy.writeWith(tempZip, 0, fileSize)
                                    .doFinally(s -> deleteTempFile(tempZip));
                        }

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
                                            sink.next(dataBufferFactory.wrap(bb));
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
                                    deleteTempFile(tempZip);
                                }
                        ).subscribeOn(Schedulers.boundedElastic());
                        return response.writeWith(dataBufferFlux);
                    } catch (IOException e) {
                        return Mono.error(e);
                    }
                });
    }

    private void deleteTempFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
