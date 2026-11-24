/**
 * Shared kernel: error model, request ids, pagination, ids, time and locale support.
 * Every module may use it; it must not depend on any business module.
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.OPEN)
package com.nemaz.school.common;

import org.springframework.modulith.ApplicationModule;
