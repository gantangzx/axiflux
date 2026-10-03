package com.gantang.axiflux.spring.service;

/**
 * Common base type for service-layer domain failures that carry the HTTP status
 * the web layer should map to, without the service depending on web types.
 *
 * <p>Extends {@link RuntimeException} so an {@code @ExceptionHandler} can match
 * it directly as a throwable and each subclass supplies its status and message
 * via the constructor / {@link #status()}.
 */
public class HttpDomainException extends RuntimeException {

    private final int status;

    public HttpDomainException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
