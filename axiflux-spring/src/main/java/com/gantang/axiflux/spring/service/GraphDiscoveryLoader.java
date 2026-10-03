package com.gantang.axiflux.spring.service;

import com.gantang.reaxon.api.workflow.StateGraph;
import com.gantang.reaxon.impl.workflow.YamlStateGraphLoader;
import com.gantang.axiflux.spring.config.props.WorkflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Discovers {@link StateGraph} definitions from the configured classpath location
 * and an optional external filesystem directory, then registers them in a
 * {@link GraphCatalog}.
 *
 * <p>Files ending in {@code .yml}/{@code .yaml} are parsed as YAML; {@code .md}
 * files are parsed as Markdown carrying a YAML front-matter block. A single bad
 * file is skipped with a warning rather than failing startup.
 */
public final class GraphDiscoveryLoader {

    private static final Logger log = LoggerFactory.getLogger(GraphDiscoveryLoader.class);

    private final WorkflowProperties props;
    private final YamlStateGraphLoader loader = new YamlStateGraphLoader();
    private final PathMatchingResourcePatternResolver resolver =
        new PathMatchingResourcePatternResolver();

    public GraphDiscoveryLoader(WorkflowProperties props) {
        this.props = props;
    }

    /** Scan all configured sources and populate {@code catalog}. */
    public void loadInto(GraphCatalog catalog) {
        loadFromClasspath(catalog);
        String dir = props.getDirectory();
        if (dir != null && !dir.isBlank()) {
            loadFromDirectory(Path.of(dir.trim()), catalog);
        }
    }

    private void loadFromClasspath(GraphCatalog catalog) {
        String location = props.getClasspathLocation();
        if (location == null || location.isBlank()) {
            return;
        }
        String root = normalize(location);
        String pattern = "classpath*:" + root + "/**/*.{yml,yaml,md}";
        try {
            Resource[] resources = resolver.getResources(pattern);
            for (Resource resource : resources) {
                if (!resource.isReadable()) {
                    continue;
                }
                String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                String filename = resource.getFilename() == null ? resource.toString() : resource.getFilename();
                register(catalog, filename, content);
            }
        } catch (IOException e) {
            log.warn("Graph classpath scan failed at {}: {}", pattern, e.toString());
        }
    }

    private void loadFromDirectory(Path directory, GraphCatalog catalog) {
        if (!Files.isDirectory(directory)) {
            log.warn("Configured workflow directory not found: {}", directory);
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                .filter(GraphDiscoveryLoader::isGraphFile)
                .forEach(path -> {
                    try {
                        String content = Files.readString(path, StandardCharsets.UTF_8);
                        register(catalog, path.getFileName().toString(), content);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Graph filesystem scan failed at {}: {}", directory, e.toString());
        }
    }

    private void register(GraphCatalog catalog, String filename, String content) {
        try {
            StateGraph graph = filename.endsWith(".md")
                ? loader.loadDocument(content)
                : loader.load(content);
            catalog.register(graph);
            log.debug("Registered workflow graph '{}' from {}", graph.name(), filename);
        } catch (RuntimeException e) {
            log.warn("Skipping invalid graph definition {}: {}", filename, e.toString());
        }
    }

    private static boolean isGraphFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".md");
    }

    private static String normalize(String location) {
        String trimmed = location.trim().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
