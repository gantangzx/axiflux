package com.gantang.tianshu.spring.auth;

/**
 * Domain failure for the OIDC SSO flow carrying the HTTP status the controller
 * maps to, without the auth package depending on web types.
 */
public class SsoException extends RuntimeException {

    private final int status;

    public SsoException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
