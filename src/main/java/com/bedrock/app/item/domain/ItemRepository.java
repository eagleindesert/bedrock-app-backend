package com.bedrock.app.item.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    List<Item> findByOwnerId(Long ownerId);

    Optional<Item> findByIdAndOwnerId(UUID id, Long ownerId);
}
