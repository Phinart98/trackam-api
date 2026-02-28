package com.trackam.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String operation; // "parse-text" | "parse-image" | "advisor"

    private String aiProvider; // "groq" | "gemini"
    private String model;
    private Integer inputTokens;
    private Integer outputTokens;

    @Column(precision = 8, scale = 6)
    private BigDecimal costUsd;

    private Integer latencyMs;

    @Column(nullable = false)
    private boolean success;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
