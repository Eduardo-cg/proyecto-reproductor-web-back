package com.musicstreaming.common.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

public final class FilenameSanitizer {

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1F]");
    private static final Pattern DOT_DOT = Pattern.compile("\\.\\.");

    private FilenameSanitizer() {
    }

    public static String sanitize(String name) {
        if (name == null) {
            return "_";
        }
        String s = UNSAFE_CHARS.matcher(name).replaceAll("_");
        s = DOT_DOT.matcher(s).replaceAll("_");
        s = s.strip();
        return s.isEmpty() ? "_" : s;
    }

    public static Path safeResolve(String basePath, String relativePath) {
        Path base = Paths.get(basePath).toAbsolutePath().normalize();
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new SecurityException("Path traversal detected: " + relativePath);
        }
        return resolved;
    }
}
