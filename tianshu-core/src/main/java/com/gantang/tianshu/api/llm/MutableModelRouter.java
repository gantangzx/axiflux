package com.gantang.tianshu.api.llm;

/**
 * A {@link ModelRouter} whose default provider can be switched at runtime
 * (hot-reload / admin console) without recreating the router bean.
 *
 * <p>P1-4 interface contract: the Spring layer previously reached into
 * {@code DefaultModelRouter} with {@code instanceof} checks to detect this
 * capability. Declaring it here lets consumers depend on the API contract
 * only; routers that do not support runtime switching simply do not
 * implement this interface, so the capability check stays explicit and
 * fail-loud ({@code instanceof MutableModelRouter}).
 */
public interface MutableModelRouter extends ModelRouter {

    /**
     * Switch the default provider used as the routing fallback.
     *
     * @param provider registered provider name; implementations should
     *                 validate it against their registry and ignore/throw
     *                 on unknown names (the registry is queryable via
     *                 {@link #get(String)} / {@link #listProviders()})
     */
    void setDefaultProvider(String provider);

    /** Currently active default provider name (the routing fallback). */
    String getDefaultProvider();
}
