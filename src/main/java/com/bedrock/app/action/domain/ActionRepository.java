package com.bedrock.app.action.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActionRepository extends JpaRepository<Action, UUID> {

    @Query("""
            select distinct action
            from Action action
            left join fetch action.blocks
            where action.ownerId = :ownerId or action.preset = true
            order by action.preset desc, action.createdAt desc
            """)
    List<Action> findAllVisible(@Param("ownerId") Long ownerId);

    @Query("""
            select distinct action
            from Action action
            left join fetch action.blocks
            where action.id = :id
              and (action.ownerId = :ownerId or action.preset = true)
            """)
    Optional<Action> findVisibleById(
            @Param("id") UUID id,
            @Param("ownerId") Long ownerId
    );
}