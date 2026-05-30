package com.musicstreaming.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musicstreaming.track.service.TrackService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public Cache<Long, byte[]> artistImageCache() {
        return Caffeine.newBuilder()
                .maximumSize(300)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    @Bean
    public Cache<Long, byte[]> trackCoverCache() {
        return Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    @Bean
    public Cache<Long, byte[]> albumCoverCache() {
        return Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    @Bean
    public Cache<Long, byte[]> artistTrackCoverCache() {
        return Caffeine.newBuilder()
                .maximumSize(300)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    @Bean
    public Cache<Long, TrackService.FileMetadata> fileMetadataCache() {
        return Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }
}
