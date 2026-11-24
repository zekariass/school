package com.nemaz.school.system.security;

import java.util.List;

import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.common.web.RequestIds;
import com.nemaz.school.system.SchoolProperties;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * API security: stateless bearer tokens, deny-by-default, problem-details errors.
 * <ul>
 * <li>Access tokens are ES256 JWTs issued by the identity module, which also provides the
 * {@link JwtDecoder} (issuer, audience, expiry, signature) and the converter that turns a
 * token into the {@code CurrentUser} principal (session deny-list, account status, role
 * grants).</li>
 * <li>Sign-in, refresh and logout are public: they authenticate with a code, a password or
 * a refresh token instead. Bearer headers are ignored on them, so a stale access token
 * cannot block a refresh.</li>
 * <li>The WebSocket handshake at {@code /ws} is open at HTTP level: the real-time gateway
 * authenticates the STOMP CONNECT frame (bearer token or single-use ticket) and checks the
 * Origin header against {@code school.cors.allowed-origins}.</li>
 * <li>Method security ({@code @PreAuthorize}) checks permissions per endpoint, usually through
 * the {@code @authz} bean (scope-aware).</li>
 * <li>CSRF protection is off: no cookies are read, and the web app's BFF adds an Origin check
 * (M1c).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

	/** Unauthenticated endpoints of the identity module (POST only). */
	public static final String[] PUBLIC_AUTH_ENDPOINTS = { "/api/v1/auth/otp/request", "/api/v1/auth/otp/verify",
			"/api/v1/auth/password", "/api/v1/auth/refresh", "/api/v1/auth/logout" };

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, ProblemSecurityHandlers handlers, SchoolProperties props,
			JwtDecoder jwtDecoder, Converter<Jwt, AbstractAuthenticationToken> principalConverter) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.exceptionHandling((ex) -> ex.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
			.oauth2ResourceServer((resource) -> resource.bearerTokenResolver(bearerTokenResolver())
				.authenticationEntryPoint(handlers)
				.accessDeniedHandler(handlers)
				.jwt((jwt) -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(principalConverter)))
			.authorizeHttpRequests((auth) -> {
				auth.requestMatchers(HttpMethod.GET, "/api/v1/meta").permitAll();
				auth.requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS).permitAll();
				// WebSocket handshake: the credential is checked at STOMP CONNECT (realtime module)
				auth.requestMatchers(HttpMethod.GET, "/ws", "/ws/**").permitAll();
				auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
				if (props.security().publicApiDocs()) {
					auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
						.permitAll();
				}
				if (props.security().publicMetrics()) {
					auth.requestMatchers("/actuator/prometheus").permitAll();
				}
				auth.requestMatchers("/error").permitAll();
				auth.anyRequest().authenticated();
			});
		return http.build();
	}

	/** Reads {@code Authorization: Bearer}, except on the public sign-in, refresh and logout endpoints. */
	static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
		List<String> publicPaths = List.of(PUBLIC_AUTH_ENDPOINTS);
		return (HttpServletRequest request) -> {
			String path = request.getRequestURI().substring(request.getContextPath().length());
			if (publicPaths.contains(path)) {
				return null;
			}
			return header.resolve(request);
		};
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(SchoolProperties props) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(props.cors().allowedOrigins());
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language", Idempotent.HEADER,
				RequestIds.HEADER));
		cors.setExposedHeaders(List.of(RequestIds.HEADER, Idempotent.REPLAYED_HEADER, HttpHeaders.RETRY_AFTER));
		cors.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}

}
