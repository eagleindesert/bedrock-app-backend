package com.bedrock.app.collection.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollectionUserRepository extends JpaRepository<CollectionUser, UUID> {

    List<CollectionUser> findByUserId(Long userId);

    Optional<CollectionUser> findByCollectionIdAndUserId(UUID collectionId, Long userId);
}
