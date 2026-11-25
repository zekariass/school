package com.nemaz.school.identity;

import java.util.UUID;

/**
 * Lets the module that knows a managed account's school decide whether it may sign in with a password
 * (setting {@code students.password_login.enabled}). Consulted for MANAGED accounts only; every
 * implementation must agree.
 */
public interface ManagedAccountPolicy {

	boolean mayUsePassword(UUID userId);

}
