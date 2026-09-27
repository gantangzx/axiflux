package com.gantang.tianshu.spring.auth;

/**
 * Password hashing / verification abstraction for self-serve local accounts.
 * Implementations must use a salted adaptive hash (BCrypt in production).
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String encodedHash);
}
