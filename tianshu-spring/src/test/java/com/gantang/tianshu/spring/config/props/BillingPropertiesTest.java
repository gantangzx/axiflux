package com.gantang.tianshu.spring.config.props;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BillingPropertiesTest {

    private BillingProperties props() {
        BillingProperties p = new BillingProperties();
        p.setTierMonthlyPrice(Map.of("pro", "price_pro_month"));
        p.setTierYearlyPrice(Map.of("pro", "price_pro_year", "team", "price_team_year"));
        return p;
    }

    @Test
    void resolvesMonthlyPrice() {
        assertEquals("price_pro_month", props().resolvePriceId("pro", "month"));
    }

    @Test
    void resolvesYearlyPrice() {
        assertEquals("price_pro_year", props().resolvePriceId("pro", "year"));
    }

    @Test
    void yearlyFallsBackToMonthlyWhenUnconfigured() {
        // team has a yearly price in props(), but use a fresh props without it.
        BillingProperties p = new BillingProperties();
        p.setTierMonthlyPrice(Map.of("team", "price_team_month"));
        assertEquals("price_team_month", p.resolvePriceId("team", "year"));
    }

    @Test
    void fallsBackToLegacyTierPrice() {
        BillingProperties p = new BillingProperties();
        p.setTierPrice(Map.of("pro", "price_legacy"));
        assertEquals("price_legacy", p.resolvePriceId("pro", "month"));
        assertEquals("price_legacy", p.resolvePriceId("pro", "year"));
    }

    @Test
    void returnsNullWhenNothingConfigured() {
        assertNull(new BillingProperties().resolvePriceId("pro", "month"));
    }

    @Test
    void promotionCodesEnabledByDefault() {
        assertTrue(new BillingProperties().isAllowPromotionCodes());
    }
}
