package com.spotpulse.images;

/** C19 boundary: only the adapter knows where bytes live. References are stable public URLs. */
public interface ImageStorage {
    record Stored(String reference, String key) { }
    record Content(byte[] bytes, String mediaType) { }
    Stored store(ImageValidation.Validated image);
    Content read(String key);
    /** Only for compensating a failed transaction's newly created object. */
    void discard(Stored created);
}
