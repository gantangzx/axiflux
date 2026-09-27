package com.gantang.tianshu.storage.repository;

import com.gantang.tianshu.storage.entity.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for registered skills.
 */
@Repository
public interface SkillRepository extends JpaRepository<SkillEntity, Long> {

    Optional<SkillEntity> findByName(String name);

    List<SkillEntity> findByEnabledTrue();

    List<SkillEntity> findByEnabledFalse();

    boolean existsByName(String name);
}
