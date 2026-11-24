package com.nemaz.school.identity.infrastructure;

import java.util.List;

import com.nimbusds.jose.jwk.ECKey;

/** The configured access-token keys: the first signs, all verify. Never log these. */
public record AccessTokenKeys(List<ECKey> all) {

	/** Claim carrying the session (refresh-token family) id. */
	public static final String SESSION_CLAIM = "sid";

	public AccessTokenKeys {
		all = List.copyOf(all);
	}

	public String signingKeyId() {
		return this.all.getFirst().getKeyID();
	}

	@Override
	public String toString() {
		return "AccessTokenKeys[" + this.all.size() + " keys, signing kid=" + signingKeyId() + "]";
	}

}
