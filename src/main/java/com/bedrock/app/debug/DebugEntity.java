package com.bedrock.app.debug;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "debug_test")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebugEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String message;
    private LocalDateTime createdAt;
}
