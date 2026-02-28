package com.trackam.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "business_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusinessProfile {

    @Id
    private String id; // FK to Supabase auth.users — same UUID

    private String businessName;
    private String ownerName;
    private String businessType;

    @Column(nullable = false)
    private String currency;

    private String country;

    @Column(precision = 19, scale = 4)
    private BigDecimal monthlyBudget;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
