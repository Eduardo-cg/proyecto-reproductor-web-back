package com.musicstreaming.common.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.musicstreaming.common.util.FileUtils;
import com.musicstreaming.common.util.FilenameSanitizer;
import com.musicstreaming.common.util.ImageUtils;
import lombok.extern.slf4j.Slf4j;
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
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class ReactiveFileService {

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

    public Mono<String> uploadFileWithSize(FilePart file, Long userId, String subfolder, AtomicLong outSize) {
        String ext = FileUtils.getExtension(file.filename());
        String id = UUID.randomUUID().toString();
        String relativePath = userId + "/" + subfolder + "/" + id + "." + ext;
        Path fullPath = FilenameSanitizer.safeResolve(storageBasePath, relativePath);

        return Mono.fromCallable(() -> {
                    FileUtils.createDirectories(fullPath.getParent());
                    return fullPath;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(path -> file.transferTo(path).thenReturn(path))
                .flatMap(path -> Mono.fromCallable(() -> {
                            try {
                                outSize.set(Files.size(path));
                            } catch (Exception e) {
                                outSize.set(file.headers().getContentLength());
                            }
                            return relativePath;
                        })
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<String> uploadCover(FilePart cover, Long userId, String category) {
        String ext = FileUtils.getExtension(cover.filename());
        String id = UUID.randomUUID().toString();
        String relativePath = userId + "/" + category + "/" + id + "." + ext;
        String tempRelativePath = userId + "/" + category + "/" + id + "_temp." + ext;
        Path tempFullPath = FilenameSanitizer.safeResolve(storageBasePath, tempRelativePath);
        Path finalFullPath = FilenameSanitizer.safeResolve(storageBasePath, relativePath);

        return Mono.fromCallable(() -> {
                    FileUtils.createDirectories(tempFullPath.getParent());
                    return tempFullPath;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(tp -> cover.transferTo(tp).thenReturn(tp))
                .flatMap(tp -> Mono.fromCallable(() -> {
                            ImageUtils.resizeIfNeeded(tp, finalFullPath);
                            if (!tp.equals(finalFullPath)) {
                                try {
                                    Files.deleteIfExists(tp);
                                } catch (Exception e) {
                                    log.warn("Failed to delete temp cover file {}: {}", tp, e.getMessage());
                                }
                            }
                            return relativePath;
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnError(e -> {
                    try {
                        Files.deleteIfExists(tempFullPath);
                    } catch (Exception ex) {
                        log.warn("Failed to delete temp file {}: {}", tempFullPath, ex.getMessage());
                    }
                    try {
                        Files.deleteIfExists(finalFullPath);
                    } catch (Exception ex) {
                        log.warn("Failed to delete final file {}: {}", finalFullPath, ex.getMessage());
                    }
                });
    }

    public Mono<String> replaceCover(FilePart cover, Long userId, String category, String oldPath, Long cacheId, Cache<Long, ?> cache) {
        return uploadCover(cover, userId, category)
                .flatMap(newPath -> {
                    Mono<Void> deleteOld = Mono.empty();
                    if (oldPath != null) {
                        Path oldFullPath = FilenameSanitizer.safeResolve(storageBasePath, oldPath);
                        deleteOld = deleteFileWithCache(oldFullPath, cacheId, cache)
                                .onErrorResume(e -> {
                                    log.warn("Failed to delete old cover {}: {}", oldPath, e.getMessage());
                                    return Mono.empty();
                                });
                    }
                    return deleteOld.thenReturn(newPath);
                });
    }

    public Path resolvePath(String relativePath) {
        if (relativePath == null) {
            throw new IllegalArgumentException("relativePath must not be null");
        }
        return FilenameSanitizer.safeResolve(storageBasePath, relativePath);
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
                                try {
                                    if (channel.isOpen()) {
                                        channel.close();
                                    }
                                } catch (IOException ignored) {
                                }
                            }
                            return channel;
                        },
                        channel -> {
                            try {
                                if (channel.isOpen()) {
                                    channel.close();
                                }
                            } catch (IOException ignored) {
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
