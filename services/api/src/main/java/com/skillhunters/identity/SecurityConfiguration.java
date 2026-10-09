package com.skillhunters.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.shared.ApiProblem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties.class)
public class SecurityConfiguration {
    private final ObjectMapper json;
    public SecurityConfiguration(ObjectMapper json) { this.json = json; }
    private void error(HttpServletResponse response, HttpStatus status, String code) throws IOException {
        response.setStatus(status.value()); response.setContentType("application/problem+json");
        json.writeValue(response.getOutputStream(), ApiProblem.detail(status, code));
    }

    @Bean
    ClientRegistrationRepository clients(org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties properties) {
        var registration = properties.getRegistration().get("skillhunters");
        var provider = properties.getProvider().get("skillhunters");
        var client = org.springframework.security.oauth2.client.registration.ClientRegistration.withRegistrationId("skillhunters")
            .clientId(registration.getClientId()).clientSecret(registration.getClientSecret())
            .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
            .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .redirectUri(registration.getRedirectUri()).scope(registration.getScope())
            .issuerUri(provider.getIssuerUri()).authorizationUri(provider.getAuthorizationUri())
            .tokenUri(provider.getTokenUri()).jwkSetUri(provider.getJwkSetUri())
            .userInfoUri(provider.getUserInfoUri()).userNameAttributeName("sub").clientName("Skill Hunters").build();
        return new org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository(client);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ClientRegistrationRepository clients,
            CurrentAccount accounts, IdentityStore store, @Value("${app.origin}") String origin) throws Exception {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(builder -> {
            OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
            // Re-authentication is required after local logout; an IdP session never silently logs back in.
            builder.additionalParameters(p -> p.put("prompt", "login"));
        });
        var csrf = new HttpSessionCsrfTokenRepository(); csrf.setHeaderName("X-CSRF-Token");
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.svg", "/error", "/actuator/health", "/actuator/health/**",
                        "/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                .requestMatchers("/api/**").authenticated().anyRequest().denyAll())
            .csrf(c -> c.csrfTokenRepository(csrf))
            .requestCache(c -> c.requestCache(new NullRequestCache()))
            .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> error(res, HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED"))
                    .accessDeniedHandler((req, res, ex) -> error(res, HttpStatus.FORBIDDEN, "ACCESS_DENIED")))
            .oauth2Login(o -> o.authorizationEndpoint(a -> a.authorizationRequestResolver(resolver))
                    .successHandler((req, res, auth) -> {
                        try {
                            var user = accounts.require(auth); store.audit(user.id(), "SESSION_LOGIN");
                            res.sendRedirect(origin + "/");
                        } catch (ApiProblem.Rejected denied) {
                            new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler().logout(req, res, auth);
                            store.audit(null, "SESSION_LOGIN_DENIED");
                            res.sendRedirect(origin + "/?login=not-authorized");
                        }
                    })
                    .failureHandler((req, res, ex) -> { store.audit(null, "SESSION_LOGIN_FAILED"); res.sendRedirect(origin + "/?login=failed"); }))
            .logout(l -> l.disable())
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")));
        return http.build();
    }
}
