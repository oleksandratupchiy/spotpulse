package com.spotpulse.images;

import com.spotpulse.config.LocalDatabaseEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@Component
public class LocalImageStorage implements ImageStorage {
    public static final String PUBLIC_PREFIX = "/api/spaces/images/";
    private static final String KEY_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)";
    private final Path directory;

    public LocalImageStorage(Environment environment) {
        String configured = environment.getProperty("spotpulse.images.directory", "");
        Path candidate = configured.isBlank() ? LocalDatabaseEnvironment.dataDirectory(environment).resolve("images") : Path.of(configured).normalize();
        if (!candidate.isAbsolute()) throw new IllegalStateException("Image directory must be absolute");
        try {
            Path ancestor = candidate;
            while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
            if (ancestor == null) throw new IllegalStateException("Invalid image directory");
            directory = ancestor.toRealPath().resolve(ancestor.relativize(candidate)).normalize();
        } catch (IOException ignored) { throw new IllegalStateException("Cannot resolve image directory"); }
        Path checkout = Path.of(environment.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        while (checkout.getParent() != null && !Files.exists(checkout.resolve(".git"))) checkout = checkout.getParent();
        if (directory.startsWith(checkout)) throw new IllegalStateException("Image directory must be outside the checkout");
        // Creation is lazy: constructing a context never touches the real image directory.
    }

    @Override public Stored store(ImageValidation.Validated image) {
        String key = UUID.randomUUID() + "." + image.extension();
        Path target = path(key);
        boolean created = false;
        try {
            Files.createDirectories(directory);
            // CREATE_NEW plus generated names cannot overwrite an unrelated file or follow a target symlink.
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                output.write(image.bytes());
            }
            return new Stored(PUBLIC_PREFIX + key, key);
        } catch (IOException ignored) {
            if (created) try { Files.deleteIfExists(target); } catch (IOException cleanup) { /* no path-bearing errors */ }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is unavailable. Please retry.");
        }
    }

    @Override public Content read(String key) {
        Path target = path(key);
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > ImageValidation.MAX_BYTES)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
            // NOFOLLOW_LINKS protects against replacing a regular file with a symlink between checks.
            try (var stream = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = stream.readNBytes(ImageValidation.MAX_BYTES + 1);
                if (bytes.length > ImageValidation.MAX_BYTES) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
                return new Content(bytes, key.endsWith(".jpg") ? "image/jpeg" : "image/png");
            }
        } catch (IOException ignored) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"); }
    }

    @Override public void discard(Stored created) {
        try { Files.deleteIfExists(path(created.key())); }
        catch (IOException ignored) { /* retain an orphan rather than risk other files; no sensitive path logging */ }
    }

    private Path path(String key) {
        if (key == null || !key.matches(KEY_PATTERN)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
        return directory.resolve(key);
    }
}
