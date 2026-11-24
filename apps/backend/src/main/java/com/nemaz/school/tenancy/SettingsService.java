package com.nemaz.school.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Behaviour settings (R-33). The effective value of a key at a scope is the most specific
 * override on the chain school, organization, country, platform, falling back to the
 * definition's default. School-level reads are cached (Redis) and evicted when an override
 * or the school's subscription changes.
 *
 * <p>
 * Unknown keys fail with {@code setting.unknown_key}. Typed accessors fail with
 * {@link IllegalStateException} when the stored value has another type; writes are
 * validated against the definition so that cannot happen for values written through
 * {@link #set}.
 */
public interface SettingsService {

	SettingValue resolve(String key, SettingScope scope);

	/** The effective value as a boolean; a null value is an error (use a boolean setting). */
	boolean getBoolean(String key, SettingScope scope);

	/** The effective integer, empty when the value is null (e.g. {@code messaging.student.min_age}). */
	Optional<Integer> getInt(String key, SettingScope scope);

	Optional<String> getString(String key, SettingScope scope);

	List<String> getStringList(String key, SettingScope scope);

	/** The effective JSON object (or other value) converted to {@code type}; empty when null. */
	<T> Optional<T> getObject(String key, SettingScope scope, Class<T> type);

	/**
	 * Sets (or replaces) the override of {@code key} at exactly {@code scope}. Validates that
	 * the key exists, the scope is allowed for it, and the value satisfies the definition's
	 * schema. Publishes {@link ConfigurationChanged}.
	 */
	SettingValue set(String key, SettingScope scope, JsonNode value, UUID actorId);

	/** Removes the override at exactly {@code scope}, so the next level up applies again. */
	void clear(String key, SettingScope scope, UUID actorId);

}
