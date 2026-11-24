package com.nemaz.school.common;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Server message bundles must stay in sync, like packages/i18n catalogs. */
class MessagesTest {

	@Test
	void amharicBundleHasExactlyTheEnglishKeys() throws IOException {
		Set<String> en = load("/messages.properties").stringPropertyNames();
		Set<String> am = load("/messages_am.properties").stringPropertyNames();
		assertThat(am).containsExactlyInAnyOrderElementsOf(en);
	}

	@Test
	void noEmptyMessages() throws IOException {
		for (String bundle : new String[] { "/messages.properties", "/messages_am.properties" }) {
			Properties props = load(bundle);
			props.forEach((key, value) -> assertThat(value.toString()).as(bundle + " " + key).isNotBlank());
		}
	}

	private static Properties load(String path) throws IOException {
		Properties props = new Properties();
		try (var in = new InputStreamReader(MessagesTest.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
			props.load(in);
		}
		return props;
	}

}
