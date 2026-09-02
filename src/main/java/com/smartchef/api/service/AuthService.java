package com.smartchef.api.service;

import com.smartchef.api.config.JwtProperties;
import com.smartchef.api.dto.AuthResponse;
import com.smartchef.api.dto.LoginRequest;
import com.smartchef.api.dto.RegisterRequest;
import com.smartchef.api.dto.UserResponse;
import com.smartchef.api.entity.Role;
import com.smartchef.api.entity.User;
import com.smartchef.api.exception.DuplicateResourceException;
import com.smartchef.api.repository.UserRepository;
import com.smartchef.api.security.JwtService;
import com.smartchef.api.security.SmartChefUserDetails;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();

        if (userRepository.existsByUsername(username)) {
            throw new DuplicateResourceException("El usuario '" + username + "' ya esta registrado");
        }
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("El email '" + email + "' ya esta registrado");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName() == null ? null : request.fullName().trim());
        user.setRole(Role.ROLE_USER);

        User saved = userRepository.save(user);

        return AuthResponse.of(
                jwtService.generateToken(saved),
                jwtProperties.expirationSeconds(),
                UserResponse.from(saved));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.username().trim(), request.password()));

        User user = ((SmartChefUserDetails) authentication.getPrincipal()).getUser();

        return AuthResponse.of(
                jwtService.generateToken(user),
                jwtProperties.expirationSeconds(),
                UserResponse.from(user));
    }
}
