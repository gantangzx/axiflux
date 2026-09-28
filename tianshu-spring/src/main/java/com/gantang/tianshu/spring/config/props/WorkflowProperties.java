package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code tianshu.workflow.*} for the state-graph workflow engine.
 *
 * <p>Graphs are discovered on the classpath under {@link #classpathLocation}
 * (a directory of {@code *.yml} / {@code *.md} resources) and, when set, in the
 * filesystem directory {@link #directory}. The engine defaults to enabled and
 * ships with the bundled example graphs.
 */
@ConfigurationProperties(prefix = "tianshu.workflow")
public class WorkflowProperties {

    private boolean enabled = true;
    /** Classpath directory scanned for bundled graph definitions. */
    private String classpathLocation = "graphs";
    /** Optional external filesystem directory also scanned for graph definitions. */
    private String directory = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getClasspathLocation() { return classpathLocation; }
    public void setClasspathLocation(String v) { this.classpathLocation = v; }
    public String getDirectory() { return directory; }
    public void setDirectory(String v) { this.directory = v; }
}
