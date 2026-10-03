package com.gantang.axiflux.spring.auth;

import java.util.List;

/**
 * Account view required by the open-source {@link AuthWebFilter}: the fields it
 * reads to enforce status and merge administrator-granted roles. A plain record
 * rather than the closed-source JPA entity.
 *
 * @param id     internal surrogate id (ULID) used as the on-behalf-of user id
 * @param status account status ("active" / "suspended")
 * @param roles  administrator-granted role scopes (never null)
 */
public record AccountView(String id, String status, List<String> roles) {

    public AccountView {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
