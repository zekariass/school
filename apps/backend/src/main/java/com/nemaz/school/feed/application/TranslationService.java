package com.nemaz.school.feed.application;

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
import com.nemaz.school.feed.application.FeedModels.CommentStatus;
import com.nemaz.school.feed.application.FeedModels.TranslationView;
import com.nemaz.school.feed.application.FeedRows.PostRow;
import com.nemaz.school.system.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * "See translation" for posts and comments (decision D5). Off by default ({@code feed.translation.enabled}, per school)
 * and, without a {@link TranslationProvider} bean, answers 503. What may leave the platform, when a provider is used:
 * <ul>
 * <li>only the text of a <b>published</b> post or a visible comment the caller may see, never drafts, pending
 * contributions, media, tags, ids or the caller's identity;</li>
 * <li>with the names of the post's tagged students (every name token) replaced by placeholders, and e-mail addresses and
 * phone-number-like digit runs replaced too; the names are put back into the cached result locally. Children who are
 * not tagged cannot be recognized in free text: authors are told so in the client, and the school decides whether to
 * enable the feature at all;</li>
 * <li>a request per user is limited to 60 provider calls an hour; a cached translation is free. The cache key includes a
 * hash of the text, so an edited post is translated again.</li>
 * </ul>
 * The result is always labelled machine translated; the original text is never overwritten.
 */
@Service
public class TranslationService {

	private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

	static final int CALLS_PER_HOUR = 60;

	private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");

	private static final Pattern DIGITS = Pattern.compile("\\+?\\d[\\d\\s().-]{5,}\\d");

	private final JdbcClient jdbc;

	private final FeedAccess access;

	private final FeedVisibility visibility;

	private final PostViews views;

	private final List<TranslationProvider> providers;

	private final RateLimiter limiter;

	private final Clock clock;

	TranslationService(JdbcClient jdbc, FeedAccess access, FeedVisibility visibility, PostViews views,
			List<TranslationProvider> providers, RateLimiter limiter, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.visibility = visibility;
		this.views = views;
		this.providers = providers;
		this.limiter = limiter;
		this.clock = clock;
	}

	public TranslationView translatePost(CurrentUser user, UUID schoolId, UUID postId, String target) {
		PostRow post = visiblePublished(user, schoolId, postId);
		return translate(user, post, "POST", postId, post.body(), post.bodyLanguage(), target);
	}

	public TranslationView translateComment(CurrentUser user, UUID schoolId, UUID postId, UUID commentId, String target) {
		PostRow post = visiblePublished(user, schoolId, postId);
		record Comment(String body, String language, String status) {
		}
		Comment comment = this.jdbc.sql("select body, body_language_code, status from post_comments where id = :id and post_id = :post")
			.param("id", commentId)
			.param("post", postId)
			.query((rs, i) -> new Comment(rs.getString("body"), rs.getString("body_language_code"), rs.getString("status")))
			.optional()
			.orElseThrow(() -> ApiException.notFound("feed.comment_not_found"));
		boolean moderator = this.access.moderates(user, post);
		if (!CommentStatus.VISIBLE.name().equals(comment.status()) && !moderator) {
			throw ApiException.notFound("feed.comment_not_found");
		}
		return translate(user, post, "POST_COMMENT", commentId, comment.body(), comment.language(), target);
	}

	private PostRow visiblePublished(CurrentUser user, UUID schoolId, UUID postId) {
		this.access.requireSchool(user, schoolId);
		PostRow post = this.views.find(schoolId, postId);
		if (!post.published() || !this.visibility.canSeePost(user, postId)) {
			throw ApiException.notFound("feed.post_not_found");
		}
		if (!this.access.settings().translation(schoolId)) {
			throw ApiException.forbidden("feed.translation_disabled");
		}
		return post;
	}

	private TranslationView translate(CurrentUser user, PostRow post, String sourceType, UUID sourceId, String text,
			String sourceLanguage, String requestedTarget) {
		String target = targetLanguage(user, requestedTarget);
		if (text == null || text.isBlank()) {
			throw ApiException.badRequest("feed.translation_empty");
		}
		String hash = sha256(text);
		TranslationView cached = cached(sourceType, sourceId, target, hash);
		if (cached != null) {
			return cached;
		}
		if (target.equals(sourceLanguage)) {
			return new TranslationView(text, sourceLanguage, target, false, null, true);
		}
		TranslationProvider provider = this.providers.stream().filter(TranslationProvider::available).findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "feed.translation_unavailable"));
		this.limiter.consume("feed-translate-user", user.userId().toString(), CALLS_PER_HOUR, Duration.ofHours(1),
				"feed.translation_rate_limited");
		Masked masked = mask(text, namesOfTaggedStudents(post.id()));
		String translated;
		try {
			translated = provider.translate(new TranslationRequest(masked.text(), sourceLanguage, target));
		}
		catch (TranslationException | RuntimeException ex) {
			log.warn("Translation provider {} failed: {}", provider.name(), ex.getClass().getSimpleName());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "feed.translation_unavailable");
		}
		String result = masked.restore(translated);
		this.jdbc.sql("""
				insert into content_translations (id, source_type, source_id, source_field, source_hash, source_language_code,
				                                  target_language_code, translated_text, is_machine, provider, created_at)
				values (:id, :type, :source, 'body', :hash, :src, :target, :text, true, :provider, :now)
				on conflict (source_type, source_id, source_field, target_language_code, source_hash) do nothing""")
			.param("id", Ids.newId())
			.param("type", sourceType)
			.param("source", sourceId)
			.param("hash", hash)
			.param("src", sourceLanguage)
			.param("target", target)
			.param("text", result)
			.param("provider", provider.name())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return new TranslationView(result, sourceLanguage, target, true, provider.name(), false);
	}

	private TranslationView cached(String sourceType, UUID sourceId, String target, String hash) {
		return this.jdbc.sql("""
				select translated_text, source_language_code, provider from content_translations
				where source_type = :type and source_id = :source and source_field = 'body' and target_language_code = :target
				  and source_hash = :hash""")
			.param("type", sourceType)
			.param("source", sourceId)
			.param("target", target)
			.param("hash", hash)
			.query((rs, i) -> new TranslationView(rs.getString("translated_text"), rs.getString("source_language_code"), target,
					true, rs.getString("provider"), true))
			.optional()
			.orElse(null);
	}

	/** The requested language, else the caller's own, else English; it must be a known language. */
	private String targetLanguage(CurrentUser user, String requested) {
		String target = (requested != null && !requested.isBlank()) ? requested.strip().toLowerCase(Locale.ROOT)
				: ((user.locale() != null) ? user.locale().getLanguage() : "en");
		boolean known = this.jdbc.sql("select exists (select 1 from languages where code = :c)")
			.param("c", target)
			.query(Boolean.class)
			.single();
		if (!known) {
			throw ApiException.badRequest("feed.translation_language_invalid");
		}
		return target;
	}

	private Set<String> namesOfTaggedStudents(UUID postId) {
		Set<String> tokens = new TreeSet<>(java.util.Comparator.comparing(String::length).reversed().thenComparing(String::compareTo));
		this.jdbc.sql("""
				select u.given_name, u.middle_name, u.family_name, u.display_name from post_student_tags t
				join users u on u.id = t.student_id where t.post_id = :post""")
			.param("post", postId)
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

	/** The text with names, e-mail addresses and phone-like numbers replaced by placeholders, and how to put them back. */
	static Masked mask(String text, Set<String> names) {
		List<String> originals = new ArrayList<>();
		String masked = text;
		for (String name : names) {
			Pattern pattern = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])",
					Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
			masked = replaceAll(masked, pattern, originals);
		}
		masked = replaceAll(masked, EMAIL, originals);
		masked = replaceAll(masked, DIGITS, originals);
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
