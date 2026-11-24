package com.nemaz.school.tenancy.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.tenancy.ConfigurationChanged;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.domain.FeatureDefinition;
import com.nemaz.school.tenancy.domain.FeatureOverride;
import com.nemaz.school.tenancy.domain.Setting;
import com.nemaz.school.tenancy.domain.SettingDefinition;
import com.nemaz.school.tenancy.infrastructure.FeatureDefinitionRepository;
import com.nemaz.school.tenancy.infrastructure.FeatureOverrideRepository;
import com.nemaz.school.tenancy.infrastructure.ScopeSpecifications;
import com.nemaz.school.tenancy.infrastructure.SettingDefinitionRepository;
import com.nemaz.school.tenancy.infrastructure.SettingRepository;
import tools.jackson.databind.JsonNode;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the platform-admin console needs beyond {@link com.nemaz.school.tenancy.SettingsService}:
 * listing overrides of a scope, feature override writes, and a school's resolved view.
 */
@Service
public class PlatformConfigurationService {

	private final SettingRepository settings;

	private final FeatureOverrideRepository featureOverrides;

	private final FeatureDefinitionRepository featureDefinitions;

	private final SettingDefinitionRepository settingDefinitions;

	private final ConfigurationScopes scopes;

	private final SchoolConfigurationCache cache;

	private final ApplicationEventPublisher events;

	private final AuditLog audit;

	PlatformConfigurationService(SettingRepository settings, FeatureOverrideRepository featureOverrides,
			FeatureDefinitionRepository featureDefinitions, SettingDefinitionRepository settingDefinitions,
			ConfigurationScopes scopes, SchoolConfigurationCache cache,
			ApplicationEventPublisher events, AuditLog audit) {
		this.audit = audit;
		this.settings = settings;
		this.featureOverrides = featureOverrides;
		this.featureDefinitions = featureDefinitions;
		this.settingDefinitions = settingDefinitions;
		this.scopes = scopes;
		this.cache = cache;
		this.events = events;
	}

	/** Every feature definition, by key (bounded: code-owned). */
	@Transactional(readOnly = true)
	public List<FeatureDefinition> featureDefinitions() {
		return this.featureDefinitions.findAll().stream().sorted(Comparator.comparing(
				FeatureDefinition::getKey)).toList();
	}

	/** Every setting definition, by module and key (bounded: code-owned). */
	@Transactional(readOnly = true)
	public List<SettingDefinition> settingDefinitions() {
		return this.settingDefinitions.findAll().stream().sorted(Comparator
			.comparing(SettingDefinition::getModule)
			.thenComparing(SettingDefinition::getKey)).toList();
	}

	/** Setting overrides at exactly {@code scope}, optionally for one key. Bounded by the number of definitions. */
	@Transactional(readOnly = true)
	public List<SettingOverride> settingOverrides(SettingScope scope, String key) {
		this.scopes.chain(scope);
		return this.settings.findAll(ScopeSpecifications.<Setting>at(scope).and((root, query, cb) -> (key == null)
				? cb.conjunction() : cb.equal(root.get("key"), key)))
			.stream()
			.sorted(Comparator.comparing(Setting::getKey))
			.map((s) -> new SettingOverride(s.getKey(), s.scope(), s.getValue(), s.getUpdatedBy(), s.getUpdatedAt()))
			.toList();
	}

	@Transactional(readOnly = true)
	public List<FeatureOverrideView> featureOverrides(SettingScope scope) {
		this.scopes.chain(scope);
		return this.featureOverrides.findAll(ScopeSpecifications.<FeatureOverride>at(scope))
			.stream()
			.sorted(Comparator.comparing(FeatureOverride::getFeatureKey))
			.map(PlatformConfigurationService::view)
			.toList();
	}

	@Transactional
	public FeatureOverrideView setFeatureOverride(String featureKey, SettingScope scope, boolean enabled,
			JsonNode config, UUID actorId) {
		if (!this.featureDefinitions.existsById(featureKey)) {
			throw ApiException.badRequest("feature.unknown_key");
		}
		if (config != null && !config.isObject()) {
			throw ApiException.badRequest("feature.invalid_config");
		}
		this.scopes.chain(scope);
		FeatureOverride saved;
		FeatureOverride existing = find(featureKey, scope);
		Boolean before = (existing != null) ? existing.isEnabled() : null;
		try {
			if (existing == null) {
				saved = this.featureOverrides.saveAndFlush(new FeatureOverride(featureKey, scope, enabled, config, actorId));
			}
			else {
				existing.change(enabled, config, actorId);
				saved = this.featureOverrides.saveAndFlush(existing);
			}
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("config.concurrent_update");
		}
		this.events.publishEvent(ConfigurationChanged.of(scope, ConfigurationChanged.FEATURE, featureKey));
		this.audit.record(ConfigurationAudit.entry("feature_override.set", scope)
			.metadata("featureKey", featureKey)
			.changes(java.util.Collections.singletonMap("enabled", before), java.util.Map.of("enabled", enabled)));
		return view(saved);
	}

	@Transactional
	public void clearFeatureOverride(String featureKey, SettingScope scope, UUID actorId) {
		if (!this.featureDefinitions.existsById(featureKey)) {
			throw ApiException.badRequest("feature.unknown_key");
		}
		this.scopes.chain(scope);
		FeatureOverride existing = find(featureKey, scope);
		if (existing == null) {
			throw ApiException.notFound("config.override_not_found");
		}
		this.featureOverrides.delete(existing);
		this.featureOverrides.flush();
		this.events.publishEvent(ConfigurationChanged.of(scope, ConfigurationChanged.FEATURE, featureKey));
		this.audit.record(ConfigurationAudit.entry("feature_override.clear", scope)
			.metadata("featureKey", featureKey)
			.changes(java.util.Map.of("enabled", existing.isEnabled()), null));
	}

	/** The resolved features and settings of a school, exactly as the runtime sees them (cached). */
	public SchoolConfiguration schoolConfiguration(UUID schoolId) {
		return this.cache.get(schoolId);
	}

	private FeatureOverride find(String featureKey, SettingScope scope) {
		return this.featureOverrides.findOne(ScopeSpecifications.<FeatureOverride>at(scope)
			.and((root, query, cb) -> cb.equal(root.get("featureKey"), featureKey))).orElse(null);
	}

	private static FeatureOverrideView view(FeatureOverride o) {
		return new FeatureOverrideView(o.getFeatureKey(), o.scope(), o.isEnabled(), o.getConfig(), o.getUpdatedBy(),
				o.getUpdatedAt());
	}

	public record SettingOverride(String key, SettingScope scope, JsonNode value, UUID updatedBy, Instant updatedAt) {
	}

	public record FeatureOverrideView(String featureKey, SettingScope scope, boolean enabled, JsonNode config,
			UUID updatedBy, Instant updatedAt) {
	}

}
