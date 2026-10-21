package com.fundaccounting.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Every service is an OAuth 2.0 resource server: it validates the OIDC access token (signature via the identity
 * provider's JWKS, issuer, expiry) on every request, even behind the gateway (defense in depth).
 *
 * <p>Roles come from the token: {@code fa-viewer} (read), {@code fa-accountant} (book trades and capital),
 * {@code fa-admin} (close and reopen periods). Writes need accountant; reads need viewer.
 */
@AutoConfiguration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(@Value("${platform.security.jwk-set-uri}") String jwkSetUri,
                          @Value("${platform.security.issuer}") String issuer) {
        // JWKS fetched from the internal URL; issuer checked against the public URL the browser used to sign in.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer)));
        return decoder;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> roles(jwt.getClaimAsMap("realm_access")));
        return http
                .csrf(c -> c.disable())   // stateless bearer-token API: no cookies, so no CSRF
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("fa-viewer", "fa-accountant", "fa-admin")
                        .requestMatchers("/api/**").hasAnyRole("fa-accountant", "fa-admin")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .build();
    }

    @SuppressWarnings("unchecked")
    static Collection<GrantedAuthority> roles(Map<String, Object> realmAccess) {
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) return List.of();
        return ((List<String>) roles).stream().filter(r -> r.startsWith("fa-"))
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }
}
