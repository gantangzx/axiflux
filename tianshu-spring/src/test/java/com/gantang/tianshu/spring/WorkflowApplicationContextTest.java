package com.gantang.tianshu.spring;

import com.gantang.tianshu.api.workflow.GraphRunner;
import com.gantang.tianshu.spring.service.GraphCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots a real Spring reactive application context over the published auto-configuration
 * imports to prove the workflow engine assembles end-to-end (runner + catalog beans) and
 * discovers the bundled example graphs at startup.
 */
@SpringBootTest(
    classes = WorkflowApplicationContextTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "tianshu.auth.enabled=false",
        "tianshu.jwt.enabled=false"
    })
class WorkflowApplicationContextTest {

    @Configuration
    @EnableAutoConfiguration
    static class TestApp { }

    @Autowired
    private GraphRunner graphRunner;

    @Autowired
    private GraphCatalog graphCatalog;

    @Test
    void contextWiresWorkflowEngine() {
        assertNotNull(graphRunner);
        assertNotNull(graphCatalog);
        // Bundled examples (echo, triage) must have been discovered at startup.
        assertTrue(graphCatalog.find("echo").isPresent(), "echo graph not registered");
        assertTrue(graphCatalog.find("triage").isPresent(), "triage graph not registered");
    }
}
