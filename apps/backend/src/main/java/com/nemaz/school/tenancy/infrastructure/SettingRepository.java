package com.nemaz.school.tenancy.infrastructure;

import java.util.UUID;

import com.nemaz.school.tenancy.domain.Setting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SettingRepository extends JpaRepository<Setting, UUID>, JpaSpecificationExecutor<Setting> {

}
