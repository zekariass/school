package com.nemaz.school.identity.application;

import com.nemaz.school.common.security.Masking;

/** Where a one-time code goes: an E.164 phone (SMS) or a lower-cased email. */
public record Destination(Channel channel, String value) {

	public enum Channel {

		SMS, EMAIL

	}

	public String masked() {
		return (this.channel == Channel.SMS) ? Masking.phone(this.value) : Masking.email(this.value);
	}

}
