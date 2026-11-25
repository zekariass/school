/**
 * People of a school (Implementation Plan M3, Database_Design §4.4, R-01..R-03, R-31): student and teacher
 * profiles (shared primary key with the user), staff (school_staff), student enrollment (school_students),
 * school-managed student accounts and their sign-in (QR or text login codes, optional username and password).
 * <p>
 * Accounts themselves belong to the identity module; this module creates them through
 * {@code identity.UserAccounts} and plugs into identity through its ports ({@code LoginCodeRedeemer},
 * {@code ManagedAccountPolicy}, {@code AccountMemberships}). It implements the academic module's
 * {@code SchoolPeople} port and uses {@code academic.ClassMemberships} and {@code ClassAccess}: the dependency
 * between the two is one-way (people depends on academic). Guardians and invitations arrive with M5.
 */
@ApplicationModule(displayName = "People")
package com.nemaz.school.people;

import org.springframework.modulith.ApplicationModule;
