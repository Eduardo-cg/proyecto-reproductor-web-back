package com.musicstreaming.common.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Setter
@Getter
@ConfigurationProperties(prefix = "app.cache")
public class CacheProperties {

    private Entry artistImage = new Entry(300, Duration.ofHours(1));
    private Entry trackCover = new Entry(500, Duration.ofHours(1));
    private Entry albumCover = new Entry(200, Duration.ofHours(1));
    private Entry artistTrackCover = new Entry(300, Duration.ofHours(1));
    private Entry fileMetadata = new Entry(2000, Duration.ofHours(1));

    @Setter
    @Getter
    public static class Entry {
        private long maxSize;
        private Duration ttl;

        public Entry() {
        }

        public Entry(long maxSize, Duration ttl) {
            this.maxSize = maxSize;
            this.ttl = ttl;
        }

    }
}
