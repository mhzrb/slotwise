package com.slotwise.provider.domain;

import com.slotwise.common.domain.BaseEntity;
import com.slotwise.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.ZoneId;

@Entity
@Table(name = "provider_profiles")
public class ProviderProfile extends BaseEntity {

    public static final int DEFAULT_SLOT_INTERVAL_MINUTES = 15;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "business_name", nullable = false, length = 120)
    private String businessName;

    @Column(length = 2000)
    private String description;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "slot_interval_minutes", nullable = false)
    private int slotIntervalMinutes = DEFAULT_SLOT_INTERVAL_MINUTES;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private long version;

    protected ProviderProfile() {
    }

    public ProviderProfile(User user, String businessName, String timeZone) {
        this.user = user;
        this.businessName = businessName;
        this.timeZone = timeZone;
    }

    public void update(String businessName, String description, String timeZone, int slotIntervalMinutes,
            boolean active) {
        this.businessName = businessName;
        this.description = description;
        this.timeZone = timeZone;
        this.slotIntervalMinutes = slotIntervalMinutes;
        this.active = active;
    }

    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }

    public User getUser() {
        return user;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getDescription() {
        return description;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public int getSlotIntervalMinutes() {
        return slotIntervalMinutes;
    }

    public boolean isActive() {
        return active;
    }
}
