package com.musicstreaming.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.musicstreaming.common.util.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

@Service
public class CoverService {

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    private Mono<byte[]> readCoverBytes(String coverPath, Long entityId, Cache<Long, byte[]> cache) {
        if (coverPath == null) {
            return Mono.just(new byte[0]);
        }
        byte[] cached = cache.getIfPresent(entityId);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.fromCallable(() -> {
                    Path path = Paths.get(storageBasePath, coverPath);
                    return FileUtils.readAllBytes(path);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(bytes -> cache.put(entityId, bytes));
    }

    public Mono<String> toBase64DataUri(String coverPath, Long entityId, Cache<Long, byte[]> cache) {
        if (coverPath == null) {
            return Mono.just("");
        }
        return readCoverBytes(coverPath, entityId, cache).map(bytes -> {
            String mimeType = FileUtils.getMimeType(coverPath);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return "data:" + mimeType + ";base64," + base64;
        });
    }
}
