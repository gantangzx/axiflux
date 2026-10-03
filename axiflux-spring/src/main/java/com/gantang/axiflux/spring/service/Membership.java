package com.gantang.axiflux.spring.service;

/**
 * Lightweight organization membership view exposed by the open-core
 * {@link OrgDirectorySpi}. Deliberately a plain record (not the JPA entity,
 * which lives in the closed-source module) so open-source transports never
 * depend on a persistence type that may be absent.
 *
 * @param orgId organization the user belongs to
 * @param role  the user's role within {@code orgId}
 *              (OWNER / ADMIN / MEMBER / VIEWER)
 */
public record Membership(String orgId, String role) {
}
