package com.gantang.axiflux.spring.service;

import java.util.Optional;

/**
 * Open-core SPI for organization lookups needed by the open-source transports.
 *
 * <p>Only the handful of reads the framework itself performs are declared here
 * (primary membership, plan tier, tool allow-list). The full organization /
 * member / invitation / billing lifecycle is a closed-source concern in
 * {@code axiflux-commercial} and is not part of this contract.
 *
 * <p>All methods are best-effort: implementations must degrade gracefully (empty
 * result / {@code free} tier) rather than failing a request when a lookup breaks.
 */
public interface OrgDirectorySpi {

    /** Primary organization membership of the user (first joined), if any. */
    Optional<Membership> primaryMembership(String userId);

    /** The org's plan tier ("free" on unknown/missing). */
    String getPlanTier(String orgId);

    /**
     * The organization's tool allow-list, or {@code null}/blank when no
     * org-level restriction is configured.
     */
    String allowedTools(String orgId);
}
