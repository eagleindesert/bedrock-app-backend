package com.bedrock.app.collection.dto.request;

import lombok.Getter;

import java.util.Map;

@Getter
public class CollectionUpdateRequest {

    private String name;

    private String color;

    private String icon;

    private Map<String, Object> attributes;
}
