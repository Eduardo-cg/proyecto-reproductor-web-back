package com.musicstreaming.common.util;

import org.apache.commons.io.FilenameUtils;
import org.apache.tika.Tika;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileUtils {

    private static final Tika TIKA = new Tika();

    private FileUtils() {
    }

    public static String getExtension(String filename) {
        String ext = FilenameUtils.getExtension(filename);
        return ext != null && !ext.isEmpty() ? ext.toLowerCase() : "bin";
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

    public static byte[] readAllBytes(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    public static void createDirectories(Path path) throws IOException {
        Files.createDirectories(path);
    }

    public static void deleteIfExists(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    public static long size(Path path) throws IOException {
        return Files.size(path);
    }

    public static String probeContentType(Path path) throws IOException {
        return Files.probeContentType(path);
    }
}
