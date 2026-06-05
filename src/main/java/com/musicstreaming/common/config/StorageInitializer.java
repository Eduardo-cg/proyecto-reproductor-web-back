package com.musicstreaming.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@Slf4j
public class StorageInitializer {

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureStorageDirectory() {
        Path path = Paths.get(storageBasePath);
        try {
            Files.createDirectories(path);
            log.info("Storage base path ready: {}", path.toAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create storage base path '" + storageBasePath + "'", e);
        }
    }
}
