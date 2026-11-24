package com.nemaz.school.identity.infrastructure;

import java.text.ParseException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.nemaz.school.identity.application.IdentityProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signing and verification keys of the access tokens (ES256 on P-256), from
 * {@code school.auth.jwt.keys} (a JWK Set; the first key signs, all verify). Without keys the
 * application does not start, unless {@code school.auth.jwt.allow-ephemeral-key} is set
 * (local/test), which generates a key per process.
 *
 * <p>
 * The decoder accepts only ES256 tokens of type {@code at+jwt} signed by one of the keys, and
 * checks issuer, audience, expiry and not-before (with the configured clock skew), plus the
 * presence of {@code sub}, {@code sid} and {@code exp}.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

	static final String TOKEN_TYPE = "at+jwt";

	private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

	@Bean
	AccessTokenKeys accessTokenKeys(IdentityProperties props) {
		IdentityProperties.Jwt jwt = props.jwt();
		requireText(jwt.issuer(), "school.auth.jwt.issuer");
		requireText(jwt.audience(), "school.auth.jwt.audience");
		String configured = jwt.keys();
		boolean present = configured != null && !configured.isBlank() && !configured.startsWith("${");
		if (!present) {
			if (!jwt.allowEphemeralKey()) {
				throw new IllegalStateException("No access-token signing key: set school.auth.jwt.keys "
						+ "(SCHOOL_AUTH_JWT_KEYS, a JWK Set with an EC P-256 private key)");
			}
			log.warn("Using an ephemeral access-token signing key (school.auth.jwt.allow-ephemeral-key=true): "
					+ "tokens become invalid when the process stops. Never use this in a deployed environment.");
			return new AccessTokenKeys(List.of(generate()));
		}
		return new AccessTokenKeys(parse(configured));
	}

	@Bean
	JwtEncoder jwtEncoder(AccessTokenKeys keys) {
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(List.copyOf(keys.all()))));
	}

	@Bean
	JwtDecoder jwtDecoder(AccessTokenKeys keys, IdentityProperties props, Clock clock) {
		List<JWK> publicKeys = keys.all().stream().map(JWK::toPublicJWK).toList();
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(new JWKSet(publicKeys)))
			.jwsAlgorithm(SignatureAlgorithm.ES256)
			.jwtProcessorCustomizer((processor) -> processor
				.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<SecurityContext>(new JOSEObjectType(TOKEN_TYPE))))
			.build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(props.jwt().clockSkew());
		timestamps.setClock(clock);
		List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
		validators.add(timestamps);
		validators.add(new JwtIssuerValidator(props.jwt().issuer()));
		validators.add(new JwtAudienceValidator(props.jwt().audience()));
		validators.add(new JwtTypeValidator(TOKEN_TYPE));
		validators.add(new JwtClaimValidator<Object>(JwtClaimNames.SUB, Objects::nonNull));
		validators.add(new JwtClaimValidator<Object>(JwtClaimNames.EXP, Objects::nonNull));
		validators.add(new JwtClaimValidator<Object>(AccessTokenKeys.SESSION_CLAIM, Objects::nonNull));
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
		return decoder;
	}

	static List<ECKey> parse(String json) {
		JWKSet set;
		try {
			set = JWKSet.parse(json);
		}
		catch (ParseException ex) {
			throw new IllegalStateException("school.auth.jwt.keys is not a valid JWK Set", ex);
		}
		if (set.getKeys().isEmpty()) {
			throw new IllegalStateException("school.auth.jwt.keys contains no keys");
		}
		List<ECKey> keys = new ArrayList<>();
		for (JWK jwk : set.getKeys()) {
			if (!(jwk instanceof ECKey ec) || !Curve.P_256.equals(ec.getCurve())) {
				throw new IllegalStateException("school.auth.jwt.keys must contain EC P-256 keys only");
			}
			if (ec.getKeyID() == null || ec.getKeyID().isBlank()) {
				throw new IllegalStateException("Every key in school.auth.jwt.keys needs a \"kid\"");
			}
			if (ec.getAlgorithm() != null && !JWSAlgorithm.ES256.equals(ec.getAlgorithm())) {
				throw new IllegalStateException("school.auth.jwt.keys: alg must be ES256");
			}
			keys.add(ec);
		}
		if (!keys.getFirst().isPrivate()) {
			throw new IllegalStateException("The first key of school.auth.jwt.keys signs and needs its private part (\"d\")");
		}
		return keys;
	}

	private static ECKey generate() {
		try {
			return new ECKeyGenerator(Curve.P_256).keyID("ephemeral-" + UUID.randomUUID())
				.algorithm(JWSAlgorithm.ES256)
				.keyUse(KeyUse.SIGNATURE)
				.generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("Could not generate an access-token key", ex);
		}
	}

	private static void requireText(String value, String property) {
		if (value == null || value.isBlank() || value.startsWith("${")) {
			throw new IllegalStateException(property + " must be set");
		}
	}

}
