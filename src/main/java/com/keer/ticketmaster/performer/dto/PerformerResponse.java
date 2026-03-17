package com.keer.ticketmaster.performer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PerformerResponse {
    private Long id;
    private String name;
    private String description;
}
