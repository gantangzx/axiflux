package com.gantang.tianshu.storage.repository;

import com.gantang.tianshu.storage.entity.RuntimeConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link RuntimeConfigEntity} (runtime_config override table).
 */
@Repository
public interface RuntimeConfigRepository extends JpaRepository<RuntimeConfigEntity, String> {
}
