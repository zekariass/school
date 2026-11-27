package com.nemaz.school.common.i18n;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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

	private static final Logger log = LoggerFactory.getLogger(SupportedLocales.class);

	private static final Pattern BUNDLE = Pattern.compile("^messages_([A-Za-z]{2,3})\\.properties$");

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

	private static List<String> detect() {
		Set<String> found = new TreeSet<>();
		try {
			for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:messages_*.properties")) {
				String name = resource.getFilename();
				Matcher matcher = (name != null) ? BUNDLE.matcher(name) : null;
				if (matcher != null && matcher.matches()) {
					found.add(matcher.group(1).toLowerCase(Locale.ROOT));
				}
			}
		}
		catch (IOException ex) {
			log.warn("Could not scan the message bundles: {}", ex.getMessage());
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
