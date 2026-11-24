package com.nemaz.school.authorization.domain;

import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.common.persistence.IdentifiedEntity;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.ScopeLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An administrative role held by a user at one scope (R-02, R-34). Ends by revocation
 * ({@code status = REVOKED}, {@code revoked_at}); rows are never deleted.
 */
@Entity
@Table(name = "role_assignments")
public class RoleAssignment extends IdentifiedEntity {

	private UUID userId;

	private String roleCode;

	@Enumerated(EnumType.STRING)
	private ScopeLevel scopeType;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2, columnDefinition = "char(2)")
	private String countryCode;

	private UUID organizationId;

	private UUID schoolId;

	private UUID campusId;

	@Enumerated(EnumType.STRING)
	private AssignmentStatus status;

	private UUID grantedBy;

	private Instant grantedAt;

	private Instant revokedAt;

	protected RoleAssignment() {
	}

	public RoleAssignment(UUID userId, String roleCode, AccessScope scope, UUID grantedBy, Instant grantedAt) {
		this.userId = userId;
		this.roleCode = roleCode;
		this.scopeType = scope.level();
		this.countryCode = scope.countryCode();
		this.organizationId = scope.organizationId();
		this.schoolId = scope.schoolId();
		this.campusId = scope.campusId();
		this.status = AssignmentStatus.ACTIVE;
		this.grantedBy = grantedBy;
		this.grantedAt = grantedAt;
	}

	public void revoke(Instant at) {
		this.status = AssignmentStatus.REVOKED;
		this.revokedAt = at;
	}

	public AccessScope scope() {
		return new AccessScope(this.scopeType, this.countryCode, this.organizationId, this.schoolId, this.campusId);
	}

	public boolean isActive() {
		return this.status == AssignmentStatus.ACTIVE;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public String getRoleCode() {
		return this.roleCode;
	}

	public AssignmentStatus getStatus() {
		return this.status;
	}

	public UUID getGrantedBy() {
		return this.grantedBy;
	}

	public Instant getGrantedAt() {
		return this.grantedAt;
	}

	public Instant getRevokedAt() {
		return this.revokedAt;
	}

}
