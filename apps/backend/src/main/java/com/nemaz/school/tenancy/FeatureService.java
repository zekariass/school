package com.nemaz.school.tenancy;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * Feature availability (R-33): a feature is on for a school only if the most specific
 * feature override (school, organization, country, platform, definition default) enables it
 * and the school's subscription plan entitles it. A school without a live subscription gets
 * the SCHOOL_FREE entitlements. Results are cached and evicted on change.
 */
public interface FeatureService {

	boolean isEnabled(String featureKey, UUID schoolId);

	/** The plan's numeric limit for the feature (max students, storage bytes, ...); empty = unlimited. */
	OptionalLong limit(String featureKey, UUID schoolId);

}
