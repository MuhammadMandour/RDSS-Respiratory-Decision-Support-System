package com.rdss.hl7;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface Hl7MessageRepository extends JpaRepository<Hl7Message, Long> {

    @Query("""
            select m from Hl7Message m
            where m.direction = com.rdss.hl7.Hl7Message.Direction.INBOUND
              and m.sendingFacility = :facility and m.sendingApplication = :application and m.controlId = :controlId
            """)
    Optional<Hl7Message> findInbound(@Param("facility") String facility, @Param("application") String application,
                                     @Param("controlId") String controlId);

    @Query("""
            select m from Hl7Message m
            where m.retryable = true and m.status in :statuses and m.nextAttemptAt <= :now
            order by m.nextAttemptAt
            """)
    List<Hl7Message> findDueForRetry(@Param("statuses") List<Hl7Message.Status> statuses, @Param("now") Instant now,
                                     Pageable pageable);

    @Query("""
            select m from Hl7Message m
            where (:direction is null or m.direction = :direction)
              and (:status is null or m.status = :status)
              and (:type is null or m.messageType like :type)
            """)
    Page<Hl7Message> search(@Param("direction") Hl7Message.Direction direction,
                            @Param("status") Hl7Message.Status status, @Param("type") String typePrefix,
                            Pageable pageable);
}
