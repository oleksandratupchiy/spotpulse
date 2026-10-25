package com.spotpulse.seo;

import com.spotpulse.dto.SpaceDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SpaceSeo {
    private final String publicBaseUrl;
    private final boolean indexable;
    private final ObjectMapper json;

    public SpaceSeo(@Value("${spotpulse.seo.public-base-url:}") String baseUrl,
                    @Value("${spotpulse.seo.indexable:true}") boolean indexable, ObjectMapper json) {
        String base = baseUrl.strip().replaceAll("/+$", "");
        if (!base.isEmpty()) {
            URI uri = URI.create(base);
            if (!isHttp(uri) || uri.getHost() == null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("spotpulse.seo.public-base-url must be an absolute HTTP(S) URL without credentials, query or fragment");
            }
        }
        this.publicBaseUrl = base;
        this.indexable = indexable && !base.isEmpty();
        this.json = json;
    }

    public boolean isIndexable() { return indexable; }

    public String publicUrl(String path) {
        return publicBaseUrl.isEmpty() ? null : publicBaseUrl + path;
    }

    public String publicPath(String path) {
        return URI.create(publicBaseUrl).getRawPath() + path;
    }

    private static boolean isHttp(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
    }

    private String imageUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.strip());
            if (isHttp(uri) && uri.getHost() != null && uri.getUserInfo() == null) return uri.toASCIIString();
            if (uri.getScheme() == null && value.startsWith("/") && !value.startsWith("//")) {
                return publicBaseUrl.isEmpty() ? value : publicBaseUrl + value;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid legacy image URLs use the same placeholder as missing images.
        }
        return null;
    }

    public Metadata metadata(SpaceDto space) {
        String title = text(space.getName()) + " — " + text(space.getCity()) + " | SpotPulse";
        String description = text(space.getName()) + ", " + text(space.getCity()) + ". " + text(space.getAddress()) + ".";
        if (space.getDescription() != null && !space.getDescription().isBlank()) {
            description += " " + text(space.getDescription());
        }
        if (description.codePointCount(0, description.length()) > 160) {
            description = description.substring(0, description.offsetByCodePoints(0, 157)).stripTrailing() + "…";
        }
        String canonical = publicUrl("/spaces/" + space.getId());
        String image = imageUrl(space.getImageUrl());
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("@context", "https://schema.org");
        place.put("@type", "Place");
        place.put("name", space.getName());
        place.put("address", Map.of("@type", "PostalAddress", "streetAddress", space.getAddress(), "addressLocality", space.getCity()));
        if (space.getDescription() != null && !space.getDescription().isBlank()) place.put("description", space.getDescription());
        if (canonical != null) place.put("url", canonical);
        if (image != null && URI.create(image).isAbsolute()) place.put("image", image);
        // Escaping '<' prevents user-provided text from terminating the JSON-LD script element.
        String structuredData = json.writeValueAsString(place).replace("<", "\\u003c")
                .replace(">", "\\u003e").replace("&", "\\u0026");
        return new Metadata(title, description, canonical, image, indexable ? "index, follow" : "noindex, follow", structuredData);
    }

    private static String text(String value) {
        return value == null ? "" : value.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
    }

    public record Metadata(String title, String description, String canonicalUrl, String imageUrl,
                           String robots, String structuredData) {}
}
