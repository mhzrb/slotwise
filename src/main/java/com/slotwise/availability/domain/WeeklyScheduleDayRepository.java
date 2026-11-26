package com.slotwise.availability.domain;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WeeklyScheduleDayRepository extends JpaRepository<WeeklyScheduleDay, UUID> {

    @EntityGraph(attributePaths = "breaks")
    List<WeeklyScheduleDay> findByProviderIdOrderByDayOfWeekAsc(UUID providerId);

    /**
     * Bulk delete, executed immediately. (Removing entities one by one and
     * inserting replacements would trip the (provider_id, day_of_week) unique
     * constraint, because Hibernate flushes inserts before deletes.)
     * Breaks are removed by the ON DELETE CASCADE foreign key.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from WeeklyScheduleDay d where d.provider.id = :providerId")
    int deleteByProviderId(@Param("providerId") UUID providerId);
}
