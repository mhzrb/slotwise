package com.slotwise.catalog.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

public interface ServiceOfferingRepository extends JpaRepository<ServiceOffering, UUID>,
        JpaSpecificationExecutor<ServiceOffering> {

    List<ServiceOffering> findByProviderIdOrderByNameAsc(UUID providerId);

    List<ServiceOffering> findByProviderIdAndActiveTrueOrderByNameAsc(UUID providerId);

    boolean existsByProviderIdAndNameIgnoreCase(UUID providerId, String name);

    boolean existsByProviderIdAndNameIgnoreCaseAndIdNot(UUID providerId, String name, UUID id);

    /** Fetches the provider together with each row to avoid N+1 queries in search results. */
    @Override
    @EntityGraph(attributePaths = "provider")
    Page<ServiceOffering> findAll(Specification<ServiceOffering> spec, Pageable pageable);
}
