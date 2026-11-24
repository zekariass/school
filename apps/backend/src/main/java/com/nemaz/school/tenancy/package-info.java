/**
 * Tenancy and configuration: organizations, schools, campuses, school languages, and the
 * resolution of behaviour settings and feature flags (Database_Design §4.2, R-33). Other
 * modules use {@link com.nemaz.school.tenancy.SettingsService} and
 * {@link com.nemaz.school.tenancy.FeatureService}, and react to the events in this package.
 */
@ApplicationModule(displayName = "Tenancy & configuration")
package com.nemaz.school.tenancy;

import org.springframework.modulith.ApplicationModule;
