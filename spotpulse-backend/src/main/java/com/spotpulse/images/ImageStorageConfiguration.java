package com.spotpulse.images;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class ImageStorageConfiguration {
    @Bean @Primary
    public ImageStorage imageStorage(Environment environment, LocalImageStorage local, ObjectProvider<CloudinaryOperations> operations) {
        String provider = environment.getProperty("spotpulse.images.storage-provider",
                environment.getProperty("SPOTPULSE_IMAGE_STORAGE", "local"));
        return switch (provider) {
            case "local" -> local;
            case "cloudinary" -> {
                var settings = CloudinarySettings.read(environment);
                yield new CloudinaryImageStorage(settings, operations.getIfAvailable(() -> new CloudinarySdkOperations(settings)));
            }
            default -> throw new IllegalStateException("SPOTPULSE_IMAGE_STORAGE must be local or cloudinary");
        };
    }
}
