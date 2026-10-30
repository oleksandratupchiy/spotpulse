package com.spotpulse.images;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.util.*;

@Component
public class ImageValidation {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_SIDE = 8192;
    public static final long MAX_PIXELS = 12_000_000;
    public record Validated(byte[] bytes, String extension) { }

    public Validated validate(MultipartFile file) {
        if (file.isEmpty()) throw invalid("Select a non-empty image");
        if (file.getSize() > MAX_BYTES) throw tooLarge();
        // Filenames never become paths, but reject path-bearing input rather than silently accepting it.
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "");
        if (name.contains("/") || name.contains("\\") || name.contains(":") || name.codePoints().anyMatch(Character::isISOControl))
            throw invalid("Invalid image filename");
        try (var stream = file.getInputStream()) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw tooLarge();
            String type = detect(bytes);
            if (type == null || !type.equals(file.getContentType()))
                throw invalid("Image content must match its JPEG, PNG or WebP MIME type");
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid("Unsupported or corrupt image");
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS)
                        throw invalid("Image must be at most 8192 pixels per side and 12 megapixels");
                    var decoded = reader.read(0);
                    if (decoded == null) throw invalid("Unsupported or corrupt image");
                    try {
                        // Strip metadata, extra frames and appended content. Never serve original uploads.
                        String extension = type.equals("image/jpeg") ? "jpg" : "png";
                        var output = new BoundedOutput();
                        if (!ImageIO.write(decoded, extension, output)) throw invalid("Unable to normalize image");
                        return new Validated(output.toByteArray(), extension);
                    } finally { decoded.flush(); }
                } finally { reader.dispose(); }
            }
        } catch (ResponseStatusException safe) { throw safe;
        } catch (IOException | RuntimeException ignored) { throw invalid("Unsupported or corrupt image"); }
    }

    private static String detect(byte[] bytes) {
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "image/jpeg";
        if (bytes.length >= 12 && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        return null;
    }
    private static class BoundedOutput extends ByteArrayOutputStream {
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if ((long) count + length > MAX_BYTES) throw tooLarge();
            super.write(bytes, offset, length);
        }
        @Override public synchronized void write(int value) {
            if (count >= MAX_BYTES) throw tooLarge();
            super.write(value);
        }
    }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException tooLarge() { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image must be at most 8 MiB, including after normalization"); }
}
