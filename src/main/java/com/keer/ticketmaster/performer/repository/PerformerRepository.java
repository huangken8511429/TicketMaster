package com.keer.ticketmaster.performer.repository;

import com.keer.ticketmaster.performer.model.Performer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PerformerRepository extends JpaRepository<Performer, Long> {
}
