package com.gantang.reaxon.api.observability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redacts sensitive values (API keys, tokens, passwords, cookies, credentials)
 * from tool-call arguments before they are persisted in the audit log.
 *
 * <p>Applied at the persistence boundary ({@code tool_executions} table) so that
 * a prompt-injection driven tool call can never exfiltrate secrets into the
 * audit store, and operators reading logs are not exposed to credentials.
 */
public final class SecretMasker {

    private SecretMasker() {}

    public static final String MASK = "***";

    private static final Set<String> EXACT_KEYS = Set.of(
            "apikey", "api_key",
            "token", "accesstoken", "access_token", "refreshtoken", "refresh_token",
            "secret", "client_secret", "clientsecret",
            "password", "passwd", "pwd",
            "credential", "credentials",
            "cookie", "authorization",
            "access_key", "accesskey", "secret_key", "secretkey");

    /**
     * Return a deep copy of {@code value} with sensitive leaf values replaced
     * by {@link #MASK}. Non-map/non-list values are returned unchanged.
     */
    public static Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (isSensitive(key)) {
                    out.put(key, MASK);
                } else {
                    out.put(key, mask(e.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(mask(o));
            }
            return out;
        }
        if (value instanceof Object[] arr) {
            List<Object> out = new ArrayList<>(arr.length);
            for (Object o : arr) {
                out.add(mask(o));
            }
            return out;
        }
        return value;
    }

    private static boolean isSensitive(String key) {
        if (key == null) return false;
        String k = key.toLowerCase().replace('-', '_');
        if (EXACT_KEYS.contains(k)) {
            return true;
        }
        // compound keys in any naming style: x-api-key, accessToken, user_password,
        // dbPassword, clientSecret, ...
        return k.contains("api_key") || k.contains("apikey")
                || k.contains("token")
                || k.contains("jwt")
                || k.contains("secret")
                || k.contains("password") || k.contains("passwd") || k.contains("pwd")
                || k.contains("credential");
    }

    // ===== Free-text masking (for error messages, stack traces, URLs) =====

    /**
     * Credential-shaped substrings inside free text (error messages, exception
     * toStrings, request lines). These fire even when the secret is embedded in a
     * URL query, an Authorization header echo, or a JSON-ish blob, where key-based
     * masking ({@link #mask(Object)}) cannot reach.
     */
    private static final List<Pattern> TEXT_PATTERNS = List.of(
        // Authorization: Bearer xxx / Basic xxx
        Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}"),
        // key=value / key:value forms inside URLs, headers, JSON-ish text
        Pattern.compile("(?i)\\b(api[-_]?key|access[-_]?token|refresh[-_]?token|secret|password|passwd|pwd|credential|auth(?:orization)?)(\\s*[=:]\\s*)\\S+"),
        // well-known token shapes
        Pattern.compile("\\bsk-[A-Za-z0-9]{8,}"),                                  // OpenAI-style
        Pattern.compile("\\bark-[0-9a-f]{8}-[0-9a-f-]{8,}"),                          // ARK plan keys
        Pattern.compile("\\beyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}") // JWT
    );

    /**
     * Mask credential-shaped substrings inside free text (e.g. an exception message
     * that echoes a request line or URL). Unlike {@link #mask(Object)} this scans the
     * raw string, so it catches secrets that were never structured as a map value.
     * Returns the input unchanged when it is null or contains nothing suspicious.
     */
    public static String maskString(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Pattern p : TEXT_PATTERNS) {
            out = p.matcher(out).replaceAll(m -> {
                // Preserve the key label for key=value forms so logs stay readable:
                // "api_key=abc123" -> "api_key=***"; "Bearer xxx" -> "***".
                String whole = m.group();
                int eq = Math.max(whole.indexOf('='), whole.indexOf(':'));
                if (eq > 0 && eq < 30 && whole.substring(0, eq).matches("(?i).*(key|token|secret|password|passwd|pwd|credential|auth).*")) {
                    return whole.substring(0, eq + 1) + MASK;
                }
                return MASK;
            });
        }
        return out;
    }
}
