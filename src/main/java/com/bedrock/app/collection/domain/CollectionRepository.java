package com.bedrock.app.collection.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CollectionRepository extends JpaRepository<Collection, UUID> {

    List<Collection> findByIdInAndKind(List<UUID> ids, CollectionKind kind);

    List<Collection> findByIdIn(List<UUID> ids);
}
