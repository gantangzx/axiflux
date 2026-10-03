package com.gantang.axiflux.registry.repo;

import com.gantang.axiflux.registry.entity.SkillCatalog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkillCatalogRepository extends JpaRepository<SkillCatalog, String> {

    @Query(value = """
        SELECT * FROM skill_catalog
        WHERE (:q IS NULL
               OR lower(name) LIKE concat('%', lower(:q), '%')
               OR lower(coalesce(author, '')) LIKE concat('%', lower(:q), '%')
               OR lower(coalesce(description, '')) LIKE concat('%', lower(:q), '%')
               OR lower(array_to_string(tags, ',')) LIKE concat('%', lower(:q), '%'))
        ORDER BY updated_at DESC
        """,
        countQuery = """
        SELECT count(*) FROM skill_catalog
        WHERE (:q IS NULL
               OR lower(name) LIKE concat('%', lower(:q), '%')
               OR lower(coalesce(author, '')) LIKE concat('%', lower(:q), '%')
               OR lower(coalesce(description, '')) LIKE concat('%', lower(:q), '%')
               OR lower(array_to_string(tags, ',')) LIKE concat('%', lower(:q), '%'))
        """,
        nativeQuery = true)
    Page<SkillCatalog> search(@Param("q") String q, Pageable pageable);
}
