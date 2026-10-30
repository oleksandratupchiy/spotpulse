package com.spotpulse.images;

import com.spotpulse.dto.SpaceDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.Duration;

@RestController @RequestMapping("/api/spaces") @RequiredArgsConstructor
public class SpaceImageController {
    private final SpaceImageService images;
    // Read legacy local keys regardless of the provider selected for new uploads.
    private final LocalImageStorage localImages;

    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SpaceDto> upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.attach(id, file));
    }

    @GetMapping("/images/{key}")
    public ResponseEntity<byte[]> image(@PathVariable String key) {
        var content = localImages.read(key);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mediaType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(content.bytes());
    }
}
