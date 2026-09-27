package com.gantang.tianshu.spring.auth;

import com.gantang.tianshu.spring.config.props.AuthProperties;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Selects internal identity fields from a verified JWT without replacing the
 * token decoder or imposing a fixed IdP claim schema.
 */
public class JwtIdentityResolver {

    private final AuthProperties props;

    public JwtIdentityResolver(AuthProperties props) {
        this.props = props;
    }

    public ResolvedJwtIdentity resolve(Jwt jwt) {
        String userId = firstText(jwt, props.getUserIdClaim(), "sub");
        String username = firstText(jwt, props.getUsernameClaim(), "preferred_username", "login");
        String email = firstText(jwt, props.getEmailClaim(), "email");
        String displayName = firstText(jwt, props.getDisplayNameClaim(), "name");
        // Read iss as a plain string: RFC 8414 allows non-URL issuers (URNs,
        // opaque ids), and Jwt.getIssuer() would force URL parsing and throw.
        String issuer = firstText(jwt, "iss");
        return new ResolvedJwtIdentity(userId,
            StringUtils.hasText(username) ? username : userId,
            email, displayName, issuer, scopes(jwt));
    }

    private List<String> scopes(Jwt jwt) {
        List<String> claim = jwt.getClaimAsStringList(AuthTokenService.SCOPES_CLAIM);
        if (claim != null) return normalize(claim);
        String scope = jwt.getClaimAsString("scope");
        if (!StringUtils.hasText(scope)) return List.of();
        return Arrays.stream(scope.split("\\s+"))
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
    }

    private static String firstText(Jwt jwt, String... claims) {
        for (String claim : claims) {
            if (!StringUtils.hasText(claim)) continue;
            String value = jwt.getClaimAsString(claim);
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private static List<String> normalize(List<String> values) {
        return values.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
    }
}
