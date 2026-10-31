package com.ridehail.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
class SecurityConfig {

    /** Set (production) when metrics are served on their own internal port, which the public port does not expose at all. */
    @org.springframework.beans.factory.annotation.Value("${management.server.port:}")
    private String managementPort;
    @org.springframework.beans.factory.annotation.Value("${server.port:8080}")
    private String serverPort;

    /**
     * /actuator/prometheus is open to a scraper only when it lives on a separate management port. On the public port it stays
     * admin-only: there it would otherwise describe the whole system to anyone.
     */
    private boolean metricsOnInternalPort() {
        return !managementPort.isBlank() && !managementPort.equals(serverPort);
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtAuthenticationConverter jwtConverter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable) // stateless bearer-token API, no cookies
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> {
                if (metricsOnInternalPort()) a.requestMatchers("/actuator/prometheus").permitAll();
                a.requestMatchers("/api/auth/**", "/api/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN") // metrics etc. describe the system: ops only (health/info are open above)
                .requestMatchers("/api/webhooks/**").permitAll() // authenticated by the provider's signature, not a JWT
                .requestMatchers("/ws/**").permitAll() // handshake is open; the STOMP CONNECT frame must carry a JWT
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/riders/**").hasRole("RIDER")
                .requestMatchers("/api/fares/**", "/api/customers/**").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/api/trips").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/api/trips/*/cancel").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/api/trips/*/arrive", "/api/trips/*/start", "/api/trips/*/complete")
                    .hasRole("RIDER")
                .anyRequest().authenticated(); // e.g. GET /api/trips/{id}; ownership is checked in the service
            })
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    JwtDecoder jwtDecoder(JwtService jwt) {
        return jwt.accessDecoder();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
        var cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(props.allowedOrigins());
        cors.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
