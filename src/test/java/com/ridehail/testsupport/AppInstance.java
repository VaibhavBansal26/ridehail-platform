package com.ridehail.testsupport;

import com.ridehail.RidehailApplication;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** One complete running copy of the application on a random port, as a separate server in a multi-server deployment. */
public final class AppInstance implements AutoCloseable {
    private final ConfigurableApplicationContext context;
    private final int port;
    private final String name;

    private AppInstance(String name, ConfigurableApplicationContext context) {
        this.name = name;
        this.context = context;
        this.port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    /** Properties that keep scheduled sweeps quiet so tests are deterministic. */
    public static Map<String, String> quiet() {
        var m = new LinkedHashMap<String, String>();
        m.put("ridehail.dispatch.expiry-scan-ms", "3600000");
        m.put("ridehail.payments.retry-scan-ms", "3600000");
        return m;
    }

    public static AppInstance start(String name, Map<String, String> properties) {
        var args = new LinkedHashMap<String, String>(properties);
        args.put("server.port", "0");
        args.put("spring.application.name", "ridehail-" + name);
        String[] cli = args.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new AppInstance(name, new SpringApplicationBuilder(RidehailApplication.class).bannerMode(Banner.Mode.OFF).run(cli));
    }

    public int port() { return port; }

    public String http() { return "http://localhost:" + port; }

    public String ws() { return "ws://localhost:" + port + "/ws"; }

    public String name() { return name; }

    public <T> T bean(Class<T> type) { return context.getBean(type); }

    @Override
    public void close() {
        context.close();
    }

    @Override
    public String toString() {
        return name + "@" + port;
    }
}
