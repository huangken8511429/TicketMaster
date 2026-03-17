package com.keer.ticketmaster.service;

import com.keer.ticketmaster.request.PerformerRequest;
import com.keer.ticketmaster.response.PerformerResponse;
import com.keer.ticketmaster.po.Performer;
import com.keer.ticketmaster.repository.PerformerRepository;
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
