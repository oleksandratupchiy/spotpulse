package com.spotpulse.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

@RestController @RequiredArgsConstructor
public class HealthController {
    private final DataSource dataSource;
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            try (var result = statement.executeQuery("SELECT 1")) {
                if (result.next() && result.getInt(1) == 1) return status(HttpStatus.OK, "UP");
            }
        } catch (SQLException ignored) { /* no JDBC credentials, SQL causes or schema details in public output */ }
        return status(HttpStatus.SERVICE_UNAVAILABLE, "DOWN");
    }
    private ResponseEntity<Map<String, String>> status(HttpStatus status, String value) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("status", value));
    }
}
