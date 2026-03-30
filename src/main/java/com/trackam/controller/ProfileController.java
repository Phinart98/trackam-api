package com.trackam.controller;

import com.trackam.dto.ProfileRequest;
import com.trackam.model.BusinessProfile;
import com.trackam.repository.BusinessProfileRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final BusinessProfileRepository profileRepo;

    @GetMapping
    public ResponseEntity<BusinessProfile> getProfile(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return profileRepo.findById(userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<BusinessProfile> upsertProfile(
            @RequestBody @Valid ProfileRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        boolean exists = profileRepo.existsById(userId);
        BusinessProfile profile = BusinessProfile.builder()
            .id(userId)
            .businessName(req.businessName())
            .ownerName(req.ownerName())
            .businessType(req.businessType())
            .currency(req.currency())
            .country(req.country())
            .monthlyBudget(req.monthlyBudget())
            .build();
        BusinessProfile saved = profileRepo.save(profile);
        return exists
            ? ResponseEntity.ok(saved)
            : ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}
