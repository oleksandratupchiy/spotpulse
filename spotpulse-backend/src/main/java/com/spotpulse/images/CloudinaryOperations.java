package com.spotpulse.images;

import java.io.IOException;
import java.util.Map;

/** Narrow SDK boundary for offline tests. Only normalized bytes and server-generated IDs are accepted. */
interface CloudinaryOperations {
    Map<?, ?> upload(byte[] bytes, String publicId, String format) throws IOException;
    void destroy(String publicId) throws IOException;
}
