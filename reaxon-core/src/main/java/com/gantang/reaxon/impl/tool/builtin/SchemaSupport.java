package com.gantang.reaxon.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for parsing JSON schema literals used by built-in tools.
 * Keeps the tool files declarative without pulling in a schema builder DSL.
 */
final class SchemaSupport {

    private static final Logger log = LoggerFactory.getLogger(SchemaSupport.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private SchemaSupport() {}

    /** Parse a JSON literal into a JsonNode; returns an empty object on failure. */
    static JsonNode parse(String json) {
        try {
            return OM.readTree(json);
        } catch (Exception e) {
            log.error("Failed to parse tool schema: {}", e.getMessage());
            return OM.createObjectNode();
        }
    }
}
