package com.slotwise.provider.service;

import com.slotwise.common.exception.ForbiddenException;
import com.slotwise.common.exception.NotFoundException;
import com.slotwise.provider.domain.ProviderProfile;
import com.slotwise.provider.domain.ProviderProfileRepository;
import com.slotwise.user.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProviderService {

    private final ProviderProfileRepository repository;

    public ProviderService(ProviderProfileRepository repository) {
        this.repository = repository;
    }

    /** Called during PROVIDER registration, inside the registration transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProviderProfile createProfile(User user, String businessName, String timeZone) {
        return repository.save(new ProviderProfile(user, businessName.trim(), timeZone));
    }

    public ProviderProfile getById(UUID providerId) {
        return repository.findById(providerId).orElseThrow(() -> new NotFoundException("Provider", providerId));
    }

    /** Public view: inactive providers and disabled accounts are hidden. */
    public ProviderProfile getPublicById(UUID providerId) {
        ProviderProfile profile = getById(providerId);
        if (!profile.isActive() || !profile.getUser().isEnabled()) {
            throw new NotFoundException("Provider", providerId);
        }
        return profile;
    }

    /** The provider profile owned by the authenticated user. */
    public ProviderProfile getByUserId(UUID userId) {
        return repository.findByUserId(userId)
                .orElseThrow(() -> new ForbiddenException("No provider profile is associated with this account"));
    }

    public Page<ProviderProfile> search(String query, Pageable pageable) {
        String pattern = query == null || query.isBlank()
                ? null
                : "%" + query.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return repository.searchActive(pattern, pageable);
    }

    @Transactional
    public ProviderProfile updateOwnProfile(UUID userId, String businessName, String description, String timeZone,
            Integer slotIntervalMinutes, Boolean active) {
        ProviderProfile profile = getByUserId(userId);
        profile.update(businessName.trim(), description, timeZone,
                slotIntervalMinutes != null ? slotIntervalMinutes : profile.getSlotIntervalMinutes(),
                active != null ? active : profile.isActive());
        return profile;
    }

    /**
     * Acquires the provider row lock for the current transaction.
     * Must be called inside an existing read-write transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProviderProfile lockForScheduling(UUID providerId) {
        return repository.findByIdForUpdate(providerId)
                .orElseThrow(() -> new NotFoundException("Provider", providerId));
    }
}
