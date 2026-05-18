package com.bedrock.app.debug;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DebugService {

    private final DebugRepository debugRepository;

    @Transactional
    public DebugEntity saveMessage(String message) {
        DebugEntity entity = DebugEntity.builder()
                .message(message)
                .createdAt(LocalDateTime.now())
                .build();
        return debugRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<DebugEntity> getAllMessages() {
        return debugRepository.findAll();
    }
}
