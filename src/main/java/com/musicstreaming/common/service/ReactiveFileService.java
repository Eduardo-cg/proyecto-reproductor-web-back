package com.musicstreaming.common.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.musicstreaming.common.util.FileUtils;
import com.musicstreaming.common.util.ImageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ReactiveFileService {

    private static final Logger log = LoggerFactory.getLogger(ReactiveFileService.class);
    private static final int CHUNK_SIZE_SEQUENTIAL = 256 * 1024;
    private static final int CHUNK_SIZE_RANDOM = 64 * 1024;
    private static final DataBufferFactory dataBufferFactory = DefaultDataBufferFactory.sharedInstance;

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public Mono<Void> deleteFile(Path path) {
        return Mono.fromCallable(() -> {
                    FileUtils.deleteIfExists(path);
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public Mono<Void> deleteFileWithCache(Path path, Long cacheId, Cache<Long, ?> cache) {
        return Mono.fromCallable(() -> {
                    FileUtils.deleteIfExists(path);
                    if (cacheId != null && cache != null) {
                        cache.invalidate(cacheId);
                    }
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public Mono<String> uploadFileWithSize(FilePart file, Long userId, String subfolder,
                                           AtomicLong outSize) {
        String ext = FileUtils.getExtension(file.filename());
        String id = UUID.randomUUID().toString();
        String relativePath = userId + "/" + subfolder + "/" + id + "." + ext;
        Path fullPath = Paths.get(storageBasePath, relativePath);

        return Mono.fromCallable(() -> {
                    FileUtils.createDirectories(fullPath.getParent());
                    return fullPath;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(path -> file.transferTo(path).thenReturn(path))
                .flatMap(path -> {
                    try {
                        outSize.set(Files.size(path));
                    } catch (Exception e) {
                        outSize.set(file.headers().getContentLength());
                    }
                    return Mono.just(relativePath);
                });
    }

    public Mono<String> uploadCover(FilePart cover, Long userId, String category) {
        String ext = FileUtils.getExtension(cover.filename());
        String id = UUID.randomUUID().toString();
        String relativePath = userId + "/" + category + "/" + id + "." + ext;
        String tempRelativePath = userId + "/" + category + "/" + id + "_temp." + ext;
        Path tempFullPath = Paths.get(storageBasePath, tempRelativePath);
        Path finalFullPath = Paths.get(storageBasePath, relativePath);

        return Mono.fromCallable(() -> {
                    FileUtils.createDirectories(tempFullPath.getParent());
                    return tempFullPath;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(tp -> cover.transferTo(tp).thenReturn(tp))
                .map(tp -> {
                    ImageUtils.resizeIfNeeded(tp, finalFullPath);
                    if (!tp.equals(finalFullPath)) {
                        try {
                            Files.deleteIfExists(tp);
                        } catch (Exception ignored) {
                        }
                    }
                    return relativePath;
                })
                .doOnError(e -> {
                    try { Files.deleteIfExists(tempFullPath); } catch (Exception ignored) {}
                    try { Files.deleteIfExists(finalFullPath); } catch (Exception ignored) {}
                });
    }

    public Mono<String> replaceCover(FilePart cover, Long userId, String category,
                                     String oldPath, Long cacheId, Cache<Long, ?> cache) {
        return uploadCover(cover, userId, category)
                .flatMap(newPath -> {
                    if (oldPath != null) {
                        Path oldFullPath = Paths.get(storageBasePath, oldPath);
                        deleteFileWithCache(oldFullPath, cacheId, cache)
                                .doOnError(e -> log.warn("Failed to delete old cover: {}", e.getMessage()))
                                .onErrorResume(e -> Mono.empty())
                                .subscribe();
                    }
                    return Mono.just(newPath);
                });
    }

    public Path resolvePath(String relativePath) {
        return Paths.get(storageBasePath, relativePath);
    }

    public Flux<DataBuffer> readFile(Path path, long start, long end, boolean sequential) {
        int chunkSize = sequential ? CHUNK_SIZE_SEQUENTIAL : CHUNK_SIZE_RANDOM;

        return Flux.<DataBuffer, FileChannel>generate(
                        () -> FileChannel.open(path, StandardOpenOption.READ).position(start),
                        (channel, sink) -> {
                            try {
                                long remaining = end - channel.position() + 1;
                                if (remaining <= 0) {
                                    sink.complete();
                                    return channel;
                                }
                                int readSize = (int) Math.min(chunkSize, remaining);
                                ByteBuffer bb = ByteBuffer.allocate(readSize);
                                int bytesRead = channel.read(bb);
                                if (bytesRead <= 0) {
                                    sink.complete();
                                } else {
                                    bb.flip();
                                    sink.next(dataBufferFactory.wrap(bb));
                                    if (channel.position() > end) {
                                        sink.complete();
                                    }
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
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
