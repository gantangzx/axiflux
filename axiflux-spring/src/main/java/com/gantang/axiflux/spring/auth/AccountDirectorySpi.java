package com.gantang.axiflux.spring.auth;

/**
 * Open-core SPI for JIT account provisioning during request authentication.
 *
 * <p>Contract shipped in the open-source framework; the implementation backed
 * by the {@code user_account} table lives in the closed-source
 * {@code axiflux-commercial} module. {@link AuthWebFilter} obtains it through an
 * {@code ObjectProvider}, so absent the commercial jar no JIT provisioning runs
 * and authentication falls back to token-only scopes.
 */
public interface AccountDirectorySpi {

    /** Account status value indicating the account may not be used. */
    String STATUS_SUSPENDED = "suspended";

    /**
     * Resolve (creating on first sight) the local account for an authenticated
     * identity.
     *
     * @param userId        stable external subject / user id
     * @param username      display username (nullable)
     * @param email         email (nullable)
     * @param displayName   display name (nullable)
     * @param issuer        identity provider issuer (nullable)
     * @return the local account view
     */
    AccountView provision(String userId, String username, String email,
                          String displayName, String issuer);
}
