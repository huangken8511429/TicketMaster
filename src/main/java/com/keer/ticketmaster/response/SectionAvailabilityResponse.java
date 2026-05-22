package com.keer.ticketmaster.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-section availability snapshot for a given event.
 *
 * Used by:
 * - {@code GET /api/events/{id}/sections} — initial fetch
 * - {@code GET /api/events/{id}/sections/stream} — SSE bridge after sub-partition aggregation
 *
 * status enum:
 * - NOT_STARTED — now < event.salesStartAt
 * - ON_SALE_PLENTY — availableRatio > 0.30
 * - ON_SALE_LIMITED — 0.05 < availableRatio <= 0.30
 * - ON_SALE_FEW — 0 < availableRatio <= 0.05
 * - SOLD_OUT — availableCount == 0
 *
 * See specs/frontend-mvp/api-contract.md §4.1
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SectionAvailabilityResponse {

    private Long eventId;

    /** Section name, e.g. "A", "VIP". */
    private String section;

    /** rows × cols. */
    private int totalSeats;

    /** Sum of available seats across all sub-partitions. */
    private int availableCount;

    /** Derived by backend; do not let frontend compute thresholds. */
    private String status;

    /** Optional fixed price per seat (TWD). Null = price not configured. */
    private Long basePrice;
}
