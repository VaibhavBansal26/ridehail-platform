package com.ridehail.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GeoConfig {
    private static final Logger log = LoggerFactory.getLogger(GeoConfig.class);

    @Bean
    GeoProvider geoProvider(GeoProperties props, ObjectMapper json) {
        if (props.provider() == GeoProperties.Provider.FAKE) {
            log.info("Address search and routing: simulated (a few well-known places, straight-line routes)");
            return new FakeGeoProvider();
        }
        // only the hosts are logged: the key, if any, never is
        log.info("Address search via {}, routing via {}", host(props.searchUrl()), host(props.routingUrl()));
        if (host(props.searchUrl()).endsWith("openstreetmap.org") || host(props.routingUrl()).endsWith("project-osrm.org")) {
            log.warn("Using the PUBLIC OpenStreetMap search and routing servers. Their usage policies allow light use only: for real traffic point"
                    + " ridehail.geo.search-url and routing-url at your own or a paid service, and set ridehail.geo.user-agent to identify this application.");
        }
        return new OsmGeoProvider(props, json);
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost() == null ? "?" : URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }
}
