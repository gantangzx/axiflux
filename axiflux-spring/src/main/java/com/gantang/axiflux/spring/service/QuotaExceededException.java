package com.gantang.axiflux.spring.service;

/**
 * Domain failure for the open-core {@link QuotaServiceSpi}: carries the intended
 * HTTP status (429 Too Many Requests) and the breached dimension (tokens /
 * turns) along with the current {@link QuotaStatus}. The web layer maps it
 * without the SPI depending on web types.
 */
public class QuotaExceededException extends HttpDomainException {
    private final String dimension;
    private final QuotaStatus status;

    public QuotaExceededException(String message, String dimension, QuotaStatus status) {
        super(429, message);
        this.dimension = dimension;
        this.status = status;
    }

    public String dimension() { return dimension; }
    public QuotaStatus quotaStatus() { return status; }

    public static QuotaExceededException tokens(QuotaStatus s) {
        return new QuotaExceededException(
            "monthly token quota exceeded for this organization", "tokens", s);
    }

    public static QuotaExceededException turns(QuotaStatus s) {
        return new QuotaExceededException(
            "monthly turn quota exceeded for this organization", "turns", s);
    }
}
