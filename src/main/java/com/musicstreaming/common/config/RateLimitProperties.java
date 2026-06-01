package com.musicstreaming.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "app.security.rate-limit")
public class RateLimitProperties {

    private int loginLimit = 10;
    private int registerLimit = 5;
    private long windowMs = 60_000;

}
