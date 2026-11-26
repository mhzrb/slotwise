package com.slotwise.appointment.web;

import com.slotwise.appointment.domain.AppointmentStatus;
import com.slotwise.appointment.service.AppointmentService;
import com.slotwise.appointment.web.dto.AppointmentResponse;
import com.slotwise.common.web.PageResponse;
import com.slotwise.common.web.SortWhitelist;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/appointments")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Appointments", description = "Platform-wide appointment oversight (ADMIN only)")
public class AdminAppointmentController {

    private static final Set<String> SORTABLE = Set.of("startAt", "createdAt", "status");

    private final AppointmentService appointmentService;

    public AdminAppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping
    @Operation(summary = "Search all appointments")
    public PageResponse<AppointmentResponse> search(
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @ParameterObject @PageableDefault(sort = "startAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(appointmentService.listAll(providerId, customerId, status, from, to,
                SortWhitelist.restrict(pageable, SORTABLE)),
                AppointmentResponse::from);
    }
}
