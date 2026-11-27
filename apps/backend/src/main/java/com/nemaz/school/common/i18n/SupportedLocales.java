package com.nemaz.school.common.i18n;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The ONE source of truth for "the languages the backend has messages for": English (the root bundle, always the
 * fallback) plus every {@code messages_<language>.properties} on the classpath. Adding a language to the backend is adding
 * its bundle file, nothing else. Which of these a given country or school uses is data ({@code country_languages},
 * {@code school_languages}); this class only says what can be rendered. {@code school.i18n.supported-locales} may
 * restrict the detected set (an operator switch, empty by default).
 *
 * <p>
 * Everything that picks, lists or loops over languages (error and notification rendering, per-language text such as role
 * names or attendance words, templates, {@code GET /meta}) uses this class instead of a literal list.
 */
@Component
public class SupportedLocales {

	/** The fallback and root bundle language. */
	public static final String FALLBACK = "en";

	private static final String BUNDLE_PREFIX = "messages_";

	private static final String BUNDLE_SUFFIX = ".properties";

	private final List<String> codes;

	@Autowired
	SupportedLocales(@Value("${school.i18n.supported-locales:}") List<String> restrictTo) {
		this.codes = withFallback(restrict(detect(), restrictTo));
	}

	private SupportedLocales(List<String> fixed, boolean unused) {
		this.codes = withFallback(fixed);
	}

	/** A fixed list (tests); English is added first when missing. */
	public static SupportedLocales of(String... codes) {
		return new SupportedLocales(List.of(codes), true);
	}

	private static List<String> withFallback(List<String> codes) {
		Set<String> ordered = new LinkedHashSet<>();
		ordered.add(FALLBACK);
		codes.forEach((code) -> ordered.add(code.toLowerCase(Locale.ROOT)));
		return List.copyOf(ordered);
	}

	/** Language codes, English first, the others alphabetical. */
	public List<String> codes() {
		return this.codes;
	}

	public List<Locale> locales() {
		return this.codes.stream().map(Locale::forLanguageTag).toList();
	}

	public boolean supports(String languageCode) {
		return languageCode != null && this.codes.contains(languageCode.toLowerCase(Locale.ROOT));
	}

	/** The locale to write to someone in: their language when the backend has messages for it, else English. */
	public Locale localeOf(String languageCode) {
		return supports(languageCode) ? Locale.forLanguageTag(languageCode.toLowerCase(Locale.ROOT)) : Locale.ENGLISH;
	}

	/**
	 * The ISO 639-1 languages that have a bundle file {@code messages_<code>.properties}: each code is probed on the class
	 * path by name, which works the same in a directory and in the packaged jar (a wildcard scan of the class path root does
	 * not).
	 */
	private static List<String> detect() {
		Set<String> found = new TreeSet<>();
		for (String code : Locale.getISOLanguages()) {
			if (new ClassPathResource(BUNDLE_PREFIX + code + BUNDLE_SUFFIX).exists()) {
				found.add(code);
			}
		}
		return new ArrayList<>(found);
	}

	private static List<String> restrict(List<String> detected, List<String> restrictTo) {
		List<String> wanted = restrictTo.stream().map((c) -> c.trim().toLowerCase(Locale.ROOT)).filter((c) -> !c.isEmpty()).toList();
		if (wanted.isEmpty()) {
			return detected;
		}
		return detected.stream().filter(wanted::contains).toList();
	}

}
