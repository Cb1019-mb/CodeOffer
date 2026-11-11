package com.codeoffer.backend.settings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface AppSettingRepository extends JpaRepository<AppSetting, Long> {

    Optional<AppSetting> findByUserIdAndSettingKey(Long userId, String settingKey);

    @Transactional
    void deleteByUserId(Long userId);
}