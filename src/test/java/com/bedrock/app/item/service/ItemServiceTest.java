package com.bedrock.app.item.service;

import com.bedrock.app.item.domain.Item;
import com.bedrock.app.item.domain.ItemRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemServiceTest {

    @Test
    void createInCollectionKeepsTitleAsAttributeWithoutInferringName() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository);
        when(repository.save(any(Item.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UUID collectionId = UUID.randomUUID();
        service.createInCollection(
                17L,
                collectionId,
                Set.of("TODO"),
                Map.of("title", "캡스톤 발표자료 준비")
        );

        ArgumentCaptor<Item> itemCaptor = ArgumentCaptor.forClass(Item.class);
        verify(repository).save(itemCaptor.capture());
        Item savedItem = itemCaptor.getValue();
        assertThat(savedItem.getName()).isNull();
        assertThat(savedItem.getCollectionId()).isEqualTo(collectionId);
        assertThat(savedItem.getAttributes())
                .containsEntry("title", "캡스톤 발표자료 준비");
    }
}
