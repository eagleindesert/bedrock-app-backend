package com.bedrock.app.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ItemFieldVersionId implements Serializable {
    private static final long serialVersionUID = 1L;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    /** API 칸 키(START_AT · END_AT · EXCEPTION:YYYY-MM-DD:slotId 등) */
    @Column(name = "field_key", nullable = false, length = 100)
    private String fieldKey;
}
