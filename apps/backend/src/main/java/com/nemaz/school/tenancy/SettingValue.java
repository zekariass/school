package com.nemaz.school.tenancy;

import tools.jackson.databind.JsonNode;

/** An effective setting value and where it came from. {@code value} is a JSON null node for a null value. */
public record SettingValue(String key, JsonNode value, ConfigSource source) {
}
