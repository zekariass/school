package com.nemaz.school.media.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaPurposePolicy;

import org.springframework.stereotype.Component;

/** The policies the owning modules registered, one per purpose. A purpose without one is not usable yet. */
@Component
class PurposePolicies {

	private final Map<MediaPurpose, MediaPurposePolicy> policies = new EnumMap<>(MediaPurpose.class);

	PurposePolicies(List<MediaPurposePolicy> registered) {
		for (MediaPurposePolicy policy : registered) {
			if (this.policies.put(policy.purpose(), policy) != null) {
				throw new IllegalStateException("Two media policies for purpose " + policy.purpose());
			}
		}
	}

	Optional<MediaPurposePolicy> find(MediaPurpose purpose) {
		return Optional.ofNullable(this.policies.get(purpose));
	}

	/** The purpose's policy, or 403 {@code media.purpose_not_supported}. */
	MediaPurposePolicy require(MediaPurpose purpose) {
		return find(purpose).orElseThrow(() -> ApiException.forbidden("media.purpose_not_supported"));
	}

	Set<MediaPurpose> supported() {
		return this.policies.keySet();
	}

}
