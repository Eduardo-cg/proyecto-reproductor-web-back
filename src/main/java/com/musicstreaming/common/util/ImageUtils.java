package com.musicstreaming.common.util;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public final class ImageUtils {

    private static final int MAX_DIMENSION = 800;
    private static final long MAX_SIZE_BYTES = 1_000_000;

    private ImageUtils() {
    }

    public static void resizeIfNeeded(Path source, Path target) {
        try {
            BufferedImage original = ImageIO.read(source.toFile());
            if (original == null) {
                FileUtils.createDirectories(target.getParent());
                Files.copy(source, target);
                return;
            }

            int width = original.getWidth();
            int height = original.getHeight();

            if (width <= MAX_DIMENSION && height <= MAX_DIMENSION && FileUtils.size(source) <= MAX_SIZE_BYTES) {
                FileUtils.createDirectories(target.getParent());
                Files.copy(source, target);
                return;
            }

            double scale = Math.min((double) MAX_DIMENSION / width, (double) MAX_DIMENSION / height);
            if (scale > 1.0) scale = 1.0;

            int newWidth = (int) (width * scale);
            int newHeight = (int) (height * scale);

            boolean sourceHasAlpha = original.getColorModel().hasAlpha();
            String outFormat = resolveOutputFormat(target, sourceHasAlpha);

            BufferedImage resized;
            if ("jpg".equalsIgnoreCase(outFormat) && sourceHasAlpha) {
                resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = resized.createGraphics();
                try {
                    g2d.setColor(java.awt.Color.WHITE);
                    g2d.fillRect(0, 0, newWidth, newHeight);
                    g2d.drawImage(original.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null);
                } finally {
                    g2d.dispose();
                }
            } else {
                int imageType = sourceHasAlpha ? BufferedImage.TYPE_INT_ARGB : (original.getType() == 0 ? BufferedImage.TYPE_INT_RGB : original.getType());
                resized = new BufferedImage(newWidth, newHeight, imageType);
                Graphics2D g2d = resized.createGraphics();
                try {
                    g2d.drawImage(original.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null);
                } finally {
                    g2d.dispose();
                }
            }

            FileUtils.createDirectories(target.getParent());
            ImageIO.write(resized, outFormat, target.toFile());
        } catch (Exception e) {
            log.warn("Failed to resize image {}, falling back to copy: {}", source, e.getMessage());
            try {
                FileUtils.createDirectories(target.getParent());
                Files.copy(source, target);
            } catch (Exception ex) {
                log.warn("Failed to copy image {} to {}: {}", source, target, ex.getMessage());
            }
        }
    }

    private static String resolveOutputFormat(Path target, boolean sourceHasAlpha) {
        String fileName = target.getFileName().toString();
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx <= 0) {
            return sourceHasAlpha ? "png" : "jpg";
        }
        String ext = fileName.substring(dotIdx + 1).toLowerCase();
        return switch (ext) {
            case "png" -> "png";
            case "webp" -> "webp";
            case "gif" -> "gif";
            case "bmp" -> "bmp";
            default -> "jpg";
        };
    }
}
