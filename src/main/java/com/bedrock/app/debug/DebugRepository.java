package com.bedrock.app.debug;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DebugRepository extends JpaRepository<DebugEntity, Long> {
}
