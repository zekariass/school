package com.nemaz.school.system.security;

import java.util.List;

import com.nemaz.school.system.SchoolProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Baseline API security: stateless, deny-by-default, problem-details errors.
 * Token authentication (JWT resource server) is added in Implementation Plan M1; until
 * then every non-public endpoint answers 401.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, ProblemSecurityHandlers handlers, SchoolProperties props)
			throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.exceptionHandling((ex) -> ex.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
			.authorizeHttpRequests((auth) -> {
				auth.requestMatchers(HttpMethod.GET, "/api/v1/meta").permitAll();
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

	@Bean
	CorsConfigurationSource corsConfigurationSource(SchoolProperties props) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(props.cors().allowedOrigins());
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language", "Idempotency-Key",
				"X-Request-Id"));
		cors.setExposedHeaders(List.of("X-Request-Id"));
		cors.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}

}
