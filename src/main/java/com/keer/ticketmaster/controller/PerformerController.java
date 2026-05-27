package com.keer.ticketmaster.controller;

import com.keer.ticketmaster.request.PerformerRequest;
import com.keer.ticketmaster.response.PerformerResponse;
import com.keer.ticketmaster.service.PerformerService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/performers")
@Profile({"api", "default"})
@RequiredArgsConstructor
public class PerformerController {

    private final PerformerService performerService;

    @PostMapping
    public ResponseEntity<PerformerResponse> createPerformer(@RequestBody PerformerRequest request) {
        PerformerResponse response = performerService.createPerformer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PerformerResponse> getPerformer(@PathVariable Long id) {
        PerformerResponse response = performerService.getPerformer(id);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<PerformerResponse>> getAllPerformers() {
        List<PerformerResponse> response = performerService.getAllPerformers();
        return ResponseEntity.ok(response);
    }
}
