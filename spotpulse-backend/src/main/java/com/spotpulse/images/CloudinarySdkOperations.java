package com.spotpulse.images;

import com.cloudinary.Cloudinary;
import java.io.IOException;
import java.util.Map;

/** Official SDK handles request construction and signing. Never pass a URL or original filename as a file. */
final class CloudinarySdkOperations implements CloudinaryOperations {
    static final int TIMEOUT_SECONDS = 15;
    private final Cloudinary client;
    CloudinarySdkOperations(CloudinarySettings settings) { this(new Cloudinary(configuration(settings))); }
    CloudinarySdkOperations(Cloudinary client) { this.client = client; }
    static Map<String, Object> configuration(CloudinarySettings settings) {
        return Map.of("cloud_name", settings.cloudName, "api_key", settings.apiKey, "api_secret", settings.apiSecret,
                "secure", true, "upload_prefix", "https://api.cloudinary.com", "timeout", TIMEOUT_SECONDS);
    }
    @Override public Map<?, ?> upload(byte[] bytes, String publicId, String format) throws IOException {
        return client.uploader().upload(bytes, Map.of("public_id", publicId, "resource_type", "image", "type", "upload",
                "overwrite", false, "use_filename", false, "unique_filename", false, "filename", "image." + format));
    }
    @Override public void destroy(String publicId) throws IOException {
        var response = client.uploader().destroy(publicId, Map.of("resource_type", "image", "type", "upload", "invalidate", true));
        if (response == null || !("ok".equals(response.get("result")) || "not found".equals(response.get("result"))))
            throw new IOException("Cloud image cleanup was not confirmed");
    }
}
