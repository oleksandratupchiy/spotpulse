package com.spotpulse.images;

import org.springframework.core.env.Environment;

/** Backend-only credentials. Deliberately not a record and has no value-bearing toString. */
final class CloudinarySettings {
    final String cloudName;
    final String apiKey;
    final String apiSecret;
    private CloudinarySettings(String cloudName, String apiKey, String apiSecret) {
        this.cloudName = cloudName; this.apiKey = apiKey; this.apiSecret = apiSecret;
    }
    static CloudinarySettings read(Environment environment) {
        String cloud = environment.getProperty("CLOUDINARY_CLOUD_NAME", "");
        String key = environment.getProperty("CLOUDINARY_API_KEY", "");
        String secret = environment.getProperty("CLOUDINARY_API_SECRET", "");
        if (!cloud.matches("[a-z0-9][a-z0-9_-]{0,127}") || !key.matches("[0-9]{5,64}")
                || secret.length() < 8 || secret.length() > 256 || secret.chars().anyMatch(c -> c <= 32 || c >= 127))
            throw new IllegalStateException("Cloudinary storage requires valid CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY and CLOUDINARY_API_SECRET backend environment variables");
        return new CloudinarySettings(cloud, key, secret);
    }
    @Override public String toString() { return "CloudinarySettings[redacted]"; }
}
