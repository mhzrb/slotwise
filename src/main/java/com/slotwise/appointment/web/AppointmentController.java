package com.slotwise.appointment.web;

import com.slotwise.appointment.domain.AppointmentStatus;
import com.slotwise.appointment.service.AppointmentService;
import com.slotwise.appointment.web.dto.AppointmentResponse;
import com.slotwise.appointment.web.dto.BookAppointmentRequest;
import com.slotwise.appointment.web.dto.ReasonRequest;
import com.slotwise.appointment.web.dto.RescheduleAppointmentRequest;
import com.slotwise.auth.security.AuthenticatedUser;
import com.slotwise.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/appointments")
@Tag(name = "Appointments", description = "Booking and appointment lifecycle")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Book an appointment (CUSTOMER)",
            description = "Atomic and concurrency-safe: of several simultaneous requests for overlapping times "
                    + "with the same provider, exactly one succeeds.")
    @ApiResponse(responseCode = "201", description = "Booked (status PENDING)")
    @ApiResponse(responseCode = "409", description = "SLOT_UNAVAILABLE - the time is already taken")
    @ApiResponse(responseCode = "422", description = "Not bookable: past, too soon, too far ahead or not offered")
    public ResponseEntity<AppointmentResponse> book(AuthenticatedUser currentUser,
            @Valid @RequestBody BookAppointmentRequest request) {
        AppointmentResponse body = AppointmentResponse.from(appointmentService.book(currentUser.id(),
                request.serviceId(), request.startAt().toInstant(), request.notes()));
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(body.id()).toUri()).body(body);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Own appointment history (CUSTOMER)")
    public PageResponse<AppointmentResponse> mine(AuthenticatedUser currentUser,
            @RequestParam(required = false) AppointmentStatus status,
            @Parameter(description = "Appointments starting at or after (ISO-8601 instant)")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Appointments starting before (ISO-8601 instant)")
            @RequestParam(required = false) Instant to,
            @ParameterObject @PageableDefault(sort = "startAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(appointmentService.listForCustomer(currentUser.id(), status, from, to, pageable),
                AppointmentResponse::from);
    }

    @GetMapping("/{appointmentId}")
    @Operation(summary = "Appointment details (owning customer, owning provider or ADMIN)")
    public AppointmentResponse get(AuthenticatedUser currentUser, @PathVariable UUID appointmentId) {
        return AppointmentResponse.from(appointmentService.getVisibleTo(currentUser, appointmentId));
    }

    @PatchMapping("/{appointmentId}/cancel")
    @Operation(summary = "Cancel an appointment",
            description = "Customers: own appointments, up to the cancellation cutoff. Providers: appointments "
                    + "booked with them. Admins: any. Started appointments cannot be cancelled.")
    public AppointmentResponse cancel(AuthenticatedUser currentUser, @PathVariable UUID appointmentId,
            @Valid @RequestBody(required = false) ReasonRequest request) {
        return AppointmentResponse.from(appointmentService.cancel(currentUser, appointmentId,
                request == null ? null : request.reason()));
    }

    @PatchMapping("/{appointmentId}/reschedule")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Reschedule an own appointment (CUSTOMER)",
            description = "Keeps the original duration; the appointment returns to PENDING. Subject to the "
                    + "reschedule cutoff and maximum number of reschedules.")
    public AppointmentResponse reschedule(AuthenticatedUser currentUser, @PathVariable UUID appointmentId,
            @Valid @RequestBody RescheduleAppointmentRequest request) {
        return AppointmentResponse.from(appointmentService.reschedule(currentUser.id(), appointmentId,
                request.startAt().toInstant()));
    }

    @PatchMapping("/{appointmentId}/confirm")
    @PreAuthorize("hasRole('PROVIDER')")
    @Operation(summary = "Confirm a pending appointment (PROVIDER)")
    public AppointmentResponse confirm(AuthenticatedUser currentUser, @PathVariable UUID appointmentId) {
        return AppointmentResponse.from(appointmentService.confirm(currentUser.id(), appointmentId));
    }

    @PatchMapping("/{appointmentId}/reject")
    @PreAuthorize("hasRole('PROVIDER')")
    @Operation(summary = "Reject a pending appointment request (PROVIDER)")
    public AppointmentResponse reject(AuthenticatedUser currentUser, @PathVariable UUID appointmentId,
            @Valid @RequestBody(required = false) ReasonRequest request) {
        return AppointmentResponse.from(appointmentService.reject(currentUser.id(), appointmentId,
                request == null ? null : request.reason()));
    }

    @PatchMapping("/{appointmentId}/complete")
    @PreAuthorize("hasRole('PROVIDER')")
    @Operation(summary = "Mark a confirmed appointment as completed (PROVIDER)")
    public AppointmentResponse complete(AuthenticatedUser currentUser, @PathVariable UUID appointmentId) {
        return AppointmentResponse.from(appointmentService.complete(currentUser.id(), appointmentId));
    }
}
