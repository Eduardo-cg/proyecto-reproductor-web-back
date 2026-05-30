package com.musicstreaming.common.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

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

            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = resized.createGraphics();
            try {
                g2d.drawImage(original.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null);
            } finally {
                g2d.dispose();
            }

            String fileName = target.getFileName().toString();
            String formatName = "jpg";
            int dotIdx = fileName.lastIndexOf('.');
            if (dotIdx > 0) {
                formatName = fileName.substring(dotIdx + 1);
                if (formatName.equalsIgnoreCase("png")) formatName = "png";
                else if (formatName.equalsIgnoreCase("webp")) formatName = "webp";
                else formatName = "jpg";
            }

            FileUtils.createDirectories(target.getParent());
            ImageIO.write(resized, formatName, target.toFile());
        } catch (Exception e) {
            try {
                FileUtils.createDirectories(target.getParent());
                Files.copy(source, target);
            } catch (Exception ignored) {
            }
        }
    }
}
