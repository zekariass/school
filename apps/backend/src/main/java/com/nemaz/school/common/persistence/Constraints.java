package com.nemaz.school.common.persistence;

/**
 * Recognises which database constraint an exception violated, from the PostgreSQL error text
 * ({@code ... violates unique constraint "ux_class_student_active"}), so services can turn races the
 * pre-checks cannot rule out into the right 409.
 */
public final class Constraints {

	private Constraints() {
	}

	/** True when the exception chain mentions the named constraint or index. */
	public static boolean violated(Throwable ex, String constraint) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			String message = t.getMessage();
			if (message != null && message.contains("\"" + constraint + "\"")) {
				return true;
			}
		}
		return false;
	}

}
