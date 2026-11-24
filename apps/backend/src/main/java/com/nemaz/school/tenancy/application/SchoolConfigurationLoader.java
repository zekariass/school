package com.nemaz.school.tenancy.application;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.nemaz.school.subscriptions.Entitlement;
import com.nemaz.school.subscriptions.SchoolEntitlements;
import com.nemaz.school.subscriptions.SubscriptionService;
import com.nemaz.school.tenancy.ConfigSource;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.application.SchoolConfiguration.FeatureState;
import com.nemaz.school.tenancy.application.SchoolConfiguration.SettingState;
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
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes a school's configuration from the database: the single implementation of the
 * R-33 precedence rules. Always goes to the database; {@link SchoolConfigurationCache}
 * puts Redis in front of it.
 *
 * <ul>
 * <li>Flag: most specific override on school, organization, country, platform, else the definition default.</li>
 * <li>Entitlement: the school's live plan (SCHOOL_FREE when it has none) must list the feature as enabled.</li>
 * <li>Available = flag AND entitled.</li>
 * <li>Setting: most specific override, else the definition default.</li>
 * </ul>
 */
@Component
class SchoolConfigurationLoader {

	private final ConfigurationScopes scopes;

	private final SettingDefinitionRepository settingDefinitions;

	private final SettingRepository settings;

	private final FeatureDefinitionRepository featureDefinitions;

	private final FeatureOverrideRepository featureOverrides;

	private final SubscriptionService subscriptions;

	private final JsonMapper json;

	SchoolConfigurationLoader(ConfigurationScopes scopes, SettingDefinitionRepository settingDefinitions,
			SettingRepository settings, FeatureDefinitionRepository featureDefinitions,
			FeatureOverrideRepository featureOverrides, SubscriptionService subscriptions, JsonMapper json) {
		this.scopes = scopes;
		this.settingDefinitions = settingDefinitions;
		this.settings = settings;
		this.featureDefinitions = featureDefinitions;
		this.featureOverrides = featureOverrides;
		this.subscriptions = subscriptions;
		this.json = json;
	}

	@Transactional(readOnly = true)
	SchoolConfiguration load(UUID schoolId) {
		List<SettingScope> chain = this.scopes.chainOfSchool(schoolId);
		SchoolEntitlements entitlements = this.subscriptions.entitlementsFor(schoolId);

		Map<String, FeatureState> features = new LinkedHashMap<>();
		Map<String, FeatureOverride> bestOverride = mostSpecific(chain,
				this.featureOverrides.findAll(ScopeSpecifications.atAny(chain)), FeatureOverride::getFeatureKey,
				FeatureOverride::getScopeType);
		for (FeatureDefinition definition : sorted(this.featureDefinitions.findAll(), FeatureDefinition::getKey)) {
			FeatureOverride override = bestOverride.get(definition.getKey());
			boolean flag = (override != null) ? override.isEnabled() : definition.isDefaultEnabled();
			ConfigSource source = (override != null) ? ConfigSource.of(override.getScopeType()) : ConfigSource.DEFAULT;
			Entitlement entitlement = entitlements.of(definition.getKey());
			features.put(definition.getKey(),
					new FeatureState(definition.getKey(), flag && entitlement.enabled(), flag, source,
							entitlement.enabled(), entitlement.limit(),
							(override != null && override.getConfig() != null) ? override.getConfig().toString()
									: null));
		}

		Map<String, SettingState> values = new LinkedHashMap<>();
		Map<String, Setting> bestSetting = mostSpecific(chain, this.settings.findAll(ScopeSpecifications.atAny(chain)),
				Setting::getKey, Setting::getScopeType);
		for (SettingDefinition definition : sorted(this.settingDefinitions.findAll(), SettingDefinition::getKey)) {
			Setting override = bestSetting.get(definition.getKey());
			JsonNode value = (override != null) ? override.getValue() : definition.getDefaultValue();
			values.put(definition.getKey(),
					new SettingState(definition.getKey(), definition.getModule(), text(value),
							(override != null) ? ConfigSource.of(override.getScopeType()) : ConfigSource.DEFAULT));
		}
		return new SchoolConfiguration(schoolId, entitlements.planCode(), entitlements.freeFallback(), features,
				values);
	}

	/** Effective setting value at any scope (organization, country, platform), uncached. */
	@Transactional(readOnly = true)
	SettingState loadSetting(SettingDefinition definition, SettingScope scope) {
		List<SettingScope> chain = this.scopes.chain(scope);
		Map<String, Setting> best = mostSpecific(chain, this.settings.findAll(ScopeSpecifications.<Setting>atAny(chain)
			.and((root, query, cb) -> cb.equal(root.get("key"), definition.getKey()))), Setting::getKey,
				Setting::getScopeType);
		Setting override = best.get(definition.getKey());
		JsonNode value = (override != null) ? override.getValue() : definition.getDefaultValue();
		return new SettingState(definition.getKey(), definition.getModule(), text(value),
				(override != null) ? ConfigSource.of(override.getScopeType()) : ConfigSource.DEFAULT);
	}

	/** For each key the row at the most specific scope of the chain (the chain is ordered most specific first). */
	private static <T> Map<String, T> mostSpecific(List<SettingScope> chain, List<T> rows,
			java.util.function.Function<T, String> keyOf,
			java.util.function.Function<T, com.nemaz.school.tenancy.ScopeType> typeOf) {
		Map<com.nemaz.school.tenancy.ScopeType, Integer> rank = new HashMap<>();
		for (int i = 0; i < chain.size(); i++) {
			rank.put(chain.get(i).type(), i);
		}
		Map<String, T> best = new HashMap<>();
		for (T row : rows) {
			T current = best.get(keyOf.apply(row));
			if (current == null || rank.get(typeOf.apply(row)) < rank.get(typeOf.apply(current))) {
				best.put(keyOf.apply(row), row);
			}
		}
		return best;
	}

	private static <T> List<T> sorted(List<T> items, java.util.function.Function<T, String> keyOf) {
		Map<String, T> byKey = new TreeMap<>();
		items.forEach((item) -> byKey.put(keyOf.apply(item), item));
		return List.copyOf(byKey.values());
	}

	private String text(JsonNode value) {
		return this.json.writeValueAsString((value != null) ? value : this.json.nullNode());
	}

}
