package com.spotpulse.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/** Explicit SPA routes preserve /spaces Thymeleaf/SEO and never catch API/OAuth/static URLs. */
@Controller
public class SpaController {
    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.FOUND).location(java.net.URI.create("/app/spaces")).cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping({"/app", "/app/", "/app/spaces", "/app/spaces/{id:[0-9]+}", "/app/rooms/{id:[0-9]+}",
            "/app/login", "/app/account", "/app/bookings", "/app/owner", "/app/owner/spaces/new",
            "/app/owner/spaces/{id:[0-9]+}", "/app/admin", "/app/analytics"})
    public ResponseEntity<ClassPathResource> app() {
        var index = new ClassPathResource("static/app/index.html");
        if (!index.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Build the production frontend or use Vite for local development");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex").body(index);
    }
}
