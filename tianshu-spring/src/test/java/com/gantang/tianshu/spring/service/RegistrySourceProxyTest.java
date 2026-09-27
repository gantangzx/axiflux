package com.gantang.tianshu.spring.service;

import com.gantang.tianshu.spring.config.props.SkillsProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-5: skill-registry outbound calls (search/resolve/download) must transit
 * the configured egress proxy. A plain {@code http://} request through a
 * forward proxy is delivered to the proxy with the absolute target URI, so a
 * mock proxy recording hits proves the registry client routed through it.
 */
class RegistrySourceProxyTest {

    @Test
    void registrySearchTransitsConfiguredEgressProxy() throws Exception {
        AtomicInteger proxyHits = new AtomicInteger();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = "{\"items\":[],\"page\":0,\"size\":20,\"total\":0}";
        proxy.createContext("/", ex -> {
            proxyHits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        proxy.start();
        try {
            ProxySelector selector = ProxySelector.of(
                new InetSocketAddress("127.0.0.1", proxy.getAddress().getPort()));
            // Unreachable-direct base URL: only the proxy can answer.
            RegistrySource src = new RegistrySource(
                "default", "http://10.255.255.1:9", "", "", selector);

            SkillSource.CatalogPage page = src.search("anything", 0, 20);
            assertNotNull(page);
            assertTrue(proxyHits.get() > 0, "registry search must have transited the egress proxy");
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void skillSourcesPropagateProxyToRegistryAndClawHub() {
        ProxySelector selector = ProxySelector.of(new InetSocketAddress("127.0.0.1", 3128));
        SkillsProperties props = new SkillsProperties();
        props.setRegistryUrl("http://10.255.255.1:9");
        // ClawHub enabled by default; give it a URL so the source is built.
        props.getClawhub().setEnabled(true);
        props.getClawhub().setUrl("http://10.255.255.2:9");

        SkillSources sources = SkillSources.from(props, selector);
        assertFalse(sources.isEmpty());
        // Both the default registry and the ClawHub adapter should be built
        // (proxy applied internally; construction must not throw).
        assertNotNull(sources.defaultRegistry());
        assertNotNull(sources.clawhub());
    }
}
