package com.gantang.axiflux.spring.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code axiflux.email.*}. Split out of the monolithic {@code AxifluxProperties}
 * (P5 config decoupling) into an independent {@code @ConfigurationProperties} bean.
 */
@ConfigurationProperties(prefix = "axiflux.email")


// ===== Email =====

public class EmailProperties {
    private String host;
    private int port = 587;
    private String username;
    private String password;
    private String from;
    private boolean starttls = true;
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public boolean isStarttls() { return starttls; }
    public void setStarttls(boolean starttls) { this.starttls = starttls; }
}
