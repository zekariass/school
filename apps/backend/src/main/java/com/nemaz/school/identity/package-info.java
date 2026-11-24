/**
 * Identity (Implementation Plan M1 steps 4-5): users, sign-in and sessions.
 * <ul>
 * <li>Sign-in by one-time code (phone first, or email) or by password (argon2id, lockout).</li>
 * <li>ES256 access tokens (15 minutes; {@code sub}, {@code sid}, no roles) and rotating refresh
 * tokens stored as SHA-256 hashes in {@code auth_sessions}, with reuse detection per token
 * family and a Redis deny-list that stops access tokens of revoked families at once.</li>
 * <li>The bearer-token principal ({@code common.security.CurrentUser}) with the user's role
 * grants from the authorization module; inactive accounts are rejected.</li>
 * <li>{@code /api/v1/me} and the bootstrap platform administrator.</li>
 * </ul>
 * Federated sign-in (Google, Apple) is deferred; {@code federated_identities} exists.
 */
@ApplicationModule(displayName = "Identity")
package com.nemaz.school.identity;

import org.springframework.modulith.ApplicationModule;
