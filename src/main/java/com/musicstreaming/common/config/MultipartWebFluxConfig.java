package com.musicstreaming.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.http.codec.multipart.MultipartHttpMessageReader;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class MultipartWebFluxConfig {

    private static final int DEFAULT_IN_MEMORY_SIZE_BYTES = 256 * 1024;
    private static final int DEFAULT_MAX_PARTS = -1;

    @Value("${app.storage.max-file-size}")
    private long maxFileSize;

    @Bean
    public WebFluxConfigurer multipartWebFluxConfigurer() {
        return new WebFluxConfigurer() {
            @Override
            public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
                DefaultPartHttpMessageReader partReader = new DefaultPartHttpMessageReader();
                partReader.setMaxInMemorySize(DEFAULT_IN_MEMORY_SIZE_BYTES);
                partReader.setMaxDiskUsagePerPart(maxFileSize);
                partReader.setMaxParts(DEFAULT_MAX_PARTS);
                partReader.setEnableLoggingRequestDetails(false);
                configurer.defaultCodecs().multipartReader(new MultipartHttpMessageReader(partReader));
            }
        };
    }
}
