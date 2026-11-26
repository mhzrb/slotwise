package com.slotwise.catalog.domain;

import com.slotwise.common.domain.BaseEntity;
import com.slotwise.provider.domain.ProviderProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * A bookable service ("Haircut", "Consultation"). Named ServiceOffering to
 * avoid confusion with Spring's {@code @Service} stereotype.
 */
@Entity
@Table(name = "service_offerings")
public class ServiceOffering extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ProviderProfile provider;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    /** Time blocked after each appointment (cleanup, travel) during which nothing else can be booked. */
    @Column(name = "buffer_minutes", nullable = false)
    private int bufferMinutes;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private long version;

    protected ServiceOffering() {
    }

    public ServiceOffering(ProviderProfile provider, String name, String description, int durationMinutes,
            int bufferMinutes, BigDecimal price) {
        this.provider = provider;
        update(name, description, durationMinutes, bufferMinutes, price, true);
    }

    public final void update(String name, String description, int durationMinutes, int bufferMinutes,
            BigDecimal price, boolean active) {
        this.name = name;
        this.description = description;
        this.durationMinutes = durationMinutes;
        this.bufferMinutes = bufferMinutes;
        this.price = price;
        this.active = active;
    }

    public void deactivate() {
        this.active = false;
    }

    public Duration duration() {
        return Duration.ofMinutes(durationMinutes);
    }

    public Duration buffer() {
        return Duration.ofMinutes(bufferMinutes);
    }

    public boolean isOwnedBy(ProviderProfile profile) {
        return provider.getId().equals(profile.getId());
    }

    public ProviderProfile getProvider() {
        return provider;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public int getBufferMinutes() {
        return bufferMinutes;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isActive() {
        return active;
    }
}
