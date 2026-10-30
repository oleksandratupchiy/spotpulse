package com.spotpulse.premium;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "feature_flags")
@Getter @Setter @NoArgsConstructor
public class FeatureFlag {
    @Id @Enumerated(EnumType.STRING) @Column(name = "flag_key", length = 64)
    private FeatureKey key;
    @Column(nullable = false)
    private boolean enabled;
    public FeatureFlag(FeatureKey key, boolean enabled) { this.key = key; this.enabled = enabled; }
}
