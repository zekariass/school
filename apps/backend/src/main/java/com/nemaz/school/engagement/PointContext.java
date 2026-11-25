package com.nemaz.school.engagement;

/**
 * Where a point was earned (Database_Design §4.7). HOME points (awarded by a guardian) never count toward class or
 * school totals, rankings, badges or reports (decision D2).
 */
public enum PointContext {

	CLASS, SCHOOL, HOME

}
