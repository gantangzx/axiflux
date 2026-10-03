package com.gantang.axiflux.spring.auth;

import java.util.List;

/**
 * Identity fields selected from a verified JWT using configurable claim names.
 */
public record ResolvedJwtIdentity(
    String userId,
    String username,
    String email,
    String displayName,
    String issuer,
    List<String> scopes) {

    public ResolvedJwtIdentity {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
