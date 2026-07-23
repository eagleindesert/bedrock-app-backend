package com.bedrock.app.collection.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollectionUserRepository extends JpaRepository<CollectionUser, CollectionUserId> {

    List<CollectionUser> findByUserId(Long userId);

    List<CollectionUser> findByCollectionId(UUID collectionId);

    Optional<CollectionUser> findByCollectionIdAndUserId(UUID collectionId, Long userId);

    long countByCollectionIdAndRole(UUID collectionId, CollectionRole role);

    void deleteByCollectionId(UUID collectionId);
}
