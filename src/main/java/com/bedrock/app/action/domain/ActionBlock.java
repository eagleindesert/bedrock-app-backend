package com.bedrock.app.action.domain;

import com.bedrock.app.action.domain.type.BlockCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(
        name = "action_steps",
        indexes = @Index(
                name = "idx_action_steps_action",
                columnList = "action_id, display_order"
        ),
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_action_steps_action_block",
                        columnNames = {"action_id", "block_code"}
                ),
                @UniqueConstraint(
                        name = "uk_action_steps_action_display_order",
                        columnNames = {"action_id", "display_order"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "action_id", nullable = false)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_code", nullable = false, length = 40)
    private BlockCode blockCode;

    @Column(name = "display_order")
    private int displayOrder;

    ActionBlock(Action action, BlockCode blockCode, int displayOrder) {
        this.action = action;
        this.blockCode = blockCode;
        this.displayOrder = displayOrder;
    }

    void changeDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}
