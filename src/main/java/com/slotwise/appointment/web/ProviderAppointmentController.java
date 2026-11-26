package com.slotwise.appointment.web;

import com.slotwise.appointment.domain.AppointmentStatus;
import com.slotwise.appointment.service.AppointmentService;
import com.slotwise.appointment.service.AppointmentService.ScheduleView;
import com.slotwise.appointment.web.dto.AppointmentResponse;
import com.slotwise.appointment.web.dto.ScheduleResponse;
import com.slotwise.auth.security.AuthenticatedUser;
import com.slotwise.common.web.PageResponse;
import com.slotwise.common.web.SortWhitelist;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** Provider views of their appointments. PROVIDER role enforced by the /providers/me/** URL rule. */
@RestController
@RequestMapping("/api/v1/providers/me")
@Tag(name = "Provider appointments", description = "Appointments and agenda of the authenticated provider")
public class ProviderAppointmentController {

    private static final Set<String> SORTABLE = Set.of("startAt", "createdAt", "status");

    private final AppointmentService appointmentService;

    public ProviderAppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping("/appointments")
    @Operation(summary = "List appointments booked with me (PROVIDER)")
    public PageResponse<AppointmentResponse> appointments(AuthenticatedUser currentUser,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @ParameterObject @PageableDefault(sort = "startAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(appointmentService.listForProvider(currentUser.id(), status, from, to,
                SortWhitelist.restrict(pageable, SORTABLE)),
                AppointmentResponse::from);
    }

    @GetMapping("/schedule")
    @Operation(summary = "Daily or weekly agenda (PROVIDER)",
            description = "Grouped by provider-local date. WEEK covers Monday-Sunday of the week containing `date`. "
                    + "Cancelled and rejected appointments are excluded.")
    public ScheduleResponse schedule(AuthenticatedUser currentUser,
            @Parameter(description = "Provider-local date; defaults to today")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "DAY") ScheduleView view) {
        return ScheduleResponse.from(appointmentService.providerSchedule(currentUser.id(), date, view));
    }
}
