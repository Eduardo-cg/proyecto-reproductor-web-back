package com.musicstreaming.auth.service;

import com.musicstreaming.auth.dto.LoginResponse;
import com.musicstreaming.auth.dto.RegisterRequest;
import com.musicstreaming.auth.dto.UserPrincipal;
import com.musicstreaming.auth.dto.UserResponse;
import com.musicstreaming.auth.entity.Role;
import com.musicstreaming.auth.entity.User;
import com.musicstreaming.auth.repository.RoleRepository;
import com.musicstreaming.auth.repository.UserRepository;
import com.musicstreaming.common.exception.ResourceAlreadyExistsException;
import com.musicstreaming.common.exception.ResourceNotFoundException;
import com.musicstreaming.common.exception.UnauthorizedException;
import com.musicstreaming.common.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String STANDARD_ROLE_NAME = "STANDARD";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.jwt.expiration}")
    private long jwtExpiration;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public Mono<UserResponse> register(RegisterRequest request) {
        return roleRepository.findByName(STANDARD_ROLE_NAME)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Role", STANDARD_ROLE_NAME)))
                .flatMap(role -> userRepository.existsByUsername(request.getUsername())
                        .flatMap(exists -> {
                            if (exists) {
                                return Mono.error(new ResourceAlreadyExistsException("Username already exists"));
                            }
                            return userRepository.existsByEmail(request.getEmail());
                        })
                        .flatMap(exists -> {
                            if (exists) {
                                return Mono.error(new ResourceAlreadyExistsException("Email already exists"));
                            }
                            User user = new User(
                                    request.getUsername(),
                                    request.getEmail(),
                                    passwordEncoder.encode(request.getPassword())
                            );
                            user.setRoleId(role.getId());
                            return userRepository.save(user);
                        })
                        .map(user -> new UserResponse(user.getId(), user.getUsername(), user.getEmail(), STANDARD_ROLE_NAME))
                        .doOnSuccess(u -> log.info("User registered userId={} username={}", u.getId(), u.getUsername())));
    }

    public Mono<LoginResponse> login(String username, String password) {
        return userRepository.findByUsername(username)
                .switchIfEmpty(Mono.error(new UnauthorizedException("Invalid username or password")))
                .flatMap(user -> {
                    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
                        return Mono.error(new UnauthorizedException("Invalid username or password"));
                    }
                    return roleRepository.findById(user.getRoleId())
                            .map(role -> {
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
                                        user.getEmail(),
                                        role.getName()
                                );

                                return new LoginResponse(token, jwtExpiration, userInfo);
                            });
                })
                .doOnSuccess(r -> log.info("User logged in userId={} username={}", r.getUser().getId(), username));
    }

    public Mono<UserResponse> getCurrentUser(Long userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("User", userId)))
                .flatMap(user -> roleRepository.findById(user.getRoleId())
                        .map(role -> new UserResponse(user.getId(), user.getUsername(), user.getEmail(), role.getName())));
    }
}
