package com.nemaz.school.tenancy.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.subscriptions.PlanInfo;
import com.nemaz.school.subscriptions.SubscriptionService;
import com.nemaz.school.tenancy.ConfigSource;
import com.nemaz.school.tenancy.ScopeType;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingValue;
import com.nemaz.school.tenancy.SettingsService;
import com.nemaz.school.tenancy.application.PlatformConfigurationService;
import com.nemaz.school.tenancy.application.PlatformConfigurationService.FeatureOverrideView;
import com.nemaz.school.tenancy.application.PlatformConfigurationService.SettingOverride;
import com.nemaz.school.tenancy.application.SchoolConfiguration;
import com.nemaz.school.tenancy.application.SchoolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration: feature overrides, setting overrides at any scope, and a school's resolved
 * ("effective") configuration. Override lists are bounded by the number of definitions, so
 * they are not paginated. Scope-aware:
 * <ul>
 * <li>feature overrides: {@code platform.features.manage} at a scope covering the override's
 * scope (platform administrators);</li>
 * <li>settings: {@code platform.settings.manage} covering the scope (platform or country
 * administrators), or {@code school.settings.manage} for a school-level setting (school and
 * organization administrators);</li>
 * <li>a school's resolved configuration: anyone who can read the school or change part of
 * its configuration.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "platform-configuration")
public class PlatformConfigurationController {

	private static final String FEATURES = "'" + Permissions.PLATFORM_FEATURES_MANAGE + "'";

	private static final String ANY_SETTINGS = "@authz.anywhere('" + Permissions.PLATFORM_SETTINGS_MANAGE
			+ "') or @authz.anywhere('" + Permissions.SCHOOL_SETTINGS_MANAGE + "')";

	private final PlatformConfigurationService configuration;

	private final SettingsService settings;

	private final SchoolService schools;

	private final JsonMapper json;

	private final AccessControl access;

	private final SubscriptionService subscriptionPlans;

	PlatformConfigurationController(PlatformConfigurationService configuration, SettingsService settings,
			SchoolService schools, JsonMapper json, AccessControl access, SubscriptionService subscriptionPlans) {
		this.subscriptionPlans = subscriptionPlans;
		this.configuration = configuration;
		this.settings = settings;
		this.schools = schools;
		this.json = json;
		this.access = access;
	}

	/** 403 unless the caller holds {@code platform.features.manage} at a scope covering {@code scope}. */
	private void requireFeatures(SettingScope scope) {
		this.access.require(CurrentUser.require(), Permissions.PLATFORM_FEATURES_MANAGE, accessScope(scope));
	}

	/** 403 unless the caller may change settings at exactly {@code scope}. */
	private void requireSettings(SettingScope scope) {
		CurrentUser user = CurrentUser.require();
		AccessScope target = accessScope(scope);
		if (this.access.can(user, Permissions.PLATFORM_SETTINGS_MANAGE, target)) {
			return;
		}
		if (scope.type() == ScopeType.SCHOOL && this.access.can(user, Permissions.SCHOOL_SETTINGS_MANAGE, target)) {
			return;
		}
		throw new org.springframework.security.access.AccessDeniedException("Missing settings permission");
	}

	static AccessScope accessScope(SettingScope scope) {
		return switch (scope.type()) {
			case PLATFORM -> AccessScope.platform();
			case COUNTRY -> AccessScope.country(scope.countryCode());
			case ORGANIZATION -> AccessScope.organization(scope.organizationId());
			case SCHOOL -> AccessScope.school(scope.schoolId());
		};
	}

	// --- catalog: what can be configured --------------------------------------------------

	@GetMapping("/definitions")
	@PreAuthorize("@authz.anywhere(" + FEATURES + ") or " + ANY_SETTINGS)
	@Operation(operationId = "getConfigurationDefinitions",
			summary = "Every feature and setting definition (key, schema, default, allowed scopes), for override editors")
	public DefinitionsResponse definitions() {
		return new DefinitionsResponse(
				this.configuration.featureDefinitions()
					.stream()
					.map((f) -> new FeatureDefinitionResponse(f.getKey(), f.getDescription(), f.isDefaultEnabled()))
					.toList(),
				this.configuration.settingDefinitions()
					.stream()
					.map((d) -> new SettingDefinitionResponse(d.getKey(), d.getModule(), d.getDescription(),
							d.getValueSchema(), d.getDefaultValue(), d.getAllowedScopes()))
					.toList());
	}

	@GetMapping("/plans")
	@PreAuthorize("@authz.anywhere('" + Permissions.PLATFORM_SUBSCRIPTIONS_MANAGE + "')")
	@Operation(operationId = "listSchoolPlans", summary = "The plans a school can be given, with localized names")
	public List<PlanInfo> plans() {
		return this.subscriptionPlans.schoolPlans(LocaleContextHolder.getLocale());
	}

	// --- feature overrides ------------------------------------------------------------

	@GetMapping("/feature-overrides")
	@PreAuthorize("@authz.anywhere(" + FEATURES + ")")
	@Operation(operationId = "listFeatureOverrides", summary = "Feature overrides at exactly one scope")
	public List<FeatureOverrideResponse> listFeatureOverrides(@RequestParam ScopeType scopeType,
			@RequestParam(required = false) String countryCode, @RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false) UUID schoolId) {
		SettingScope scope = new ScopeRef(scopeType, countryCode, organizationId, schoolId).toScope();
		requireFeatures(scope);
		return this.configuration.featureOverrides(scope).stream().map(FeatureOverrideResponse::from).toList();
	}

	@PutMapping("/feature-overrides")
	@PreAuthorize("@authz.anywhere(" + FEATURES + ")")
	@Operation(operationId = "putFeatureOverride", summary = "Enable or disable a feature at a scope")
	public FeatureOverrideResponse putFeatureOverride(@Valid @RequestBody PutFeatureOverrideRequest r) {
		SettingScope scope = r.scope().toScope();
		requireFeatures(scope);
		return FeatureOverrideResponse.from(this.configuration.setFeatureOverride(r.featureKey(), scope, r.enabled(),
				r.config(), CurrentUser.currentId()));
	}

	@DeleteMapping("/feature-overrides")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("@authz.anywhere(" + FEATURES + ")")
	@Operation(operationId = "deleteFeatureOverride", summary = "Remove a feature override so the next level applies")
	public void deleteFeatureOverride(@RequestParam String featureKey, @RequestParam ScopeType scopeType,
			@RequestParam(required = false) String countryCode, @RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false) UUID schoolId) {
		SettingScope scope = new ScopeRef(scopeType, countryCode, organizationId, schoolId).toScope();
		requireFeatures(scope);
		this.configuration.clearFeatureOverride(featureKey, scope, CurrentUser.currentId());
	}

	// --- settings ---------------------------------------------------------------------

	@GetMapping("/settings")
	@PreAuthorize(ANY_SETTINGS)
	@Operation(operationId = "listSettingOverrides", summary = "Setting overrides at exactly one scope, optionally one key")
	public List<SettingOverrideResponse> listSettings(@RequestParam ScopeType scopeType,
			@RequestParam(required = false) String countryCode, @RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false) UUID schoolId, @RequestParam(required = false) String key) {
		SettingScope scope = new ScopeRef(scopeType, countryCode, organizationId, schoolId).toScope();
		requireSettings(scope);
		return this.configuration.settingOverrides(scope, key).stream().map(SettingOverrideResponse::from).toList();
	}

	@PutMapping("/settings")
	@PreAuthorize(ANY_SETTINGS)
	@Operation(operationId = "putSetting",
			summary = "Set a setting at a scope; the value is validated against the definition's schema")
	public SettingOverrideResponse putSetting(@Valid @RequestBody PutSettingRequest r) {
		SettingScope scope = r.scope().toScope();
		requireSettings(scope);
		SettingValue saved = this.settings.set(r.key(), scope, r.value(), CurrentUser.currentId());
		return new SettingOverrideResponse(saved.key(), ScopeRef.of(scope), saved.value(), null, null);
	}

	@DeleteMapping("/settings")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(ANY_SETTINGS)
	@Operation(operationId = "deleteSetting", summary = "Remove a setting override so the next level applies")
	public void deleteSetting(@RequestParam String key, @RequestParam ScopeType scopeType,
			@RequestParam(required = false) String countryCode, @RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false) UUID schoolId) {
		SettingScope scope = new ScopeRef(scopeType, countryCode, organizationId, schoolId).toScope();
		requireSettings(scope);
		this.settings.clear(key, scope, CurrentUser.currentId());
	}

	// --- resolved view ----------------------------------------------------------------

	@GetMapping("/schools/{schoolId}/configuration")
	@PreAuthorize("@authz.anyAt(T(com.nemaz.school.common.security.AccessScope).school(#schoolId), '"
			+ Permissions.SCHOOL_READ + "', '" + Permissions.PLATFORM_FEATURES_MANAGE + "', '"
			+ Permissions.PLATFORM_SETTINGS_MANAGE + "', '" + Permissions.SCHOOL_SETTINGS_MANAGE + "')")
	@Operation(operationId = "getSchoolConfiguration",
			summary = "A school's effective features and settings, with the level each value comes from")
	public SchoolConfigurationResponse schoolConfiguration(@PathVariable UUID schoolId) {
		this.schools.get(schoolId); // 404 for unknown schools
		SchoolConfiguration c = this.configuration.schoolConfiguration(schoolId);
		return new SchoolConfigurationResponse(c.schoolId(), c.planCode(), c.planIsFreeFallback(),
				c.features()
					.values()
					.stream()
					.map((f) -> new FeatureStateResponse(f.key(), f.enabled(), f.flagEnabled(), f.flagSource(),
							f.entitled(), f.limit(), (f.configJson() != null) ? this.json.readTree(f.configJson()) : null))
					.toList(),
				c.settings()
					.values()
					.stream()
					.map((s) -> new SettingStateResponse(s.key(), s.module(), this.json.readTree(s.valueJson()),
							s.source()))
					.toList());
	}

	// --- DTOs -------------------------------------------------------------------------

	public record FeatureDefinitionResponse(@NotNull String key, String description, @NotNull boolean defaultEnabled) {
	}

	public record SettingDefinitionResponse(@NotNull String key, @NotNull String module, String description,
			@NotNull @Schema(description = "JSON Schema subset the value must satisfy") JsonNode valueSchema,
			@NotNull @Schema(description = "Any JSON value") JsonNode defaultValue,
			@NotNull List<String> allowedScopes) {
	}

	public record DefinitionsResponse(@NotNull List<FeatureDefinitionResponse> features,
			@NotNull List<SettingDefinitionResponse> settings) {
	}

	public record PutFeatureOverrideRequest(
			@NotBlank(message = Validation.REQUIRED) @Size(max = 64, message = Validation.TOO_LONG) String featureKey,
			@NotNull(message = Validation.REQUIRED) @Valid ScopeRef scope,
			@NotNull(message = Validation.REQUIRED) Boolean enabled,
			@Schema(description = "Optional feature-specific configuration (a JSON object)") JsonNode config) {
	}

	public record FeatureOverrideResponse(@NotNull String featureKey, @NotNull ScopeRef scope, @NotNull boolean enabled,
			JsonNode config, UUID updatedBy, Instant updatedAt) {

		static FeatureOverrideResponse from(FeatureOverrideView v) {
			return new FeatureOverrideResponse(v.featureKey(), ScopeRef.of(v.scope()), v.enabled(), v.config(),
					v.updatedBy(), v.updatedAt());
		}

	}

	public record PutSettingRequest(
			@NotBlank(message = Validation.REQUIRED) @Size(max = 128, message = Validation.TOO_LONG) String key,
			@NotNull(message = Validation.REQUIRED) @Valid ScopeRef scope,
			@NotNull(message = Validation.REQUIRED) @Schema(description = "Any JSON value valid for the setting") JsonNode value) {
	}

	public record SettingOverrideResponse(@NotNull String key, @NotNull ScopeRef scope,
			@NotNull @Schema(description = "Any JSON value") JsonNode value, UUID updatedBy, Instant updatedAt) {

		static SettingOverrideResponse from(SettingOverride o) {
			return new SettingOverrideResponse(o.key(), ScopeRef.of(o.scope()), o.value(), o.updatedBy(),
					o.updatedAt());
		}

	}

	public record FeatureStateResponse(@NotNull String key,
			@NotNull @Schema(description = "Flag enabled AND entitled by the plan") boolean enabled,
			@NotNull boolean flagEnabled, @NotNull ConfigSource flagSource, @NotNull boolean entitled,
			@Schema(description = "The plan's numeric limit; absent = unlimited") Long limit, JsonNode config) {
	}

	public record SettingStateResponse(@NotNull String key, @NotNull String module,
			@NotNull @Schema(description = "Any JSON value") JsonNode value, @NotNull ConfigSource source) {
	}

	public record SchoolConfigurationResponse(@NotNull UUID schoolId, @NotNull String planCode,
			@NotNull @Schema(description = "True when the school has no live subscription and gets SCHOOL_FREE") boolean planIsFreeFallback,
			@NotNull List<FeatureStateResponse> features, @NotNull List<SettingStateResponse> settings) {
	}

}
