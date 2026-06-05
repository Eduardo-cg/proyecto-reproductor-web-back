package com.musicstreaming.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class JwtSecretValidator {

    private static final String DEFAULT_SECRET = "your-256-bit-secret-key-here-must-be-at-least-32-characters-long";

    private final ConfigurableEnvironment environment;
    private final String jwtSecret;

    public JwtSecretValidator(ConfigurableEnvironment environment,
                              @Value("${app.jwt.secret}") String jwtSecret) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            if (DEFAULT_SECRET.equals(jwtSecret)) {
                log.error("Cannot start in production with the default JWT secret. Set the JWT_SECRET environment variable with a secure 256-bit key.");
                throw new IllegalStateException(
                        "JWT_SECRET environment variable must be set in production. " +
                                "The default insecure secret is not allowed. " +
                                "Set the JWT_SECRET environment variable with a secure 256-bit key.");
            }
            if (jwtSecret.length() < 32) {
                log.error("JWT secret is too short. Must be at least 32 characters for HS256 security.");
                throw new IllegalStateException(
                        "JWT_SECRET must be at least 32 characters long for HS256 security.");
            }
            log.info("JWT secret validation passed.");
        }
    }
}
