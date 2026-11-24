package com.nemaz.school.tenancy;

import java.util.List;

import com.nemaz.school.tenancy.application.ValueSchemaValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueSchemaValidatorTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static List<String> validate(String schema, String value) {
		return ValueSchemaValidator.validate(JSON.readTree(schema), JSON.readTree(value));
	}

	@Test
	void types() {
		assertThat(validate("{\"type\":\"boolean\"}", "true")).isEmpty();
		assertThat(validate("{\"type\":\"boolean\"}", "\"true\"")).containsExactly("$: type");
		assertThat(validate("{\"type\":\"boolean\"}", "null")).containsExactly("$: type");
		assertThat(validate("{\"type\":\"integer\"}", "5")).isEmpty();
		assertThat(validate("{\"type\":\"integer\"}", "5.0")).isEmpty();
		assertThat(validate("{\"type\":\"integer\"}", "5.5")).containsExactly("$: type");
		assertThat(validate("{\"type\":\"number\"}", "5.5")).isEmpty();
		assertThat(validate("{\"type\":\"number\"}", "\"5\"")).containsExactly("$: type");
		assertThat(validate("{\"type\":\"string\"}", "\"x\"")).isEmpty();
		assertThat(validate("{\"type\":\"array\"}", "[]")).isEmpty();
		assertThat(validate("{\"type\":\"object\"}", "[]")).containsExactly("$: type");
		assertThat(validate("{\"type\":\"null\"}", "null")).isEmpty();
	}

	@Test
	void typeUnions() {
		String schema = "{\"type\":[\"integer\",\"null\"],\"minimum\":0}";
		assertThat(validate(schema, "null")).isEmpty();
		assertThat(validate(schema, "7")).isEmpty();
		assertThat(validate(schema, "-1")).containsExactly("$: minimum");
		assertThat(validate(schema, "\"7\"")).containsExactly("$: type");
	}

	@Test
	void enumerations() {
		String schema = "{\"type\":\"string\",\"enum\":[\"TEACHER_ONLY\",\"EITHER\"]}";
		assertThat(validate(schema, "\"EITHER\"")).isEmpty();
		assertThat(validate(schema, "\"NOBODY\"")).containsExactly("$: enum");
		assertThat(validate("{\"enum\":[1,\"a\",null]}", "null")).isEmpty();
		assertThat(validate("{\"enum\":[1,\"a\",null]}", "2")).containsExactly("$: enum");
	}

	@Test
	void numberBounds() {
		String schema = "{\"type\":\"integer\",\"minimum\":1,\"maximum\":3}";
		assertThat(validate(schema, "1")).isEmpty();
		assertThat(validate(schema, "3")).isEmpty();
		assertThat(validate(schema, "0")).containsExactly("$: minimum");
		assertThat(validate(schema, "4")).containsExactly("$: maximum");
	}

	@Test
	void stringConstraints() {
		String schema = "{\"type\":\"string\",\"minLength\":2,\"maxLength\":4,\"pattern\":\"^[a-z]+$\"}";
		assertThat(validate(schema, "\"abc\"")).isEmpty();
		assertThat(validate(schema, "\"a\"")).containsExactly("$: minLength");
		assertThat(validate(schema, "\"abcde\"")).containsExactly("$: maxLength");
		assertThat(validate(schema, "\"ab1\"")).containsExactly("$: pattern");
		// length counts characters, not UTF-16 units
		assertThat(validate("{\"maxLength\":3}", "\"አማርኛ\"")).containsExactly("$: maxLength");
		assertThat(validate("{\"maxLength\":4}", "\"አማርኛ\"")).isEmpty();
	}

	@Test
	void arrays() {
		String schema = "{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"IMAGE\",\"VIDEO\"]},\"minItems\":1,\"maxItems\":2}";
		assertThat(validate(schema, "[\"IMAGE\"]")).isEmpty();
		assertThat(validate(schema, "[]")).containsExactly("$: minItems");
		assertThat(validate(schema, "[\"IMAGE\",\"VIDEO\",\"IMAGE\"]")).containsExactly("$: maxItems");
		assertThat(validate(schema, "[\"AUDIO\",3]")).containsExactly("$[0]: enum", "$[1]: type");
	}

	@Test
	void objects() {
		String schema = "{\"type\":\"object\",\"properties\":{\"start\":{\"type\":\"string\",\"pattern\":\"^[0-2][0-9]:[0-5][0-9]$\"},"
				+ "\"end\":{\"type\":\"string\"}},\"required\":[\"start\",\"end\"],\"additionalProperties\":false}";
		assertThat(validate(schema, "{\"start\":\"21:00\",\"end\":\"06:00\"}")).isEmpty();
		assertThat(validate(schema, "{\"start\":\"21:00\"}")).containsExactly("$.end: required");
		assertThat(validate(schema, "{\"start\":\"9pm\",\"end\":\"06:00\"}")).containsExactly("$.start: pattern");
		assertThat(validate(schema, "{\"start\":\"21:00\",\"end\":\"06:00\",\"extra\":1}"))
			.containsExactly("$.extra: additionalProperties");
		assertThat(validate("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}}", "{\"a\":1,\"b\":\"free\"}"))
			.isEmpty();
	}

	@Test
	void nestedPathsAreReported() {
		String schema = "{\"type\":\"object\",\"properties\":{\"days\":{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":7}}}}";
		assertThat(validate(schema, "{\"days\":[1,9]}")).containsExactly("$.days[1]: maximum");
	}

	@Test
	void annotationKeywordsAreIgnoredButUnknownKeywordsAreRejected() {
		assertThat(validate("{\"type\":\"boolean\",\"description\":\"d\",\"default\":false,\"title\":\"t\"}", "false"))
			.isEmpty();
		assertThatThrownBy(() -> validate("{\"type\":\"string\",\"format\":\"email\"}", "\"a\""))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("format");
		assertThatThrownBy(() -> validate("{\"oneOf\":[]}", "1")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> validate("{\"type\":\"date\"}", "1")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void typeMismatchStopsFurtherChecksOfThatValue() {
		assertThat(validate("{\"type\":\"string\",\"minLength\":3,\"enum\":[\"abc\"]}", "5")).containsExactly("$: type");
	}

}
