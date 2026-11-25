/**
 * Academic structure and classes (Implementation Plan M3, Database_Design §4.5): academic years and periods,
 * grade levels, subjects, classes with their student and teacher rosters, saved student groups.
 * <p>
 * The reusable part is {@link com.nemaz.school.academic.ClassAccess}: "may user U see, teach or manage class C"
 * for every later module that hangs content off a class (attendance, points, feed, messaging). People data
 * (who is an ACTIVE student or teacher of a school, names, avatars) is reached through the
 * {@link com.nemaz.school.academic.SchoolPeople} port, implemented by the people module; the people module
 * in turn uses {@link com.nemaz.school.academic.ClassMemberships}. The dependency is one-way
 * (people depends on academic).
 */
@ApplicationModule(displayName = "Academic")
package com.nemaz.school.academic;

import org.springframework.modulith.ApplicationModule;
