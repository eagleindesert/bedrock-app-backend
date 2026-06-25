package com.bedrock.app.item.service;

import com.bedrock.app.item.domain.Item;
import com.bedrock.app.item.domain.ItemRepository;
import com.bedrock.app.item.dto.request.ItemCreateRequest;
import com.bedrock.app.item.dto.request.ItemUpdateRequest;
import com.bedrock.app.item.dto.response.ItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;

    @Transactional
    public ItemResponse create(Long ownerId, ItemCreateRequest request) {
        Item item = Item.builder()
                .name(request.getName())
                .ownerId(ownerId)
                .attributes(request.getAttributes())
                .build();
        return ItemResponse.from(itemRepository.save(item));
    }

    @Transactional(readOnly = true)
    public List<ItemResponse> findAll(Long ownerId) {
        return itemRepository.findByOwnerId(ownerId).stream()
                .map(ItemResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ItemResponse findOne(UUID id, Long ownerId) {
        return ItemResponse.from(getOwnedItem(id, ownerId));
    }

    @Transactional
    public ItemResponse update(UUID id, Long ownerId, ItemUpdateRequest request) {
        Item item = getOwnedItem(id, ownerId);
        item.update(request.getName(), request.getAttributes());
        return ItemResponse.from(item);
    }

    @Transactional
    public void delete(UUID id, Long ownerId) {
        Item item = getOwnedItem(id, ownerId);
        item.delete();
    }

    private Item getOwnedItem(UUID id, Long ownerId) {
        return itemRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "아이템을 찾을 수 없습니다."));
    }
}
