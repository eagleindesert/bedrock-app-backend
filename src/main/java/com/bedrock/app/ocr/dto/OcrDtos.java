package com.bedrock.app.ocr.dto;

import com.bedrock.app.ai.dto.AiDtos;
import java.time.LocalTime;
import java.util.List;

public final class OcrDtos {
    private OcrDtos() {}
    public record Recognition(List<Item> items, List<AiDtos.Warning> warnings, String engine) {}
    public record Item(String candidateId, String name, String location, List<Slot> slots) {}
    public record Slot(Integer dayOfWeek, LocalTime startTime, LocalTime endTime) {}
    public record ModelResult(List<Item> items, List<AiDtos.Warning> warnings) {}
}
