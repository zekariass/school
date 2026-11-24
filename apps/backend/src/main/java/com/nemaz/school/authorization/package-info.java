/**
 * Authorization (Implementation Plan M1 step 6): roles, permissions, scoped role assignments
 * and the scope-aware policy {@code can(user, permission, scope)}.
 * <ul>
 * <li>Every module checks access through {@link com.nemaz.school.common.security.AccessControl}
 * (shared kernel), implemented here, or through the {@code @authz} bean in
 * {@code @PreAuthorize}. This module depends on tenancy's public
 * {@link com.nemaz.school.tenancy.TenantDirectory} to resolve coverage, never the reverse,
 * so there is no cycle.</li>
 * <li>The identity module reads a user's grants through {@link com.nemaz.school.authorization.RoleAssignments}
 * when it authenticates an access token.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Authorization")
package com.nemaz.school.authorization;

import org.springframework.modulith.ApplicationModule;
