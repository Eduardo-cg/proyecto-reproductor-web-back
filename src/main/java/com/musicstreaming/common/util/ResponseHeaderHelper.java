package com.musicstreaming.common.util;

import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class ResponseHeaderHelper {

    private ResponseHeaderHelper() {
    }

    public static void setDownloadHeaders(ServerHttpResponse response, String filename, long fileSize) {
        String safeName = escapeForHttpHeader(filename);
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);
        response.getHeaders().add("Content-Disposition",
                "attachment; filename=\"" + safeName + "\"; filename*=UTF-8''" + encoded);
        response.getHeaders().setContentLength(fileSize);
    }

    private static String escapeForHttpHeader(String name) {
        if (name == null) {
            return "download";
        }
        String s = name.replace("\\", "_")
                .replace("\"", "")
                .replace("\r", "_")
                .replace("\n", "_")
                .strip();
        return s.isEmpty() ? "download" : s;
    }
}
