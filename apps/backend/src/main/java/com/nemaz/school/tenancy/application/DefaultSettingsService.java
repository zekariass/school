package com.nemaz.school.tenancy.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.tenancy.ConfigSource;
import com.nemaz.school.tenancy.ConfigurationChanged;
import com.nemaz.school.tenancy.ScopeType;
import com.nemaz.school.tenancy.SettingChangeGuard;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingValue;
import com.nemaz.school.tenancy.SettingsService;
import com.nemaz.school.tenancy.application.SchoolConfiguration.SettingState;
import com.nemaz.school.tenancy.domain.Setting;
import com.nemaz.school.tenancy.domain.SettingDefinition;
import com.nemaz.school.tenancy.infrastructure.ScopeSpecifications;
import com.nemaz.school.tenancy.infrastructure.SettingDefinitionRepository;
import com.nemaz.school.tenancy.infrastructure.SettingRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultSettingsService implements SettingsService {

	private final SettingDefinitionRepository definitions;

	private final SettingRepository settings;

	private final SchoolConfigurationCache cache;

	private final SchoolConfigurationLoader loader;

	private final ConfigurationScopes scopes;

	private final ApplicationEventPublisher events;

	private final JsonMapper json;

	private final AuditLog audit;

	private final ObjectProvider<SettingChangeGuard> guards;

	DefaultSettingsService(SettingDefinitionRepository definitions, SettingRepository settings,
			SchoolConfigurationCache cache, SchoolConfigurationLoader loader, ConfigurationScopes scopes,
			ApplicationEventPublisher events, JsonMapper json, AuditLog audit, ObjectProvider<SettingChangeGuard> guards) {
		this.audit = audit;
		this.guards = guards;
		this.definitions = definitions;
		this.settings = settings;
		this.cache = cache;
		this.loader = loader;
		this.scopes = scopes;
		this.events = events;
		this.json = json;
	}

	@Override
	@Transactional(readOnly = true)
	public SettingValue resolve(String key, SettingScope scope) {
		SettingDefinition definition = definition(key);
		SettingState state = null;
		if (scope.type() == ScopeType.SCHOOL) {
			state = this.cache.get(scope.schoolId()).settings().get(key);
		}
		if (state == null) {
			state = this.loader.loadSetting(definition, scope);
		}
		return new SettingValue(key, this.json.readTree(state.valueJson()), state.source());
	}

	@Override
	public boolean getBoolean(String key, SettingScope scope) {
		JsonNode value = resolve(key, scope).value();
		if (!value.isBoolean()) {
			throw new IllegalStateException("Setting " + key + " is not a boolean");
		}
		return value.asBoolean();
	}

	@Override
	public Optional<Integer> getInt(String key, SettingScope scope) {
		JsonNode value = resolve(key, scope).value();
		if (value.isNull()) {
			return Optional.empty();
		}
		if (!value.isIntegralNumber()) {
			throw new IllegalStateException("Setting " + key + " is not an integer");
		}
		return Optional.of(value.asInt());
	}

	@Override
	public Optional<String> getString(String key, SettingScope scope) {
		JsonNode value = resolve(key, scope).value();
		if (value.isNull()) {
			return Optional.empty();
		}
		if (!value.isString()) {
			throw new IllegalStateException("Setting " + key + " is not a string");
		}
		return Optional.of(value.asString());
	}

	@Override
	public List<String> getStringList(String key, SettingScope scope) {
		JsonNode value = resolve(key, scope).value();
		if (value.isNull()) {
			return List.of();
		}
		if (!value.isArray()) {
			throw new IllegalStateException("Setting " + key + " is not a list");
		}
		List<String> items = new ArrayList<>();
		value.forEach((item) -> items.add(item.asString()));
		return List.copyOf(items);
	}

	@Override
	public <T> Optional<T> getObject(String key, SettingScope scope, Class<T> type) {
		JsonNode value = resolve(key, scope).value();
		return value.isNull() ? Optional.empty() : Optional.of(this.json.treeToValue(value, type));
	}

	@Override
	@Transactional
	public SettingValue set(String key, SettingScope scope, JsonNode value, UUID actorId) {
		SettingDefinition definition = definition(key);
		if (!definition.allows(scope.type())) {
			throw ApiException.badRequest("setting.scope_not_allowed", scope.type());
		}
		JsonNode checked = (value != null) ? value : this.json.nullNode();
		List<String> errors = ValueSchemaValidator.validate(definition.getValueSchema(), checked);
		if (!errors.isEmpty()) {
			throw ApiException.badRequest("setting.invalid_value", errors.getFirst());
		}
		this.scopes.chain(scope); // the school / organization / country must exist
		runGuards(key, scope, definition, resolve(key, scope).value(), checked, actorId);

		Setting existing = find(key, scope).orElse(null);
		JsonNode before = (existing != null) ? existing.getValue() : null;
		try {
			if (existing == null) {
				this.settings.saveAndFlush(new Setting(key, scope, checked, actorId));
			}
			else {
				existing.change(checked, actorId);
				this.settings.saveAndFlush(existing);
			}
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("config.concurrent_update");
		}
		this.events.publishEvent(ConfigurationChanged.of(scope, ConfigurationChanged.SETTING, key));
		this.audit.record(ConfigurationAudit.entry("setting.set", scope).metadata("key", key).changes(before, checked));
		return new SettingValue(key, checked, ConfigSource.of(scope.type()));
	}

	@Override
	@Transactional
	public void clear(String key, SettingScope scope, UUID actorId) {
		SettingDefinition definition = definition(key);
		List<SettingScope> chain = this.scopes.chain(scope);
		Setting existing = find(key, scope).orElseThrow(() -> ApiException.notFound("config.override_not_found"));
		JsonNode inherited = (chain.size() > 1)
				? this.json.readTree(this.loader.loadSetting(definition, chain.get(1)).valueJson())
				: definition.getDefaultValue();
		runGuards(key, scope, definition, existing.getValue(), inherited, actorId);
		this.settings.delete(existing);
		this.settings.flush();
		this.events.publishEvent(ConfigurationChanged.of(scope, ConfigurationChanged.SETTING, key));
		this.audit.record(ConfigurationAudit.entry("setting.clear", scope)
			.metadata("key", key)
			.changes(existing.getValue(), null));
	}

	/** The registered {@link SettingChangeGuard}s may refuse the change (they throw an ApiException). */
	private void runGuards(String key, SettingScope scope, SettingDefinition definition, JsonNode before, JsonNode after,
			UUID actorId) {
		SettingChangeGuard.Change change = new SettingChangeGuard.Change(key, scope, before, after, actorId);
		this.guards.orderedStream().forEach((guard) -> guard.check(change));
	}

	private Optional<Setting> find(String key, SettingScope scope) {
		return this.settings.findOne(ScopeSpecifications.<Setting>at(scope)
			.and((root, query, cb) -> cb.equal(root.get("key"), key)));
	}

	private SettingDefinition definition(String key) {
		return this.definitions.findById(key).orElseThrow(() -> ApiException.badRequest("setting.unknown_key"));
	}

}
