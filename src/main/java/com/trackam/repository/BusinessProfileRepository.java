package com.trackam.repository;

import com.trackam.model.BusinessProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BusinessProfileRepository extends JpaRepository<BusinessProfile, UUID> {}
