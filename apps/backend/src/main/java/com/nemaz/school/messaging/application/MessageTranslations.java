package com.nemaz.school.messaging.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.feed.TranslationProvider;
import com.nemaz.school.feed.TranslationProvider.TranslationException;
import com.nemaz.school.feed.TranslationProvider.TranslationRequest;
import com.nemaz.school.messaging.application.MessagingModels.MessageItemView;
import com.nemaz.school.messaging.application.MessagingModels.MessageTranslationView;
import com.nemaz.school.messaging.application.MessagingPolicy.Access;
import com.nemaz.school.messaging.infrastructure.MessagingSettings;
import com.nemaz.school.system.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "See translation" of a message (rule book R11.4, decision D5), reusing the feed's {@link TranslationProvider} adapter and
 * the {@code content_translations} cache. <b>What may leave the platform:</b>
 * <ul>
 * <li>nothing unless the school switched {@code messaging.translation.enabled} on (default off) and a provider is available;</li>
 * <li>never the messages of a DIRECT conversation between an adult and a student, whatever the setting;</li>
 * <li>only the text of a message the caller may see now (SENT or EDITED), never ids, names of senders, media or the caller;
 * the names of the conversation's students, e-mail addresses and phone-like numbers are replaced by placeholders and put back
 * locally; 60 provider calls an hour per user, cached results are free; the result is always labelled machine translated.</li>
 * </ul>
 */
@Service
public class MessageTranslations {

	private static final Logger log = LoggerFactory.getLogger(MessageTranslations.class);

	static final int CALLS_PER_HOUR = 60;

	private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");

	private static final Pattern DIGITS = Pattern.compile("\\+?\\d[\\d\\s().-]{5,}\\d");

	private final JdbcClient jdbc;

	private final MessagingPolicy policy;

	private final MessageQueries queries;

	private final MessagingSettings settings;

	private final List<TranslationProvider> providers;

	private final RateLimiter limiter;

	private final Clock clock;

	MessageTranslations(JdbcClient jdbc, MessagingPolicy policy, MessageQueries queries, MessagingSettings settings,
			List<TranslationProvider> providers, RateLimiter limiter, Clock clock) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.queries = queries;
		this.settings = settings;
		this.providers = providers;
		this.limiter = limiter;
		this.clock = clock;
	}

	@Transactional
	public MessageTranslationView translate(CurrentUser user, UUID conversationId, UUID messageId, String requested) {
		Access access = this.policy.access(user, conversationId);
		if (!this.settings.translation(access.conversation().schoolId())) {
			throw ApiException.forbidden("messaging.translation_disabled");
		}
		if (access.conversation().safeguarded()) {
			throw ApiException.forbidden("messaging.translation_not_allowed");
		}
		MessageItemView message = this.queries.view(user, access, messageId);
		if (message.body() == null || message.body().isBlank()
				|| !("SENT".equals(message.status()) || "EDITED".equals(message.status()))) {
			throw ApiException.badRequest("messaging.translation_empty");
		}
		String target = targetLanguage(user, requested);
		String hash = sha256(message.body());
		MessageTranslationView cached = cached(messageId, target, hash);
		if (cached != null) {
			return cached;
		}
		if (target.equals(message.bodyLanguageCode())) {
			return new MessageTranslationView(message.body(), message.bodyLanguageCode(), target, false, true);
		}
		TranslationProvider provider = this.providers.stream().filter(TranslationProvider::available).findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "messaging.translation_unavailable"));
		this.limiter.consume("messaging-translate-user", user.userId().toString(), CALLS_PER_HOUR, Duration.ofHours(1),
				"messaging.translation_rate_limited");
		Masked masked = mask(message.body(), namesOfStudents(conversationId));
		String translated;
		try {
			translated = provider.translate(new TranslationRequest(masked.text(), message.bodyLanguageCode(), target));
		}
		catch (TranslationException | RuntimeException ex) {
			log.warn("Translation provider {} failed: {}", provider.name(), ex.getClass().getSimpleName());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "messaging.translation_unavailable");
		}
		String result = masked.restore(translated);
		this.jdbc.sql("""
				insert into content_translations (id, source_type, source_id, source_field, source_hash, source_language_code,
				                                  target_language_code, translated_text, is_machine, provider, created_at)
				values (:id, 'MESSAGE', :source, 'body', :hash, :src, :target, :text, true, :provider, :now)
				on conflict (source_type, source_id, source_field, target_language_code, source_hash) do nothing""")
			.param("id", Ids.newId())
			.param("source", messageId)
			.param("hash", hash)
			.param("src", message.bodyLanguageCode())
			.param("target", target)
			.param("text", result)
			.param("provider", provider.name())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return new MessageTranslationView(result, message.bodyLanguageCode(), target, true, false);
	}

	private MessageTranslationView cached(UUID messageId, String target, String hash) {
		return this.jdbc.sql("""
				select translated_text, source_language_code from content_translations
				where source_type = 'MESSAGE' and source_id = :source and source_field = 'body' and target_language_code = :target
				  and source_hash = :hash""")
			.param("source", messageId)
			.param("target", target)
			.param("hash", hash)
			.query((rs, i) -> new MessageTranslationView(rs.getString("translated_text"), rs.getString("source_language_code"),
					target, true, true))
			.optional()
			.orElse(null);
	}

	private String targetLanguage(CurrentUser user, String requested) {
		String target = (requested != null && !requested.isBlank()) ? requested.strip().toLowerCase(Locale.ROOT)
				: ((user.locale() != null) ? user.locale().getLanguage() : "en");
		boolean known = this.jdbc.sql("select exists (select 1 from languages where code = :c)")
			.param("c", target)
			.query(Boolean.class)
			.single();
		if (!known) {
			throw ApiException.badRequest("messaging.translation_language_invalid");
		}
		return target;
	}

	/** Every name token of the students of the conversation (members and the child it is about). */
	private Set<String> namesOfStudents(UUID conversationId) {
		Set<String> tokens = new TreeSet<>(java.util.Comparator.comparing(String::length).reversed().thenComparing(String::compareTo));
		this.jdbc.sql("""
				select u.given_name, u.middle_name, u.family_name, u.display_name from users u
				where u.id in (select m.user_id from conversation_members m where m.conversation_id = :c and m.acting_as = 'STUDENT'
				               union select c.context_student_id from conversations c where c.id = :c and c.context_student_id is not null)""")
			.param("c", conversationId)
			.query((rs, i) -> {
				for (String column : List.of("given_name", "middle_name", "family_name", "display_name")) {
					String value = rs.getString(column);
					if (value != null) {
						tokens.add(value.strip());
						for (String part : value.split("\\s+")) {
							if (part.length() >= 2) {
								tokens.add(part);
							}
						}
					}
				}
				return 0;
			})
			.list();
		tokens.removeIf((t) -> t.length() < 2);
		return tokens;
	}

	static Masked mask(String text, Set<String> names) {
		List<String> originals = new ArrayList<>();
		String masked = text;
		// e-mail addresses and numbers first (an address contains a name), then the students' names
		masked = replaceAll(masked, EMAIL, originals);
		masked = replaceAll(masked, DIGITS, originals);
		for (String name : names) {
			Pattern pattern = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])",
					Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
			masked = replaceAll(masked, pattern, originals);
		}
		return new Masked(masked, originals);
	}

	private static String replaceAll(String text, Pattern pattern, List<String> originals) {
		Matcher matcher = pattern.matcher(text);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			originals.add(matcher.group());
			matcher.appendReplacement(out, Matcher.quoteReplacement("[[" + (originals.size() - 1) + "]]"));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	record Masked(String text, List<String> originals) {

		String restore(String translated) {
			String restored = translated;
			for (int i = 0; i < this.originals.size(); i++) {
				restored = restored.replace("[[" + i + "]]", this.originals.get(i));
			}
			return restored;
		}

	}

	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
