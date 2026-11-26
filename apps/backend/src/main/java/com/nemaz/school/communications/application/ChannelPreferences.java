package com.nemaz.school.communications.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.communications.SmsOptOuts;
import com.nemaz.school.communications.application.AnnouncementModels.CommunicationChannelsView;
import com.nemaz.school.identity.UserDirectory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opt-outs of the optional announcement channels. Everybody is opted in until they say otherwise; the choice is checked
 * before every email and SMS, urgent or not (urgent announcements bypass quiet hours and push preferences, never these).
 * A number that opted out with the SMS provider ({@link SmsOptOuts}, the STOP keyword) stays blocked whatever the user
 * chooses in the app.
 */
@Service
public class ChannelPreferences implements SmsOptOuts {

	private final JdbcClient jdbc;

	private final UserDirectory users;

	private final Clock clock;

	ChannelPreferences(JdbcClient jdbc, UserDirectory users, Clock clock) {
		this.jdbc = jdbc;
		this.users = users;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public CommunicationChannelsView view(UUID userId) {
		boolean emailOut = this.jdbc.sql("select coalesce((select email_opt_out from communication_preferences where user_id = :u), false)")
			.param("u", userId)
			.query(Boolean.class)
			.single();
		boolean smsOut = this.jdbc.sql("select coalesce((select sms_opt_out from communication_preferences where user_id = :u), false)")
			.param("u", userId)
			.query(Boolean.class)
			.single();
		String phone = this.users.verifiedContacts(userId).map(UserDirectory.VerifiedContacts::phoneE164).orElse(null);
		boolean provider = phone != null && providerOptOut(phone);
		return new CommunicationChannelsView(!emailOut, !smsOut && !provider, provider);
	}

	@Transactional
	public CommunicationChannelsView change(UUID userId, Boolean email, Boolean sms) {
		if (Boolean.TRUE.equals(sms)) {
			String phone = this.users.verifiedContacts(userId).map(UserDirectory.VerifiedContacts::phoneE164).orElse(null);
			if (phone != null && providerOptOut(phone)) {
				throw ApiException.conflict("announcement.sms_blocked_by_provider");
			}
		}
		this.jdbc.sql("""
				insert into communication_preferences (user_id, email_opt_out, sms_opt_out, updated_at)
				values (:u, coalesce(not cast(:email as boolean), false), coalesce(not cast(:sms as boolean), false), :now)
				on conflict (user_id) do update set
				  email_opt_out = coalesce(not cast(:email as boolean), communication_preferences.email_opt_out),
				  sms_opt_out = coalesce(not cast(:sms as boolean), communication_preferences.sms_opt_out),
				  updated_at = :now""")
			.param("u", userId)
			.param("email", email, java.sql.Types.BOOLEAN)
			.param("sms", sms, java.sql.Types.BOOLEAN)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return view(userId);
	}

	/** Users who switched the channel off. */
	Set<UUID> emailOptOuts(Collection<UUID> userIds) {
		return optOuts(userIds, "email_opt_out");
	}

	Set<UUID> smsOptOuts(Collection<UUID> userIds) {
		return optOuts(userIds, "sms_opt_out");
	}

	private Set<UUID> optOuts(Collection<UUID> userIds, String column) {
		if (userIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(this.jdbc.sql("select user_id from communication_preferences where " + column + " and user_id in (:ids)")
			.param("ids", userIds)
			.query(UUID.class)
			.list());
	}

	/** The numbers (of those given) the provider reported as opted out. */
	Set<String> blockedNumbers(Collection<String> phones) {
		if (phones.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(this.jdbc.sql("select phone_e164 from sms_opt_outs where phone_e164 in (:p)")
			.param("p", phones)
			.query(String.class)
			.list());
	}

	private boolean providerOptOut(String phone) {
		return isOptedOut(phone);
	}

	@Override
	@Transactional
	public void optOut(String phoneE164) {
		this.jdbc.sql("insert into sms_opt_outs (phone_e164, source) values (:p, 'PROVIDER') on conflict do nothing")
			.param("p", phoneE164)
			.update();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isOptedOut(String phoneE164) {
		return this.jdbc.sql("select exists (select 1 from sms_opt_outs where phone_e164 = :p)")
			.param("p", phoneE164)
			.query(Boolean.class)
			.single();
	}

}
