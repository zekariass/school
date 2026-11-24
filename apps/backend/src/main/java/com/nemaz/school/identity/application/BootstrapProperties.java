package com.nemaz.school.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The first platform administrator ({@code school.bootstrap.admin.*}): created at startup
 * when no ACTIVE platform administrator exists and a phone and/or email is configured.
 */
@ConfigurationProperties("school.bootstrap.admin")
public record BootstrapProperties(String phone, String email, @DefaultValue("Platform administrator") String name) {
}
