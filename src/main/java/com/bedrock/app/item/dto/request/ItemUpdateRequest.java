package com.bedrock.app.item.dto.request;

import lombok.Getter;

import java.util.Map;

@Getter
public class ItemUpdateRequest {

    private String name;

    private Map<String, Object> attributes;
}
