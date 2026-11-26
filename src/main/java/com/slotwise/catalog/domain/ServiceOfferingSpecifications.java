package com.slotwise.catalog.domain;

import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

public final class ServiceOfferingSpecifications {

    private ServiceOfferingSpecifications() {
    }

    /** Only active services of active, enabled providers are publicly searchable. */
    public static Specification<ServiceOffering> publiclyVisible() {
        return (root, query, cb) -> {
            Join<Object, Object> provider = root.join("provider");
            return cb.and(
                    cb.isTrue(root.get("active")),
                    cb.isTrue(provider.get("active")),
                    cb.isTrue(provider.join("user").get("enabled")));
        };
    }

    public static Specification<ServiceOffering> textMatches(String text) {
        return (root, query, cb) -> {
            if (text == null || text.isBlank()) {
                return null;
            }
            String pattern = "%" + text.trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\'));
        };
    }

    public static Specification<ServiceOffering> ofProvider(UUID providerId) {
        return (root, query, cb) -> providerId == null ? null : cb.equal(root.get("provider").get("id"), providerId);
    }

    public static Specification<ServiceOffering> priceAtMost(BigDecimal maxPrice) {
        return (root, query, cb) -> maxPrice == null ? null : cb.lessThanOrEqualTo(root.get("price"), maxPrice);
    }

    public static Specification<ServiceOffering> durationAtMost(Integer maxDurationMinutes) {
        return (root, query, cb) -> maxDurationMinutes == null
                ? null
                : cb.lessThanOrEqualTo(root.get("durationMinutes"), maxDurationMinutes);
    }
}
