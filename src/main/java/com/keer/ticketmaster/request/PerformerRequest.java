package com.keer.ticketmaster.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PerformerRequest {
    private String name;
    private String description;
}
