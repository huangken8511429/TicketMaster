package com.keer.ticketmaster.performer.service;

import com.keer.ticketmaster.performer.dto.PerformerRequest;
import com.keer.ticketmaster.performer.dto.PerformerResponse;
import com.keer.ticketmaster.performer.model.Performer;
import com.keer.ticketmaster.performer.repository.PerformerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
public class PerformerService {

    private final PerformerRepository performerRepository;

    @Transactional
    public PerformerResponse createPerformer(PerformerRequest request) {
        Performer performer = new Performer();
        performer.setName(request.getName());
        performer.setDescription(request.getDescription());
        Performer saved = performerRepository.save(performer);
        return toResponse(saved);
    }

    public PerformerResponse getPerformer(Long id) {
        return performerRepository.findById(id)
                .map(this::toResponse)
                .orElse(null);
    }

    public List<PerformerResponse> getAllPerformers() {
        return performerRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    private PerformerResponse toResponse(Performer performer) {
        return PerformerResponse.builder()
                .id(performer.getId())
                .name(performer.getName())
                .description(performer.getDescription())
                .build();
    }
}
