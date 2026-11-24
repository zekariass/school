package com.nemaz.school.authorization.infrastructure;

import com.nemaz.school.authorization.domain.Role;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, String> {

}
