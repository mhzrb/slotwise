package com.slotwise.appointment.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID>,
        JpaSpecificationExecutor<Appointment> {

    /** Sentinel for "exclude nothing", avoiding untyped NULL parameters in SQL. */
    UUID NO_EXCLUSION = new UUID(0L, 0L);

    /** Active appointments whose occupied range [startAt, blockedUntil) overlaps [from, to). */
    @Query("""
            select a from Appointment a
            where a.provider.id = :providerId
              and a.status in :statuses
              and a.startAt < :to and a.blockedUntil > :from
              and a.id <> :excludeId
            order by a.startAt
            """)
    List<Appointment> findOverlapping(@Param("providerId") UUID providerId, @Param("from") Instant from,
            @Param("to") Instant to, @Param("statuses") Collection<AppointmentStatus> statuses,
            @Param("excludeId") UUID excludeId);

    @EntityGraph(attributePaths = {"provider", "customer", "service"})
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> findDetailedById(@Param("id") UUID id);

    @Override
    @EntityGraph(attributePaths = {"provider", "customer", "service"})
    Page<Appointment> findAll(Specification<Appointment> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "service"})
    List<Appointment> findByProviderIdAndStartAtGreaterThanEqualAndStartAtLessThanAndStatusInOrderByStartAtAsc(
            UUID providerId, Instant from, Instant to, Collection<AppointmentStatus> statuses);
}
