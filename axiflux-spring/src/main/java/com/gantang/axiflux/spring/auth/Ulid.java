package com.gantang.axiflux.spring.auth;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Minimal ULID generator (Crockford base32, 26 chars, 128 bits):
 * a 48-bit millisecond timestamp followed by 80 random bits.
 *
 * <p>ULIDs are generated locally with no node-id coordination (unlike a
 * snowflake Long) and sort lexicographically by creation time, making them a
 * good opaque surrogate key for accounts that may be provisioned across many
 * embedded, disconnected instances.
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final BigInteger BASE = BigInteger.valueOf(32);
    private static final BigInteger MASK80 = BigInteger.ONE.shiftLeft(80).subtract(BigInteger.ONE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {}

    public static String generate() {
        long timestamp = System.currentTimeMillis();
        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);
        BigInteger randomness = new BigInteger(1, randomBytes).and(MASK80);
        BigInteger value = BigInteger.valueOf(timestamp).shiftLeft(80).or(randomness);

        StringBuilder sb = new StringBuilder(26);
        while (value.signum() > 0) {
            BigInteger[] divrem = value.divideAndRemainder(BASE);
            sb.append(ALPHABET[divrem[1].intValue()]);
            value = divrem[0];
        }
        while (sb.length() < 26) {
            sb.append(ALPHABET[0]);
        }
        return sb.reverse().toString();
    }
}
