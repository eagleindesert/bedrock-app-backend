package com.bedrock.app.collection.service;

import com.bedrock.app.collection.domain.*;
import com.bedrock.app.collection.dto.request.CollectionCreateRequest;
import com.bedrock.app.collection.dto.response.CollectionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CollectionService {

    private final CollectionRepository collectionRepository;
    private final CollectionUserRepository collectionUserRepository;

    @Transactional(readOnly = true)
    public List<CollectionResponse> findAll(Long userId, String kindValue) {
        List<CollectionUser> memberships = collectionUserRepository.findByUserId(userId);
        if (memberships.isEmpty()) {
            return List.of();
        }

        Map<UUID, CollectionRole> roleByCollectionId = memberships.stream()
                .collect(Collectors.toMap(CollectionUser::getCollectionId, CollectionUser::getRole));

        List<UUID> collectionIds = List.copyOf(roleByCollectionId.keySet());

        CollectionKind kind = CollectionKind.fromValue(kindValue);
        List<Collection> collections = kind != null
                ? collectionRepository.findByIdInAndKind(collectionIds, kind)
                : collectionRepository.findByIdIn(collectionIds);

        return collections.stream()
                .map(collection -> CollectionResponse.from(collection, roleByCollectionId.get(collection.getId())))
                .toList();
    }

    @Transactional
    public CollectionResponse create(Long userId, CollectionCreateRequest request) {
        CollectionKind kind = CollectionKind.fromValue(request.getKind());
        if (kind == null) {
            throw new IllegalArgumentException("kind는 필수입니다.");
        }

        Collection collection = Collection.builder()
                .kind(kind)
                .name(request.getName())
                .color(request.getColor())
                .icon(request.getIcon())
                .attributes(request.getAttributes())
                .build();
        collectionRepository.save(collection);

        CollectionUser owner = CollectionUser.builder()
                .collectionId(collection.getId())
                .userId(userId)
                .role(CollectionRole.OWNER)
                .build();
        collectionUserRepository.save(owner);

        return CollectionResponse.from(collection, CollectionRole.OWNER);
    }
}
