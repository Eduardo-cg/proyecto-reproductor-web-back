package com.musicstreaming.domain.service;

import com.musicstreaming.adapter.dto.LoginResponse;
import com.musicstreaming.adapter.dto.RegisterRequest;
import com.musicstreaming.adapter.dto.UserResponse;
import com.musicstreaming.adapter.security.JwtTokenProvider;
import com.musicstreaming.adapter.security.UserPrincipal;
import com.musicstreaming.domain.model.User;
import com.musicstreaming.domain.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.jwt.expiration}")
    private long jwtExpiration;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public Mono<UserResponse> register(RegisterRequest request) {
        return userRepository.existsByUsername(request.getUsername())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new RuntimeException("Username already exists"));
                    }
                    return userRepository.existsByEmail(request.getEmail());
                })
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new RuntimeException("Email already exists"));
                    }
                    User user = new User(
                            request.getUsername(),
                            request.getEmail(),
                            passwordEncoder.encode(request.getPassword())
                    );
                    return userRepository.save(user);
                })
                .map(user -> new UserResponse(user.getId(), user.getUsername(), user.getEmail()))
                .doOnSuccess(u -> log.info("User registered: {}", u.getUsername()));
    }

    public Mono<LoginResponse> login(String username, String password) {
        return userRepository.findByUsername(username)
                .switchIfEmpty(Mono.error(new RuntimeException("Invalid username or password")))
                .flatMap(user -> {
                    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
                        return Mono.error(new RuntimeException("Invalid username or password"));
                    }
                    UserPrincipal userPrincipal = new UserPrincipal(
                            user.getId(),
                            user.getUsername(),
                            user.getEmail(),
                            user.getPasswordHash()
                    );
                    String token = jwtTokenProvider.generateToken(userPrincipal);

                    LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                            user.getId(),
                            user.getUsername(),
                            user.getEmail()
                    );

                    return Mono.just(new LoginResponse(token, jwtExpiration, userInfo));
                })
                .doOnSuccess(r -> log.info("User logged in: {}", username));
    }

    public Mono<UserResponse> getCurrentUser(Long userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new RuntimeException("User not found")))
                .map(user -> new UserResponse(user.getId(), user.getUsername(), user.getEmail()));
    }
}