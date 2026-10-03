package com.gantang.axiflux.spring.observability;

/**
 * Open-core SPI for the slice of conversion-funnel tracking the open-source
 * error handler needs: recording that a request was refused by a plan gate
 * (402) or quota cap (429). The full signup/activation/upgrade tracker lives in
 * the closed-source {@code axiflux-commercial} module.
 */
public interface FunnelTrackerSpi {

    /** A request was refused by a plan gate (402) or the quota cap (429). */
    void quotaHit(String userId, String reason);
}
