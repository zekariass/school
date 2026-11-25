package com.nemaz.school.system.scheduling;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * ShedLock's method proxy throws {@code LockingNotSupportedException} on every call to a locked method that returns a
 * primitive other than {@code void}, so such a job never runs. Integration tests switch scheduling off
 * ({@code school.scheduling.enabled=false}), which hides this, hence this static check.
 */
class SchedulerLockMethodsTest {

	private static final JavaClasses CLASSES = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("com.nemaz.school");

	@Test
	void lockedMethodsReturnVoidOrAnObject() {
		methods().that()
			.areAnnotatedWith(SchedulerLock.class)
			.should(new ArchCondition<>("return void or a reference type (ShedLock returns null when the lock is held)") {
				@Override
				public void check(JavaMethod method, ConditionEvents events) {
					Class<?> type = method.getRawReturnType().reflect();
					boolean ok = !type.isPrimitive() || type == void.class;
					events.add(new SimpleConditionEvent(method, ok,
							method.getFullName() + " returns " + type.getName()));
				}
			})
			.check(CLASSES);
	}

	@Test
	void lockedMethodsAreScheduled() {
		methods().that()
			.areAnnotatedWith(SchedulerLock.class)
			.should()
			.beAnnotatedWith(Scheduled.class)
			.check(CLASSES);
	}

}
