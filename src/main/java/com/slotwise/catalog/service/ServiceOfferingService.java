package com.slotwise.catalog.service;

import com.slotwise.catalog.domain.ServiceOffering;
import com.slotwise.catalog.domain.ServiceOfferingRepository;
import com.slotwise.catalog.domain.ServiceOfferingSpecifications;
import com.slotwise.common.exception.ConflictException;
import com.slotwise.common.exception.ForbiddenException;
import com.slotwise.common.exception.NotFoundException;
import com.slotwise.provider.domain.ProviderProfile;
import com.slotwise.provider.service.ProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ServiceOfferingService {

    private static final Logger log = LoggerFactory.getLogger(ServiceOfferingService.class);

    private final ServiceOfferingRepository repository;
    private final ProviderService providerService;

    public ServiceOfferingService(ServiceOfferingRepository repository, ProviderService providerService) {
        this.repository = repository;
        this.providerService = providerService;
    }

    public record ServiceDetails(String name, String description, int durationMinutes, int bufferMinutes,
            BigDecimal price, Boolean active) {
    }

    @Transactional
    public ServiceOffering create(UUID providerUserId, ServiceDetails details) {
        ProviderProfile provider = providerService.getByUserId(providerUserId);
        String name = details.name().trim();
        if (repository.existsByProviderIdAndNameIgnoreCase(provider.getId(), name)) {
            throw new ConflictException("SERVICE_NAME_TAKEN", "You already offer a service named '" + name + "'");
        }
        ServiceOffering service = new ServiceOffering(provider, name, details.description(),
                details.durationMinutes(), details.bufferMinutes(), details.price());
        if (Boolean.FALSE.equals(details.active())) {
            service.deactivate();
        }
        service = repository.save(service);
        log.info("Provider {} created service {}", provider.getId(), service.getId());
        return service;
    }

    @Transactional
    public ServiceOffering update(UUID providerUserId, UUID serviceId, ServiceDetails details) {
        ServiceOffering service = getOwned(providerUserId, serviceId);
        String name = details.name().trim();
        if (repository.existsByProviderIdAndNameIgnoreCaseAndIdNot(service.getProvider().getId(), name, serviceId)) {
            throw new ConflictException("SERVICE_NAME_TAKEN", "You already offer a service named '" + name + "'");
        }
        // Existing appointments keep their own start/end/price, so editing a service never rewrites history.
        service.update(name, details.description(), details.durationMinutes(), details.bufferMinutes(),
                details.price(), details.active() == null ? service.isActive() : details.active());
        return service;
    }

    /**
     * Soft delete: services are referenced by historical appointments, so they
     * are deactivated (hidden and no longer bookable) instead of removed.
     */
    @Transactional
    public void deactivate(UUID providerUserId, UUID serviceId) {
        getOwned(providerUserId, serviceId).deactivate();
        log.info("Service {} deactivated", serviceId);
    }

    public List<ServiceOffering> listOwn(UUID providerUserId) {
        ProviderProfile provider = providerService.getByUserId(providerUserId);
        return repository.findByProviderIdOrderByNameAsc(provider.getId());
    }

    public List<ServiceOffering> listPublicForProvider(UUID providerId) {
        providerService.getPublicById(providerId);
        return repository.findByProviderIdAndActiveTrueOrderByNameAsc(providerId);
    }

    public ServiceOffering getPublic(UUID serviceId) {
        ServiceOffering service = getById(serviceId);
        if (!service.isActive() || !service.getProvider().isActive()) {
            throw new NotFoundException("Service", serviceId);
        }
        return service;
    }

    public ServiceOffering getById(UUID serviceId) {
        return repository.findById(serviceId).orElseThrow(() -> new NotFoundException("Service", serviceId));
    }

    public Page<ServiceOffering> search(String text, UUID providerId, BigDecimal maxPrice, Integer maxDuration,
            Pageable pageable) {
        Specification<ServiceOffering> spec = Specification.allOf(
                ServiceOfferingSpecifications.publiclyVisible(),
                ServiceOfferingSpecifications.textMatches(text),
                ServiceOfferingSpecifications.ofProvider(providerId),
                ServiceOfferingSpecifications.priceAtMost(maxPrice),
                ServiceOfferingSpecifications.durationAtMost(maxDuration));
        return repository.findAll(spec, pageable);
    }

    private ServiceOffering getOwned(UUID providerUserId, UUID serviceId) {
        ProviderProfile provider = providerService.getByUserId(providerUserId);
        ServiceOffering service = getById(serviceId);
        if (!service.isOwnedBy(provider)) {
            throw new ForbiddenException("You can only manage your own services");
        }
        return service;
    }
}
