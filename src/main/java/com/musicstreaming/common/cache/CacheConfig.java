package com.musicstreaming.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musicstreaming.track.service.TrackService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    private final CacheProperties properties;

    public CacheConfig(CacheProperties properties) {
        this.properties = properties;
    }

    @Bean
    public Cache<Long, byte[]> artistImageCache() {
        return build(properties.getArtistImage());
    }

    @Bean
    public Cache<Long, byte[]> trackCoverCache() {
        return build(properties.getTrackCover());
    }

    @Bean
    public Cache<Long, byte[]> albumCoverCache() {
        return build(properties.getAlbumCover());
    }

    @Bean
    public Cache<Long, byte[]> artistTrackCoverCache() {
        return build(properties.getArtistTrackCover());
    }

    @Bean
    public Cache<Long, TrackService.FileMetadata> fileMetadataCache() {
        return build(properties.getFileMetadata());
    }

    private <K, V> Cache<K, V> build(CacheProperties.Entry entry) {
        return Caffeine.newBuilder()
                .maximumSize(entry.getMaxSize())
                .expireAfterWrite(entry.getTtl())
                .build();
    }
}
