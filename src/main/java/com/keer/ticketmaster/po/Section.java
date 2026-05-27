package com.keer.ticketmaster.po;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "section")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Section {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private int rows;

    private int cols;

    private int availableSeats;

    /**
     * Base price for all seats in this section (TWD, nullable).
     * Phase 1 frontend MVP: simplest pricing model = fixed price per section.
     * Future: support price tiers per row, dynamic pricing, etc.
     */
    private Long basePrice;
}
