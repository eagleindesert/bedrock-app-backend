package com.bedrock.app.action.domain;

import com.bedrock.app.action.domain.type.BlockCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
        name = "actions",
        indexes = {
                @Index(name = "idx_actions_owner_id", columnList = "owner_id"),
                @Index(name = "idx_actions_is_preset", columnList = "is_preset")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@SQLRestriction("deleted_at IS NULL")
public class Action {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "is_preset", nullable = false)
    private boolean preset;

    @OneToMany(mappedBy = "action", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<ActionBlock> blocks = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @Builder
    public Action(
            Long ownerId,
            String name,
            String description,
            boolean enabled,
            boolean preset,
            List<BlockAssignment> blockAssignments
    ) {
        this.ownerId = ownerId;
        this.name = name;
        this.description = description;
        this.enabled = enabled;
        this.preset = preset;
        replaceBlocks(blockAssignments);
    }

    public void updateDefinition(
            String name,
            String description,
            boolean enabled,
            boolean preset,
            List<BlockAssignment> blockAssignments
    ) {
        this.name = name;
        this.description = description;
        this.enabled = enabled;
        this.preset = preset;

        if (blockAssignments != null) {
            replaceBlocks(blockAssignments);
        }
    }

    public void replaceBlocks(List<BlockAssignment> assignments) {
        if (assignments == null) {
            blocks.clear();
            return;
        }

        Map<BlockCode, ActionBlock> existingByCode = new EnumMap<>(BlockCode.class);
        for (ActionBlock block : blocks) {
            existingByCode.put(block.getBlockCode(), block);
        }

        Set<BlockCode> requestedCodes = EnumSet.noneOf(BlockCode.class);
        for (BlockAssignment assignment : assignments) {
            requestedCodes.add(assignment.blockCode());
        }

        blocks.removeIf(block ->
                !requestedCodes.contains(block.getBlockCode())
        );

        for (BlockAssignment assignment : assignments) {
            ActionBlock existing = existingByCode.get(assignment.blockCode());
            if (existing == null) {
                blocks.add(new ActionBlock(
                        this,
                        assignment.blockCode(),
                        assignment.displayOrder()
                ));
            } else {
                existing.changeDisplayOrder(assignment.displayOrder());
            }
        }
    }

    public void delete() {
        deletedAt = LocalDateTime.now();
    }

    public record BlockAssignment(
            BlockCode blockCode,
            int displayOrder
    ) {
    }
}
