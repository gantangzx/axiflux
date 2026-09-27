package com.gantang.tianshu.registry.repo;

import com.gantang.tianshu.registry.entity.SkillVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkillVersionRepository extends JpaRepository<SkillVersion, Long> {

    List<SkillVersion> findBySlugOrderByPublishedAtDesc(String slug);

    Optional<SkillVersion> findBySlugAndVersion(String slug, String version);

    Optional<SkillVersion> findFirstBySlugOrderByPublishedAtDesc(String slug);
}
