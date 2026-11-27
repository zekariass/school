package com.nemaz.school.attendance.application;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code school.attendance.secrets.*} (M7e): the server secrets of the QR check-in.
 * <ul>
 * <li>{@code keys} maps a key id (letters, digits, {@code _}, {@code -}; lower case from the environment:
 * {@code SCHOOL_ATTENDANCE_SECRETS_KEYS_K1}) to a base64 HMAC key (32 random bytes): the pepper of the stored
 * credential hashes. {@code activeKeyId} is the key new credentials are hashed with; every listed key still verifies
 * (rotation overlap, see docs/ops/Runbook.md).</li>
 * <li>{@code qrKey} (base64, 32 random bytes) is the server key of the rotating codes: {@code HMAC(qrKey, session | window)}.</li>
 * </ul>
 * Nothing is configured by default: a deployed profile refuses to start without valid values
 * ({@code DeploymentConfigValidator}); elsewhere the QR endpoints fail closed (503 {@code attendance.secrets_unavailable}).
 * The development values live in {@code application-local.yaml} and the test profile only. The values are secrets: never
 * logged, never put in an audit row or an event ({@code toString} is redacted).
 */
@ConfigurationProperties("school.attendance.secrets")
public record AttendanceSecretsProperties(String activeKeyId, @DefaultValue Map<String, String> keys, String qrKey) {

	@Override
	public String toString() {
		return "AttendanceSecretsProperties[redacted]";
	}

}
