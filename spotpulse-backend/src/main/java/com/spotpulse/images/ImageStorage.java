package com.spotpulse.images;

/** C19 boundary: only the adapter knows where bytes live. References are stable public URLs. */
public interface ImageStorage {
    record Stored(String reference, String key, String provider, String assetId) {
        public Stored(String reference, String key) { this(reference, key, "local", null); }
    }
    record Content(byte[] bytes, String mediaType) { }
    Stored store(ImageValidation.Validated image);
    Content read(String key);
    /** Only for compensating a failed transaction's newly created object. */
    void discard(Stored created);
    /** Release an upload receipt after commit/unknown outcome; never delete a potentially committed asset. */
    default void committed(Stored created) { }
}
