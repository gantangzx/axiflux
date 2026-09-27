package com.gantang.tianshu.eval.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;

/** Loads {@link Scenario} instances from YAML (classpath, file, or raw text). */
public final class ScenarioLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ScenarioLoader() {}

    public static Scenario fromYaml(String yaml) {
        try {
            Scenario s = YAML.readValue(yaml, Scenario.class);
            if (s == null || s.id() == null) {
                throw new IllegalArgumentException("Scenario missing required field: id");
            }
            return s;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid scenario YAML: " + e.getMessage(), e);
        }
    }

    public static Scenario fromPath(Path path) {
        try {
            return fromYaml(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read scenario: " + path, e);
        }
    }

    /** Load from the classpath, e.g. {@code eval/01-text-only.yaml}. */
    public static Scenario fromResource(String classpathLocation) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = ScenarioLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IllegalArgumentException("Scenario resource not found: " + classpathLocation);
            }
            return YAML.readValue(in, Scenario.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read scenario resource " + classpathLocation
                    + ": " + e.getMessage(), e);
        }
    }
}
