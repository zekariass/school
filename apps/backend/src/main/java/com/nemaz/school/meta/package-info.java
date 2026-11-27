/**
 * Public platform metadata ({@code GET /api/v1/meta}): service version, server time, the languages the backend has messages
 * for, the platform default country and the ENABLED countries with their configuration. A module of its own because it reads
 * tenancy (the platform default) and reference data, which {@code system} (used by both) must not depend on.
 */
@ApplicationModule(displayName = "Platform metadata")
package com.nemaz.school.meta;

import org.springframework.modulith.ApplicationModule;
