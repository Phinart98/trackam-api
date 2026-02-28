package com.trackam.controller;

import com.trackam.dto.ProfileRequest;
import com.trackam.model.BusinessProfile;
import com.trackam.repository.BusinessProfileRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final BusinessProfileRepository profileRepo;

    @GetMapping
    public ResponseEntity<BusinessProfile> getProfile(@AuthenticationPrincipal Jwt jwt) {
        return profileRepo.findById(jwt.getSubject())
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<BusinessProfile> upsertProfile(
            @RequestBody @Valid ProfileRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        BusinessProfile profile = BusinessProfile.builder()
            .id(userId)
            .businessName(req.businessName())
            .ownerName(req.ownerName())
            .businessType(req.businessType())
            .currency(req.currency())
            .country(req.country())
            .monthlyBudget(req.monthlyBudget())
            .build();
        return ResponseEntity.ok(profileRepo.save(profile));
    }
}
