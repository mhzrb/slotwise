package com.slotwise.provider.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProviderProfileRepository extends JpaRepository<ProviderProfile, UUID> {

    Optional<ProviderProfile> findByUserId(UUID userId);

    /**
     * {@code SELECT ... FOR UPDATE} on the provider row. Used as the
     * serialisation point for every operation that changes a provider's
     * calendar (booking, rescheduling), so availability checks and inserts
     * for one provider happen one transaction at a time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProviderProfile p where p.id = :id")
    Optional<ProviderProfile> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select p from ProviderProfile p join p.user u
            where p.active = true and u.enabled = true
              and (:pattern is null or lower(p.businessName) like :pattern escape '\\')
            """)
    Page<ProviderProfile> searchActive(@Param("pattern") String pattern, Pageable pageable);
}
