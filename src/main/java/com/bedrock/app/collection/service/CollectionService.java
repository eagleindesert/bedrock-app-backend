package com.bedrock.app.collection.service;

import com.bedrock.app.auth.domain.user.UserRepository;
import com.bedrock.app.collection.domain.*;
import com.bedrock.app.collection.dto.request.CollectionCreateRequest;
import com.bedrock.app.collection.dto.request.CollectionUpdateRequest;
import com.bedrock.app.collection.dto.request.CollectionUserAddRequest;
import com.bedrock.app.collection.dto.response.CollectionResponse;
import com.bedrock.app.collection.dto.response.CollectionUserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CollectionService {

    private final CollectionRepository collectionRepository;
    private final CollectionUserRepository collectionUserRepository;
    private final UserRepository userRepository;

    // ── #26 GET /api/v1/collections ─────────────────────────────

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

    // ── #27 POST /api/v1/collections ────────────────────────────

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

    // ── #28 GET /api/v1/collections/{id} ────────────────────────

    @Transactional(readOnly = true)
    public CollectionResponse findOne(Long userId, UUID collectionId) {
        CollectionUser membership = getMembership(collectionId, userId);
        Collection collection = getCollection(collectionId);
        return CollectionResponse.from(collection, membership.getRole());
    }

    // ── #29 PATCH /api/v1/collections/{id} ──────────────────────

    @Transactional
    public CollectionResponse update(Long userId, UUID collectionId, CollectionUpdateRequest request) {
        CollectionUser membership = getMembership(collectionId, userId);
        requireOwner(membership);

        Collection collection = getCollection(collectionId);
        collection.applyPatch(request.getName(), request.getColor(), request.getIcon(), request.getAttributes());

        return CollectionResponse.from(collection, membership.getRole());
    }

    // ── #30 DELETE /api/v1/collections/{id} ─────────────────────

    @Transactional
    public void delete(Long userId, UUID collectionId) {
        CollectionUser membership = getMembership(collectionId, userId);
        requireOwner(membership);

        Collection collection = getCollection(collectionId);
        collection.delete();

        // collection_users는 ON DELETE CASCADE 대상이지만 컬렉션은 soft delete이므로 직접 정리한다.
        collectionUserRepository.deleteByCollectionId(collectionId);

        // NOTE: 소속 items(및 kind=semester derived_from 인스턴스) cascade는 이번 범위에서 제외.
        // 현재 Item 엔티티에는 collection_id 컬럼이 없어 items ↔ collections 연결이 불가능하다.
        // 후속 작업으로 Item에 collection_id 추가 후 이 메서드에서 함께 soft delete 처리해야 한다.
    }

    // ── #31 GET /api/v1/collections/{id}/users ──────────────────

    @Transactional(readOnly = true)
    public List<CollectionUserResponse> findMembers(Long userId, UUID collectionId) {
        getMembership(collectionId, userId);
        getCollection(collectionId);

        return collectionUserRepository.findByCollectionId(collectionId).stream()
                .map(CollectionUserResponse::from)
                .toList();
    }

    // ── #32 POST /api/v1/collections/{id}/users ─────────────────

    @Transactional
    public CollectionUserResponse addMember(Long requesterId, UUID collectionId, CollectionUserAddRequest request) {
        CollectionUser requesterMembership = getMembership(collectionId, requesterId);
        requireOwner(requesterMembership);
        getCollection(collectionId);

        if (request.getUserId() == null) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
        if (!userRepository.existsById(request.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        if (collectionUserRepository.findByCollectionIdAndUserId(collectionId, request.getUserId()).isPresent()) {
            throw new IllegalArgumentException("이미 컬렉션에 속한 사용자입니다.");
        }

        CollectionRole role = parseRole(request.getRole());

        CollectionUser newMember = CollectionUser.builder()
                .collectionId(collectionId)
                .userId(request.getUserId())
                .role(role)
                .build();
        collectionUserRepository.save(newMember);

        return CollectionUserResponse.from(newMember);
    }

    // ── #33 DELETE /api/v1/collections/{id}/users/{user_id} ─────

    @Transactional
    public void removeMember(Long requesterId, UUID collectionId, Long targetUserId) {
        CollectionUser requesterMembership = getMembership(collectionId, requesterId);
        requireOwner(requesterMembership);
        getCollection(collectionId);

        CollectionUser target = collectionUserRepository.findByCollectionIdAndUserId(collectionId, targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 사용자는 컬렉션 멤버가 아닙니다."));

        if (target.getRole() == CollectionRole.OWNER
                && collectionUserRepository.countByCollectionIdAndRole(collectionId, CollectionRole.OWNER) <= 1) {
            throw new IllegalArgumentException("컬렉션의 마지막 owner는 제거할 수 없습니다.");
        }

        collectionUserRepository.delete(target);
    }

    // ── 공통 ─────────────────────────────────────────────────────

    private Collection getCollection(UUID collectionId) {
        return collectionRepository.findById(collectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "컬렉션을 찾을 수 없습니다."));
    }

    private CollectionUser getMembership(UUID collectionId, Long userId) {
        return collectionUserRepository.findByCollectionIdAndUserId(collectionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "컬렉션을 찾을 수 없습니다."));
    }

    private void requireOwner(CollectionUser membership) {
        if (membership.getRole() != CollectionRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner만 수행할 수 있습니다.");
        }
    }

    private CollectionRole parseRole(String value) {
        if (value == null) {
            return CollectionRole.EDITOR;
        }
        try {
            return CollectionRole.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("유효하지 않은 role 입니다: " + value);
        }
    }
}
