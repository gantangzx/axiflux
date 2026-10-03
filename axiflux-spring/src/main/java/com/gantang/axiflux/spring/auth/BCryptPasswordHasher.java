package com.gantang.axiflux.spring.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt-backed {@link PasswordHasher}. A single internal encoder supplies both
 * hashing and verification; BCrypt embeds its own per-password salt.
 */
public class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedHash) {
        if (rawPassword == null || encodedHash == null || encodedHash.isBlank()) return false;
        return encoder.matches(rawPassword, encodedHash);
    }
}
