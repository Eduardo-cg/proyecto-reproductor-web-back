package com.musicstreaming.shared.util;

import org.apache.commons.io.FilenameUtils;
import org.apache.tika.Tika;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FileUtils {

    private static final Tika TIKA = new Tika();

    private FileUtils() {}

    public static String getExtension(String filename) {
        String ext = FilenameUtils.getExtension(filename);
        return ext != null && !ext.isEmpty() ? ext.toLowerCase() : "jpg";
    }

    public static String getMimeType(String filename) {
        String ext = getExtension(filename);
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg", "oga" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "m4a", "aac" -> "audio/mp4";
            default -> {
                String detected = TIKA.detect(filename);
                yield detected != null ? detected : "application/octet-stream";
            }
        };
    }

    public static byte[] readAllBytes(Path path) throws java.io.IOException {
        return Files.readAllBytes(path);
    }

    public static Path createDirectories(Path path) throws java.io.IOException {
        return Files.createDirectories(path);
    }

    public static boolean deleteIfExists(Path path) throws java.io.IOException {
        return Files.deleteIfExists(path);
    }

    public static long size(Path path) throws java.io.IOException {
        return Files.size(path);
    }

    public static String probeContentType(Path path) throws java.io.IOException {
        return Files.probeContentType(path);
    }
}
