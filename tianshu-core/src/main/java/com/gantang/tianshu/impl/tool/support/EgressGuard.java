package com.gantang.tianshu.impl.tool.support;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Shared SSRF / egress guard.
 *
 * <p>Used both by the policy layer ({@code NetworkEgressPolicy}, which checks
 * the original tool arguments) and by the HTTP tools themselves, which must
 * re-validate every redirect hop — otherwise a public URL that 302-redirects to
 * {@code http://169.254.169.254/} would bypass the argument-level check.
 *
 * <p>Blocks non-http(s) schemes, loopback / link-local / private / multicast
 * IPs, localhost and cloud-metadata hostnames.
 *
 * <p><b>DNS rebinding:</b> for non-literal hostnames this also resolves the
 * name via {@link InetAddress#getAllByName(String)} and rejects it if any
 * resolved address is internal — this catches a public domain whose A/AAAA
 * record points at 10.x/169.254.x/127.x.
 *
 * @apiNote <b>TOCTOU DNS rebinding limitation:</b> a TTL=0 name that returns
 * a public IP at check time and an internal IP at connect time cannot be
 * prevented by this guard alone. True mitigation requires <b>connection
 * pinning</b> (using {@link #resolveAndPin(String)} to obtain the verified IP
 * list and connecting directly to that IP with a {@code Host} header) or an
 * <b>egress proxy</b> as the final deployment control. This layer removes the
 * common, static case; callers that need hard TOCTOU guarantees must use
 * {@link #resolveAndPin(String)} or route through an approved egress proxy.
 */
public final class EgressGuard {

    private static final Logger LOG = Logger.getLogger(EgressGuard.class.getName());

    private EgressGuard() {}

    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost",
            "metadata.google.internal",
            "metadata",
            "169.254.169.254.nip.io");

    /**
     * @return {@code null} when the URL is allowed, otherwise a human-readable deny reason.
     */
    public static String denyReason(String raw, boolean allowPrivateNetwork) {
        if (raw == null || raw.isBlank()) {
            return "empty URL";
        }
        final URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (Exception e) {
            return "malformed URL: " + raw;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "scheme '" + scheme + "' is not permitted; only http/https are allowed";
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.isEmpty()) {
            return "URL has no host: " + raw;
        }
        if (BLOCKED_HOSTNAMES.contains(host) || host.endsWith(".localhost")) {
            return "access to internal host '" + host + "' is blocked";
        }
        String ip = stripBrackets(host);
        // Cloud metadata / link-local addresses are ALWAYS blocked — even when
        // private networks are explicitly allowed — because they hand out
        // temporary cloud credentials and are the prime SSRF exfiltration target.
        if (isLinkLocalOrMetadata(ip)) {
            return "access to link-local/cloud-metadata address '" + host
                    + "' is blocked (SSRF guard)";
        }
        if (allowPrivateNetwork) {
            // Even when private networks are allowed, re-resolve hostnames to
            // catch a public name that DNS-maps to a cloud-metadata/link-local
            // address (those are always blocked). Site-local/loopback are
            // permitted because the operator explicitly opted in.
            return resolveDenyReason(host, ip, true);
        }
        if (isBlockedIp(ip)) {
            return "access to private/loopback address '" + host
                    + "' is blocked (SSRF guard)";
        }
        return resolveDenyReason(host, ip, false);
    }

    /**
     * Resolve a non-literal hostname and reject if any resolved address is
     * internal. Literal IPs are skipped (already checked by the caller).
     * Link-local/metadata (169.254/fe80) are always denied regardless of the
     * private-network toggle; site-local/loopback are only denied when private
     * networks are not explicitly allowed.
     */
    private static String resolveDenyReason(String host, String ipLiteral, boolean allowPrivateNetwork) {
        // If the host is already a literal IPv4/IPv6 address, no DNS lookup needed.
        boolean isLiteralV4 = ipLiteral.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean isLiteralV6 = ipLiteral.indexOf(':') >= 0;
        if (isLiteralV4 || isLiteralV6) {
            return null;
        }
        final InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // Fail-open: a name that cannot resolve cannot be pointed at an internal
            // address by a rebinding attacker either (the actual connection would fail
            // the same way). Blocking here would break offline/no-DNS environments.
            return null;
        }
        for (InetAddress a : addrs) {
            String resolved = a.getHostAddress();
            if (resolved != null && resolved.indexOf('%') >= 0) {
                resolved = resolved.substring(0, resolved.indexOf('%')); // strip scope id
            }
            if (isLinkLocalOrMetadata(resolved) || a.isLinkLocalAddress()) {
                LOG.warning("[EgressGuard] DNS TOCTOU check: host '" + host
                        + "' resolved to link-local/cloud-metadata address " + resolved
                        + " — DENIED (SSRF guard)");
                return "host '" + host + "' resolves to link-local/cloud-metadata address "
                        + resolved + " (SSRF guard)";
            }
            if (!allowPrivateNetwork && (a.isLoopbackAddress() || a.isAnyLocalAddress()
                    || a.isSiteLocalAddress() || a.isMulticastAddress() || isBlockedIp(resolved))) {
                LOG.warning("[EgressGuard] DNS TOCTOU check: host '" + host
                        + "' resolved to internal address " + resolved
                        + " — DENIED (DNS-rebinding guard)");
                return "host '" + host + "' resolves to internal address "
                        + resolved + " (DNS-rebinding guard)";
            }
        }
        // Log resolved IPs for TOCTOU auditability
        if (LOG.isLoggable(java.util.logging.Level.FINE)) {
            LOG.fine("[EgressGuard] DNS resolved host '" + host + "' to "
                    + Arrays.toString(addrs) + " — allowed");
        }
        return null;
    }

    /**
     * Resolve a hostname and return the verified IP list for connection pinning.
     * The caller should connect directly to one of these IPs and supply the
     * original hostname via the {@code Host} header (HTTP/1.1) or TLS SNI.
     *
     * <p>This eliminates the TOCTOU DNS-rebinding window: the IPs returned here
     * have already been validated by {@link #denyReason(String, boolean)} and
     * are the exact addresses the connection should use, preventing a TTL=0
     * rebinding attack where the second resolution returns an internal IP.
     *
     * @param host the hostname to resolve (not a URL)
     * @param allowPrivateNetwork whether private/loopback ranges are permitted
     * @return immutable list of verified {@link InetAddress} objects; empty if
     *         the host is denied or cannot resolve
     */
    public static List<InetAddress> resolveAndPin(String host, boolean allowPrivateNetwork) {
        if (host == null || host.isBlank()) return List.of();
        String lower = host.toLowerCase().trim();
        if (BLOCKED_HOSTNAMES.contains(lower) || lower.endsWith(".localhost")) {
            return List.of();
        }
        String ip = stripBrackets(lower);
        if (isLinkLocalOrMetadata(ip)) {
            return List.of();
        }
        boolean isLiteralV4 = ip.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean isLiteralV6 = ip.indexOf(':') >= 0;
        if (isLiteralV4 || isLiteralV6) {
            if (!allowPrivateNetwork && isBlockedIp(ip)) {
                return List.of();
            }
            try {
                return List.of(InetAddress.getByName(ip));
            } catch (UnknownHostException e) {
                return List.of();
            }
        }
        final InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(lower);
        } catch (UnknownHostException e) {
            return List.of();
        }
        for (InetAddress a : addrs) {
            String resolved = a.getHostAddress();
            if (resolved != null && resolved.indexOf('%') >= 0) {
                resolved = resolved.substring(0, resolved.indexOf('%'));
            }
            if (isLinkLocalOrMetadata(resolved) || a.isLinkLocalAddress()) {
                LOG.warning("[EgressGuard] resolveAndPin: host '" + lower
                        + "' resolved to link-local/cloud-metadata address " + resolved + " — DENIED");
                return List.of();
            }
            if (!allowPrivateNetwork && (a.isLoopbackAddress() || a.isAnyLocalAddress()
                    || a.isSiteLocalAddress() || a.isMulticastAddress() || isBlockedIp(resolved))) {
                LOG.warning("[EgressGuard] resolveAndPin: host '" + lower
                        + "' resolved to internal address " + resolved + " — DENIED");
                return List.of();
            }
        }
        return Collections.unmodifiableList(Arrays.asList(addrs));
    }

    /** True when a string value looks like a URL the policy should inspect. */
    public static boolean looksLikeUrl(String s) {
        if (s == null) return false;
        return s.startsWith("http://") || s.startsWith("https://")
                || s.startsWith("file://") || s.startsWith("ftp://")
                || s.startsWith("gopher://") || s.startsWith("dict://");
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /** True for 169.254.0.0/16 (incl. cloud metadata 169.254.169.254) and IPv6 fe80::/10 link-local. */
    static boolean isLinkLocalOrMetadata(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        if (ip.indexOf(':') >= 0) {
            String lower = ip.toLowerCase();
            return lower.startsWith("fe80") || lower.startsWith("fe9")
                    || lower.startsWith("fea") || lower.startsWith("feb");
        }
        String[] octets = ip.split("\\.");
        if (octets.length != 4) return false;
        try {
            int o0 = Integer.parseInt(octets[0]);
            int o1 = Integer.parseInt(octets[1]);
            return o0 == 169 && o1 == 254;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** True for literal IPv4/IPv6 loopback, private, link-local, multicast or unspecified addresses. */
    static boolean isBlockedIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        if (ip.indexOf(':') >= 0) {
            // IPv6 literal
            String lower = ip.toLowerCase();
            if ("::1".equals(lower) || "::".equals(lower)) return true;
            if (lower.startsWith("fe80") || lower.startsWith("fe9")
                    || lower.startsWith("fea") || lower.startsWith("feb")) return true;
            if (lower.startsWith("fc") || lower.startsWith("fd")) return true;
            return false;
        }
        String[] octets = ip.split("\\.");
        if (octets.length != 4) return false; // not an IPv4 literal → hostname, allowed at this layer
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                o[i] = Integer.parseInt(octets[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (o[i] < 0 || o[i] > 255) return false;
        }
        if (o[0] == 127) return true;                // loopback
        if (o[0] == 10) return true;                 // private 10/8
        if (o[0] == 0) return true;                  // 0.0.0.0/8 "this" network
        if (o[0] == 169 && o[1] == 254) return true; // link-local + cloud metadata
        if (o[0] == 192 && o[1] == 168) return true; // private 192.168/16
        if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return true; // private 172.16/12
        if (o[0] == 100 && o[1] >= 64 && o[1] <= 127) return true; // CGNAT 100.64/10
        if (o[0] >= 224) return true;                // multicast + reserved
        return false;
    }
}
