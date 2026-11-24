package com.nemaz.school.authorization.infrastructure;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.authorization.domain.AssignmentStatus;
import com.nemaz.school.authorization.domain.RoleAssignment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RoleAssignmentRepository
		extends JpaRepository<RoleAssignment, UUID>, JpaSpecificationExecutor<RoleAssignment> {

	List<RoleAssignment> findByUserIdAndStatusOrderByGrantedAtAsc(UUID userId, AssignmentStatus status);

	boolean existsByRoleCodeAndStatus(String roleCode, AssignmentStatus status);

	long countByRoleCodeAndStatus(String roleCode, AssignmentStatus status);

}
