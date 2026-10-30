package com.spotpulse.premium;

/** Explicit registry. New keys require a reviewed server change. All defaults fail closed. */
public enum FeatureKey {
    PREMIUM_FEATURES_ENABLED("Master switch for all premium functionality"),
    FEATURED_SPACES_ENABLED("Place entitled spaces first in discovery"),
    ADVANCED_ANALYTICS_ENABLED("Allow entitled spaces to use room performance analytics"),
    PREMIUM_BADGES_ENABLED("Show premium indicators on entitled spaces");

    public final String description;
    FeatureKey(String description) { this.description = description; }
}
