package com.nemaz.school.common.persistence;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.tenancy.domain.Campus;
import com.nemaz.school.tenancy.domain.Organization;
import org.junit.jupiter.api.Test;

import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class BaseEntityTest {

	private static Organization organization(UUID id) {
		Organization o = new Organization("ET", "Org", null, "org");
		ReflectionTestUtils.setField(o, "id", id);
		return o;
	}

	@Test
	void transientEntitiesAreOnlyEqualToThemselves() {
		Organization a = organization(null);
		Organization b = organization(null);
		assertThat(a).isEqualTo(a).isNotEqualTo(b);
	}

	@Test
	void persistentEntitiesAreEqualById() {
		UUID id = UUID.randomUUID();
		assertThat(organization(id)).isEqualTo(organization(id)).hasSameHashCodeAs(organization(id));
		assertThat(organization(id)).isNotEqualTo(organization(UUID.randomUUID()));
	}

	@Test
	void differentEntityTypesWithTheSameIdAreNotEqual() {
		UUID id = UUID.randomUUID();
		Campus campus = new Campus(UUID.randomUUID(), "c", null, null);
		ReflectionTestUtils.setField(campus, "id", id);
		assertThat(organization(id)).isNotEqualTo(campus).isNotEqualTo(null).isNotEqualTo("x");
	}

	@Test
	void hashCodeSurvivesIdAssignment() {
		Organization o = organization(null);
		Set<Organization> set = new HashSet<>();
		set.add(o);
		ReflectionTestUtils.setField(o, "id", UUID.randomUUID()); // what persist() does
		assertThat(set).contains(o);
	}

}
