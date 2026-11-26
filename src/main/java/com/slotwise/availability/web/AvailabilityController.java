package com.slotwise.availability.web;

import com.slotwise.auth.security.AuthenticatedUser;
import com.slotwise.availability.service.AvailabilityService;
import com.slotwise.availability.web.dto.AvailabilityExceptionRequest;
import com.slotwise.availability.web.dto.AvailabilityExceptionResponse;
import com.slotwise.availability.web.dto.DayScheduleRequest;
import com.slotwise.availability.web.dto.ProviderAvailabilityResponse;
import com.slotwise.availability.web.dto.SlotsResponse;
import com.slotwise.availability.web.dto.WeeklyScheduleRequest;
import com.slotwise.availability.web.dto.WeeklyScheduleResponse;
import com.slotwise.provider.domain.ProviderProfile;
import com.slotwise.provider.service.ProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Availability", description = "Working hours, exceptions and bookable slots")
public class AvailabilityController {

    private final AvailabilityService availabilityService;
    private final ProviderService providerService;

    public AvailabilityController(AvailabilityService availabilityService, ProviderService providerService) {
        this.availabilityService = availabilityService;
        this.providerService = providerService;
    }

    // ---------------- provider self-management (PROVIDER role enforced by URL rule /providers/me/**)

    @PutMapping("/api/v1/providers/me/availability")
    @Operation(summary = "Replace the weekly schedule (PROVIDER)",
            description = "Times are local to the provider's time zone. Unlisted weekdays are days off.")
    public WeeklyScheduleResponse replaceWeeklySchedule(AuthenticatedUser currentUser,
            @Valid @RequestBody WeeklyScheduleRequest request) {
        var days = availabilityService.replaceWeeklySchedule(currentUser.id(),
                request.days().stream().map(DayScheduleRequest::toDaySchedule).toList());
        return WeeklyScheduleResponse.from(providerService.getByUserId(currentUser.id()), days);
    }

    @GetMapping("/api/v1/providers/me/availability")
    @Operation(summary = "Get own weekly schedule and upcoming exceptions (PROVIDER)")
    public ProviderAvailabilityResponse ownAvailability(AuthenticatedUser currentUser) {
        ProviderProfile provider = providerService.getByUserId(currentUser.id());
        return new ProviderAvailabilityResponse(
                WeeklyScheduleResponse.from(provider, availabilityService.getWeeklySchedule(provider.getId())),
                availabilityService.listOwnExceptions(currentUser.id(), null, null).stream()
                        .map(AvailabilityExceptionResponse::forOwner).toList());
    }

    @PostMapping("/api/v1/providers/me/availability/exceptions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a holiday, blocked time or extra hours (PROVIDER)")
    public AvailabilityExceptionResponse addException(AuthenticatedUser currentUser,
            @Valid @RequestBody AvailabilityExceptionRequest request) {
        return AvailabilityExceptionResponse.forOwner(availabilityService.addException(currentUser.id(),
                request.date(), request.type(), request.startTime(), request.endTime(), request.reason()));
    }

    @GetMapping("/api/v1/providers/me/availability/exceptions")
    @Operation(summary = "List own exceptions in a date range (PROVIDER)")
    public List<AvailabilityExceptionResponse> listOwnExceptions(AuthenticatedUser currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return availabilityService.listOwnExceptions(currentUser.id(), from, to).stream()
                .map(AvailabilityExceptionResponse::forOwner).toList();
    }

    @DeleteMapping("/api/v1/providers/me/availability/exceptions/{exceptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an own exception (PROVIDER)")
    public void deleteException(AuthenticatedUser currentUser, @PathVariable UUID exceptionId) {
        availabilityService.deleteException(currentUser.id(), exceptionId);
    }

    // ---------------- public

    @GetMapping("/api/v1/providers/{providerId}/availability")
    @SecurityRequirements
    @Operation(summary = "Get a provider's weekly schedule and exceptions")
    public ProviderAvailabilityResponse publicAvailability(@PathVariable UUID providerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ProviderProfile provider = providerService.getPublicById(providerId);
        return new ProviderAvailabilityResponse(
                WeeklyScheduleResponse.from(provider, availabilityService.getWeeklySchedule(providerId)),
                availabilityService.listPublicExceptions(providerId, from, to).stream()
                        .map(AvailabilityExceptionResponse::forPublic).toList());
    }

    @GetMapping("/api/v1/providers/{providerId}/slots")
    @SecurityRequirements
    @Operation(summary = "List bookable slots for a service",
            description = "Dates are provider-local. Defaults to 7 days starting today. Slots honour working hours, "
                    + "breaks, exceptions, existing appointments, buffer time, minimum notice and booking horizon.")
    public SlotsResponse slots(@PathVariable UUID providerId,
            @RequestParam UUID serviceId,
            @Parameter(description = "First provider-local date (inclusive)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last provider-local date (inclusive)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return SlotsResponse.from(availabilityService.findAvailableSlots(providerId, serviceId, from, to));
    }
}
