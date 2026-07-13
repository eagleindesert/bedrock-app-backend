package com.bedrock.app.action.dto;

import com.bedrock.app.action.domain.type.BlockCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ActionRequests {

    private ActionRequests() {
    }

    public record Block(
            @NotNull BlockCode blockCode,
            @Min(0) Integer displayOrder
    ) {
    }

    public record Create(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 1000) String description,
            Boolean enabled,
            Boolean preset,
            @NotEmpty List<@Valid Block> blocks
    ) {
    }

    public record Update(
            @Size(min = 1, max = 100) String name,
            @Size(max = 1000) String description,
            Boolean enabled,
            Boolean preset,
            List<@Valid Block> blocks
    ) {
    }

    public record Execute(
            @NotNull UUID targetCollectionId,
            Map<String, Object> prefill,
            Map<String, Object> input
    ) {
    }
}
