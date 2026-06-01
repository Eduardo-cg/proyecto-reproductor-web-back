package com.musicstreaming.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class StorageHealthIndicator implements HealthIndicator {

    private static final long LOW_SPACE_THRESHOLD_BYTES = 500 * 1024 * 1024;

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    @Override
    public Health health() {
        try {
            Path path = Paths.get(storageBasePath);
            if (!Files.exists(path)) {
                return Health.down()
                        .withDetail("storage", "Directory does not exist")
                        .withDetail("path", storageBasePath)
                        .build();
            }

            long totalSpace = path.toFile().getTotalSpace();
            long usableSpace = path.toFile().getUsableSpace();
            long usedPercent = totalSpace > 0 ? ((totalSpace - usableSpace) * 100 / totalSpace) : 0;

            if (usableSpace < LOW_SPACE_THRESHOLD_BYTES) {
                return Health.down()
                        .withDetail("storage", "Low disk space")
                        .withDetail("path", storageBasePath)
                        .withDetail("totalMB", totalSpace / (1024 * 1024))
                        .withDetail("usableMB", usableSpace / (1024 * 1024))
                        .withDetail("usedPercent", usedPercent + "%")
                        .build();
            }

            return Health.up()
                    .withDetail("path", storageBasePath)
                    .withDetail("totalMB", totalSpace / (1024 * 1024))
                    .withDetail("usableMB", usableSpace / (1024 * 1024))
                    .withDetail("usedPercent", usedPercent + "%")
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("storage", "Error checking storage")
                    .withException(e)
                    .build();
        }
    }
}
