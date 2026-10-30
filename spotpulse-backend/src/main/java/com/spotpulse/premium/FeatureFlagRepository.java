package com.spotpulse.premium;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, FeatureKey> { }
