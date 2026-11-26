package com.slotwise.appointment.web.dto;

import com.slotwise.appointment.domain.Appointment;
import com.slotwise.appointment.service.AppointmentService.Schedule;
import com.slotwise.appointment.service.AppointmentService.ScheduleView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A provider agenda grouped by provider-local date; every date in the range is present. */
public record ScheduleResponse(
        ScheduleView view,
        String timeZone,
        LocalDate from,
        LocalDate to,
        int totalAppointments,
        List<Day> days) {

    public record Day(LocalDate date, List<AppointmentResponse> appointments) {
    }

    public static ScheduleResponse from(Schedule schedule) {
        ZoneId zone = schedule.provider().zoneId();
        Map<LocalDate, List<AppointmentResponse>> byDate = new TreeMap<>();
        for (LocalDate d = schedule.from(); !d.isAfter(schedule.to()); d = d.plusDays(1)) {
            byDate.put(d, new ArrayList<>());
        }
        for (Appointment a : schedule.appointments()) {
            byDate.computeIfAbsent(a.getStartAt().atZone(zone).toLocalDate(), k -> new ArrayList<>())
                    .add(AppointmentResponse.from(a));
        }
        List<Day> days = byDate.entrySet().stream().map(e -> new Day(e.getKey(), e.getValue())).toList();
        return new ScheduleResponse(schedule.view(), zone.getId(), schedule.from(), schedule.to(),
                schedule.appointments().size(), days);
    }
}
