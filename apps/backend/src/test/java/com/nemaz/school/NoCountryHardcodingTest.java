package com.nemaz.school;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for docs/Spec_Review.md D42: no country is named in the backend's Java sources. A country, its zone, calendar,
 * languages, currency and phone rules come from the {@code countries} tables through {@code tenancy.CountryResolver}, so a
 * new country is data. Seeds in {@code db/migration} may name countries; Java may not use the string literals {@code "ET"},
 * {@code "Africa/Addis_Ababa"}, {@code "ETB"}, {@code "+251"} nor a literal language list that contains {@code "am"}
 * (languages come from {@code common.i18n.SupportedLocales}). Comments and Javadoc are free to use them as examples.
 *
 * <p>
 * What legitimately stays code: the calendar conversion algorithm and the {@code DateSystem} enum
 * ({@code imports/domain/EthiopianCalendar.java}).
 */
class NoCountryHardcodingTest {

	private static final Path SOURCES = Path.of("src/main/java");

	/** Files that may name Ethiopia: the Ethiopian calendar algorithm. */
	private static final Set<String> ALLOWED = Set.of("com/nemaz/school/imports/domain/EthiopianCalendar.java");

	private static final List<Pattern> FORBIDDEN = List.of(Pattern.compile("\"ET\""),
			Pattern.compile("\"Africa/Addis_Ababa\""), Pattern.compile("\"ETB\""), Pattern.compile("\"\\+251"),
			// a list or set literal that has "am" among its elements: List.of("en", "am"), Set.of("am", ...)
			Pattern.compile("(List|Set|Arrays\\.asList)\\.of\\([^)]*\"am\"[^)]*\\)"),
			Pattern.compile("Arrays\\.asList\\([^)]*\"am\"[^)]*\\)"), Pattern.compile("\"en,\\s*am\""),
			Pattern.compile("\"am\"\\.equals\\("), Pattern.compile("Locale\\.forLanguageTag\\(\"am\"\\)"));

	@Test
	void noJavaSourceNamesACountryAZoneOrACurrencyOfOne() throws IOException {
		List<String> violations = new ArrayList<>();
		try (Stream<Path> files = Files.walk(SOURCES)) {
			for (Path file : files.filter((p) -> p.toString().endsWith(".java")).toList()) {
				String relative = SOURCES.relativize(file).toString().replace('\\', '/');
				if (ALLOWED.contains(relative)) {
					continue;
				}
				String[] lines = Files.readString(file).split("\n", -1);
				boolean inBlockComment = false;
				for (int i = 0; i < lines.length; i++) {
					String code = codeOf(lines[i], inBlockComment);
					inBlockComment = endsInBlockComment(lines[i], inBlockComment);
					for (Pattern pattern : FORBIDDEN) {
						Matcher matcher = pattern.matcher(code);
						if (matcher.find()) {
							violations.add(relative + ":" + (i + 1) + " " + matcher.group().strip());
						}
					}
				}
			}
		}
		assertThat(violations).as("country literals in Java (read them from the database through CountryResolver)")
			.isEmpty();
	}

	@Test
	void theGuardCatchesWhatItIsMeantTo() {
		assertThat(FORBIDDEN.stream().anyMatch((p) -> p.matcher("String c = \"ET\";").find())).isTrue();
		assertThat(FORBIDDEN.stream().anyMatch((p) -> p.matcher("ZoneId.of(\"Africa/Addis_Ababa\")").find())).isTrue();
		assertThat(FORBIDDEN.stream().anyMatch((p) -> p.matcher("matches(\"^\\\\+251[79]\")").find())).isFalse();
		assertThat(FORBIDDEN.stream().anyMatch((p) -> p.matcher("startsWith(\"+251\")").find())).isTrue();
		assertThat(FORBIDDEN.stream().anyMatch((p) -> p.matcher("for (String l : List.of(\"en\", \"am\")) {").find()))
			.isTrue();
		assertThat(FORBIDDEN.stream().anyMatch((p) -> p.matcher("List.of(\"en\", \"sw\")").find())).isFalse();
		assertThat(codeOf("  // example +251911 \"ET\"", false)).isBlank();
		assertThat(codeOf("  * \"ET\" in a javadoc line", true)).isBlank();
	}

	/** The line without comments (line comments, block comment text and Javadoc continuation lines). */
	private static String codeOf(String line, boolean inBlockComment) {
		String text = line;
		if (inBlockComment) {
			int end = text.indexOf("*/");
			if (end < 0) {
				return "";
			}
			text = text.substring(end + 2);
		}
		String trimmed = text.stripLeading();
		if (trimmed.startsWith("//") || trimmed.startsWith("/*") && trimmed.contains("*/") && trimmed.endsWith("*/")) {
			return "";
		}
		int block = text.indexOf("/*");
		if (block >= 0 && !text.substring(0, block).contains("\"")) {
			text = text.substring(0, block);
		}
		int comment = text.indexOf("//");
		if (comment >= 0 && !text.substring(0, comment).contains("\"")) {
			text = text.substring(0, comment);
		}
		return text;
	}

	private static boolean endsInBlockComment(String line, boolean inBlockComment) {
		int start = inBlockComment ? 0 : line.indexOf("/*");
		if (start < 0) {
			return false;
		}
		int end = line.indexOf("*/", start);
		return end < 0;
	}

}
