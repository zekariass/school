package com.nemaz.school.tenancy.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import tools.jackson.databind.JsonNode;

/**
 * Validates a JSON value against the small JSON Schema subset used by
 * {@code setting_definitions.value_schema}. Supported keywords:
 * <ul>
 * <li>{@code type}: one of boolean, integer, number, string, array, object, null, or an array of them</li>
 * <li>{@code enum}</li>
 * <li>{@code minimum}, {@code maximum} (numbers)</li>
 * <li>{@code minLength}, {@code maxLength}, {@code pattern} (strings; the pattern may match anywhere)</li>
 * <li>{@code minItems}, {@code maxItems}, {@code items} (arrays; {@code items} is one schema)</li>
 * <li>{@code properties}, {@code required}, {@code additionalProperties} (objects; the latter only as a boolean)</li>
 * </ul>
 * Annotation keywords ({@code title}, {@code description}, {@code default}, {@code $comment})
 * are ignored. Any other keyword makes the schema invalid ({@link IllegalArgumentException}),
 * so a definition can never silently be validated more weakly than it reads. A library such
 * as networknt is avoided because the available ones still depend on Jackson 2.
 *
 * <p>
 * Errors are language-neutral: {@code "<path>: <keyword>"} with the path as {@code $},
 * {@code $.field}, {@code $[2]}.
 */
public final class ValueSchemaValidator {

	private static final Set<String> ANNOTATIONS = Set.of("title", "description", "default", "$comment");

	private static final Set<String> SUPPORTED = Set.of("type", "enum", "minimum", "maximum", "minLength", "maxLength",
			"pattern", "minItems", "maxItems", "items", "properties", "required", "additionalProperties");

	private ValueSchemaValidator() {
	}

	/** Empty when {@code value} satisfies {@code schema}. */
	public static List<String> validate(JsonNode schema, JsonNode value) {
		List<String> errors = new ArrayList<>();
		check(schema, value, "$", errors);
		return errors;
	}

	private static void check(JsonNode schema, JsonNode value, String path, List<String> errors) {
		if (!schema.isObject()) {
			throw new IllegalArgumentException("Schema at " + path + " must be an object");
		}
		for (String keyword : schema.propertyNames()) {
			if (!SUPPORTED.contains(keyword) && !ANNOTATIONS.contains(keyword)) {
				throw new IllegalArgumentException("Unsupported schema keyword: " + keyword);
			}
		}

		JsonNode type = schema.get("type");
		if (type != null && !matchesType(type, value)) {
			errors.add(path + ": type");
			return; // the remaining keywords only make sense for the right type
		}

		JsonNode enumeration = schema.get("enum");
		if (enumeration != null) {
			boolean found = false;
			for (JsonNode candidate : enumeration) {
				found |= candidate.equals(value);
			}
			if (!found) {
				errors.add(path + ": enum");
			}
		}

		if (value.isNumber()) {
			checkNumber(schema, value, path, errors);
		}
		else if (value.isString()) {
			checkString(schema, value, path, errors);
		}
		else if (value.isArray()) {
			checkArray(schema, value, path, errors);
		}
		else if (value.isObject()) {
			checkObject(schema, value, path, errors);
		}
	}

	private static boolean matchesType(JsonNode type, JsonNode value) {
		if (type.isArray()) {
			for (JsonNode alternative : type) {
				if (matchesSingleType(alternative.asString(), value)) {
					return true;
				}
			}
			return false;
		}
		return matchesSingleType(type.asString(), value);
	}

	private static boolean matchesSingleType(String type, JsonNode value) {
		return switch (type) {
			case "boolean" -> value.isBoolean();
			case "integer" -> value.isNumber() && isIntegral(value);
			case "number" -> value.isNumber();
			case "string" -> value.isString();
			case "array" -> value.isArray();
			case "object" -> value.isObject();
			case "null" -> value.isNull();
			default -> throw new IllegalArgumentException("Unsupported schema type: " + type);
		};
	}

	private static boolean isIntegral(JsonNode number) {
		return number.isIntegralNumber() || number.decimalValue().stripTrailingZeros().scale() <= 0;
	}

	private static void checkNumber(JsonNode schema, JsonNode value, String path, List<String> errors) {
		JsonNode minimum = schema.get("minimum");
		if (minimum != null && value.decimalValue().compareTo(minimum.decimalValue()) < 0) {
			errors.add(path + ": minimum");
		}
		JsonNode maximum = schema.get("maximum");
		if (maximum != null && value.decimalValue().compareTo(maximum.decimalValue()) > 0) {
			errors.add(path + ": maximum");
		}
	}

	private static void checkString(JsonNode schema, JsonNode value, String path, List<String> errors) {
		String text = value.asString();
		int length = text.codePointCount(0, text.length());
		JsonNode minLength = schema.get("minLength");
		if (minLength != null && length < minLength.asInt()) {
			errors.add(path + ": minLength");
		}
		JsonNode maxLength = schema.get("maxLength");
		if (maxLength != null && length > maxLength.asInt()) {
			errors.add(path + ": maxLength");
		}
		JsonNode pattern = schema.get("pattern");
		if (pattern != null) {
			try {
				if (!Pattern.compile(pattern.asString()).matcher(text).find()) {
					errors.add(path + ": pattern");
				}
			}
			catch (PatternSyntaxException ex) {
				throw new IllegalArgumentException("Invalid schema pattern at " + path, ex);
			}
		}
	}

	private static void checkArray(JsonNode schema, JsonNode value, String path, List<String> errors) {
		JsonNode minItems = schema.get("minItems");
		if (minItems != null && value.size() < minItems.asInt()) {
			errors.add(path + ": minItems");
		}
		JsonNode maxItems = schema.get("maxItems");
		if (maxItems != null && value.size() > maxItems.asInt()) {
			errors.add(path + ": maxItems");
		}
		JsonNode items = schema.get("items");
		if (items != null) {
			for (int i = 0; i < value.size(); i++) {
				check(items, value.get(i), path + "[" + i + "]", errors);
			}
		}
	}

	private static void checkObject(JsonNode schema, JsonNode value, String path, List<String> errors) {
		JsonNode properties = schema.get("properties");
		JsonNode required = schema.get("required");
		if (required != null) {
			for (JsonNode name : required) {
				if (!value.has(name.asString())) {
					errors.add(path + "." + name.asString() + ": required");
				}
			}
		}
		Set<String> declared = new HashSet<>();
		if (properties != null) {
			for (String name : properties.propertyNames()) {
				declared.add(name);
				JsonNode child = value.get(name);
				if (child != null) {
					check(properties.get(name), child, path + "." + name, errors);
				}
			}
		}
		JsonNode additional = schema.get("additionalProperties");
		if (additional != null) {
			if (!additional.isBoolean()) {
				throw new IllegalArgumentException("additionalProperties must be a boolean");
			}
			if (!additional.asBoolean()) {
				for (String name : value.propertyNames()) {
					if (!declared.contains(name)) {
						errors.add(path + "." + name + ": additionalProperties");
					}
				}
			}
		}
	}

}
