package com.nemaz.school.academic;

import java.util.Optional;

import com.nemaz.school.common.security.CurrentUser;

/**
 * Extension point of {@link ClassAccess#canView}: another module contributes who else may view classes
 * by declaring a bean. M5 adds the guardian rule here ("ACTIVE guardian, not custody-restricted, of a
 * student ACTIVE in the class"); nothing else needs to change. Rules only ever widen view access; they never
 * grant teach or manage.
 * <p>
 * A rule returns a SQL boolean expression over the {@code classes} row aliased {@code classAlias}, with
 * named parameters whose names start with its own unique prefix (not {@code ca_}), or empty when it does
 * not apply to this user.
 */
public interface ClassAccessRule {

	Optional<SqlFragment> visibleClasses(CurrentUser user, String classAlias);

}
