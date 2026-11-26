package com.slotwise.availability.domain;

import com.slotwise.common.domain.BaseEntity;
import com.slotwise.provider.domain.ProviderProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "availability_exceptions")
public class AvailabilityException extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ProviderProfile provider;

    @Column(name = "exception_date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExceptionType type;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(length = 255)
    private String reason;

    protected AvailabilityException() {
    }

    public AvailabilityException(ProviderProfile provider, LocalDate date, ExceptionType type, LocalTime startTime,
            LocalTime endTime, String reason) {
        this.provider = provider;
        this.date = date;
        this.type = type;
        this.startTime = startTime;
        this.endTime = endTime;
        this.reason = reason;
    }

    public ProviderProfile getProvider() {
        return provider;
    }

    public LocalDate getDate() {
        return date;
    }

    public ExceptionType getType() {
        return type;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getReason() {
        return reason;
    }
}
