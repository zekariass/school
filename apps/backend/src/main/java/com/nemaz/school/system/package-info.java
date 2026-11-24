/**
 * Cross-cutting technical platform: security baseline, OpenAPI, scheduling, outbox
 * maintenance and system endpoints. Business modules (identity, tenancy, academic, ...)
 * are separate top-level packages, each with api / application / domain /
 * infrastructure subpackages (Implementation Plan M0.3).
 */
@ApplicationModule(displayName = "System")
package com.nemaz.school.system;

import org.springframework.modulith.ApplicationModule;
