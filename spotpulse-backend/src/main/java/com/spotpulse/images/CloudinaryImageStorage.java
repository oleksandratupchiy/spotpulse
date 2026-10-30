package com.spotpulse.images;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Uploads only; public images go directly to the CDN. Never derives deletion authority from a URL. */
public final class CloudinaryImageStorage implements ImageStorage {
    static final String PREFIX = "spotpulse/spaces/";
    private static final Logger log = LoggerFactory.getLogger(CloudinaryImageStorage.class);
    private final String cloudName;
    private final CloudinaryOperations operations;
    private final Semaphore slots = new Semaphore(8);
    private final Set<Stored> pending = Collections.newSetFromMap(new IdentityHashMap<>());

    CloudinaryImageStorage(CloudinarySettings settings, CloudinaryOperations operations) {
        this.cloudName = settings.cloudName; this.operations = operations;
    }
    @Override public Stored store(ImageValidation.Validated image) {
        if (image.bytes().length == 0 || image.bytes().length > ImageValidation.MAX_BYTES
                || !(image.extension().equals("jpg") || image.extension().equals("png")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid normalized image");
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cloud image provider is busy. Please retry.");
        String publicId = PREFIX + UUID.randomUUID();
        try {
            Map<?, ?> response;
            try { response = operations.upload(image.bytes(), publicId, image.extension()); }
            catch (Exception failure) { throw uploadFailure(failure); }
            if (response == null || response.get("error") != null || (response.get("existing") != null && !Boolean.FALSE.equals(response.get("existing")))
                    || !publicId.equals(response.get("public_id")) || !"image".equals(response.get("resource_type"))
                    || !"upload".equals(response.get("type")) || !image.extension().equals(response.get("format"))) throw invalidResponse();
            String assetId = response.get("asset_id") instanceof String id ? id : "";
            if (!assetId.matches("[A-Za-z0-9_-]{16,128}")) throw invalidResponse();
            long version = positive(response.get("version")), bytes = positive(response.get("bytes"));
            long width = positive(response.get("width")), height = positive(response.get("height"));
            if (bytes > ImageValidation.MAX_BYTES || width > ImageValidation.MAX_SIDE || height > ImageValidation.MAX_SIDE
                    || width * height > ImageValidation.MAX_PIXELS) throw invalidResponse();
            String expected = "https://res.cloudinary.com/" + cloudName + "/image/upload/v" + version + "/" + publicId + "." + image.extension();
            String secureUrl = response.get("secure_url") instanceof String url ? url : "";
            if (secureUrl.length() > 255 || !expected.equals(secureUrl)) throw invalidResponse();
            URI uri = URI.create(secureUrl);
            if (!"https".equals(uri.getScheme()) || !"res.cloudinary.com".equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw invalidResponse();
            var receipt = new Stored(secureUrl, publicId, "cloudinary", assetId);
            synchronized (pending) { pending.add(receipt); }
            return receipt;
        } catch (RuntimeException failure) {
            slots.release();
            // No deletion on ambiguous response or timeout: it might identify an existing/unrelated asset.
            if (failure instanceof ResponseStatusException safe) throw safe;
            throw invalidResponse();
        }
    }
    @Override public Content read(String key) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"); }
    @Override public void committed(Stored created) { if (release(created)) slots.release(); }
    @Override public void discard(Stored created) {
        if (!release(created)) return; // only the exact server-issued pending receipt may authorize rollback deletion
        try { operations.destroy(created.key()); }
        catch (Exception ignored) { log.warn("Cloud image rollback cleanup failed; asset retained for manual reconciliation"); }
        finally { slots.release(); }
    }
    private boolean release(Stored receipt) { synchronized (pending) { return pending.remove(receipt); } }
    private static long positive(Object value) {
        if (!(value instanceof Number)) throw invalidResponse();
        try { long number = Long.parseLong(value.toString()); if (number > 0) return number; }
        catch (NumberFormatException ignored) { }
        throw invalidResponse();
    }
    private static ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Cloud image provider returned an invalid response. Please retry.");
    }
    private static ResponseStatusException uploadFailure(Exception failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 5; depth++, current = current.getCause())
            if (current instanceof InterruptedIOException || current instanceof HttpTimeoutException)
                return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Cloud image upload timed out. Please retry.");
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Cloud image upload failed. Please retry.");
    }
}
