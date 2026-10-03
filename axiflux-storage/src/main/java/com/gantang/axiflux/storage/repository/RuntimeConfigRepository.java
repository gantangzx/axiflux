package com.gantang.axiflux.storage.repository;

import com.gantang.axiflux.storage.entity.RuntimeConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link RuntimeConfigEntity} (runtime_config override table).
 */
@Repository
public interface RuntimeConfigRepository extends JpaRepository<RuntimeConfigEntity, String> {
}
