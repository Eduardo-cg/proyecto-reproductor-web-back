package com.musicstreaming.common.util;

import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class ResponseHeaderHelper {

    private ResponseHeaderHelper() {
    }

    public static void setDownloadHeaders(ServerHttpResponse response, String filename, long fileSize) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);
        response.getHeaders().add("Content-Disposition",
                "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded);
        response.getHeaders().setContentLength(fileSize);
    }
}
