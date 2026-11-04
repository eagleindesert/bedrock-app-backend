package com.bedrock.app.common.response;

import java.util.List;

public record CursorPageResponse<T>(List<T> items, String nextCursor, boolean hasMore) {
    public CursorPageResponse {
        items = List.copyOf(items);
        if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
            throw new IllegalArgumentException("다음 페이지가 있으면 커서가 필요합니다.");
        }
    }
}
