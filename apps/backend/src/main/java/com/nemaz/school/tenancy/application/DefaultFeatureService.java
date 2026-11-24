package com.nemaz.school.tenancy.application;

import java.util.OptionalLong;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.tenancy.FeatureService;
import com.nemaz.school.tenancy.application.SchoolConfiguration.FeatureState;

import org.springframework.stereotype.Service;

@Service
class DefaultFeatureService implements FeatureService {

	private final SchoolConfigurationCache cache;

	DefaultFeatureService(SchoolConfigurationCache cache) {
		this.cache = cache;
	}

	@Override
	public boolean isEnabled(String featureKey, UUID schoolId) {
		return state(featureKey, schoolId).enabled();
	}

	@Override
	public OptionalLong limit(String featureKey, UUID schoolId) {
		Long limit = state(featureKey, schoolId).limit();
		return (limit != null) ? OptionalLong.of(limit) : OptionalLong.empty();
	}

	private FeatureState state(String featureKey, UUID schoolId) {
		FeatureState state = this.cache.get(schoolId).features().get(featureKey);
		if (state == null) {
			throw ApiException.badRequest("feature.unknown_key");
		}
		return state;
	}

}
