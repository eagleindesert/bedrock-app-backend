package com.bedrock.app.ai.service;

import com.bedrock.app.ai.dto.AiDtos;

import java.util.Locale;
import java.util.Set;

/** 서버가 추가한 확인 질문은 명시적인 답에만 승인한다. */
public final class AiConfirmations {
    private static final Set<String> DELETE = Set.of("네", "예", "삭제", "삭제해 주세요", "확인", "yes", "confirm");
    private static final Set<String> KEEP = Set.of("네", "예", "유지", "원래 날짜 유지", "그대로", "yes", "keep");
    private static final Set<String> CANCEL = Set.of("취소", "아니요", "삭제 취소", "삭제하지 마", "no", "cancel");

    private AiConfirmations() {}

    public static boolean deleteConfirmed(AiDtos.Answer answer) { return accepts(answer, "confirm", DELETE); }
    public static boolean pastKept(AiDtos.Answer answer) { return accepts(answer, "keep", KEEP); }
    public static boolean deletionCancelled(AiDtos.Answer answer) {
        return answer.questionId().startsWith("confirm_delete_")
                && ("cancel".equals(answer.choiceId()) || CANCEL.contains(normalizedText(answer)));
    }

    private static boolean accepts(AiDtos.Answer answer, String choice, Set<String> texts) {
        String text = normalizedText(answer);
        // 선택과 자유 입력을 함께 보냈을 때 서로 모순되는 승인은 받지 않는다.
        if (answer.choiceId() != null) return choice.equals(answer.choiceId()) && (text.isEmpty() || texts.contains(text));
        return texts.contains(text);
    }

    private static String normalizedText(AiDtos.Answer answer) {
        return answer.text() == null ? "" : answer.text().strip().toLowerCase(Locale.ROOT);
    }
}
