package com.gantang.tianshu.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Session storage backend. Binds {@code tianshu.session.*}.
 *
 * <p>Split out of the monolithic {@code TianshuProperties} (P5 config
 * decoupling): each domain is now an independent {@code @ConfigurationProperties}
 * bean, injected directly where needed instead of reaching through a root
 * aggregate.
 */
@ConfigurationProperties(prefix = "tianshu.session")
public class SessionProperties {

    /** "redis" | "jpa" | "inmemory" — defaults to inmemory when nothing else is available. */
    private String provider = "auto";

    /**
     * Name a conversation after its first user message (console-style auto-title).
     * Set {@code tianshu.session.auto-title=false} for id-only session lists.
     */
    private boolean autoTitle = true;

    /** Longest auto-generated title; a longer first question is truncated with an ellipsis. */
    private int titleMaxLength = com.gantang.tianshu.impl.session.SessionTitleHook.DEFAULT_MAX_LENGTH;

    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }

    public boolean isAutoTitle() { return autoTitle; }
    public void setAutoTitle(boolean v) { this.autoTitle = v; }

    public int getTitleMaxLength() { return titleMaxLength; }
    public void setTitleMaxLength(int v) { this.titleMaxLength = v; }
}
