package com.nemaz.school.safeguarding.infrastructure;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code school.safeguarding.encryption.*}: the keys that encrypt the narrative and the notes of safeguarding incidents at the
 * application level. {@code keys} maps a key id (letters, digits, {@code _}, {@code -}; lower case from the environment:
 * {@code SCHOOL_SAFEGUARDING_ENCRYPTION_KEYS_K1}) to a base64 AES-256 key (32 random bytes); {@code activeKeyId} is the key
 * new writes use, every listed key still decrypts (rotation). Nothing is configured by default: a deployed profile
 * refuses to start without a valid key ({@code DeploymentConfigValidator}); elsewhere the module fails closed at the first
 * write or read (503 {@code safeguarding.encryption_unavailable}). The development key lives in
 * {@code application-local.yaml} only; the keys are never part of the repository otherwise.
 */
@ConfigurationProperties("school.safeguarding.encryption")
public record EncryptionProperties(String activeKeyId, @DefaultValue Map<String, String> keys) {
}
