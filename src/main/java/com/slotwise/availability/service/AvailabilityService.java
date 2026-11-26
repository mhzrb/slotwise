package com.slotwise.availability.service;

import com.slotwise.availability.domain.AvailabilityException;
import com.slotwise.availability.domain.AvailabilityExceptionRepository;
import com.slotwise.availability.domain.ExceptionType;
import com.slotwise.availability.domain.WeeklyScheduleDay;
import com.slotwise.availability.domain.WeeklyScheduleDayRepository;
import com.slotwise.availability.engine.DateException;
import com.slotwise.availability.engine.DayRule;
import com.slotwise.availability.engine.Interval;
import com.slotwise.availability.engine.ScheduleRules;
import com.slotwise.availability.engine.Slot;
import com.slotwise.availability.engine.SlotCalculator;
import com.slotwise.availability.engine.SlotCheck;
import com.slotwise.availability.engine.SlotQuery;
import com.slotwise.availability.engine.TimeWindow;
import com.slotwise.catalog.domain.ServiceOffering;
import com.slotwise.catalog.service.ServiceOfferingService;
import com.slotwise.common.config.BookingProperties;
import com.slotwise.common.exception.BadRequestException;
import com.slotwise.common.exception.BusinessRuleException;
import com.slotwise.common.exception.ConflictException;
import com.slotwise.common.exception.ForbiddenException;
import com.slotwise.common.exception.NotFoundException;
import com.slotwise.provider.domain.ProviderProfile;
import com.slotwise.provider.service.ProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final WeeklyScheduleDayRepository scheduleRepository;
    private final AvailabilityExceptionRepository exceptionRepository;
    private final ProviderService providerService;
    private final ServiceOfferingService serviceOfferingService;
    private final List<BusyTimeProvider> busyTimeProviders;
    private final BookingProperties bookingProperties;
    private final Clock clock;
    private final SlotCalculator calculator = new SlotCalculator();

    public AvailabilityService(WeeklyScheduleDayRepository scheduleRepository,
            AvailabilityExceptionRepository exceptionRepository, ProviderService providerService,
            ServiceOfferingService serviceOfferingService, List<BusyTimeProvider> busyTimeProviders,
            BookingProperties bookingProperties, Clock clock) {
        this.scheduleRepository = scheduleRepository;
        this.exceptionRepository = exceptionRepository;
        this.providerService = providerService;
        this.serviceOfferingService = serviceOfferingService;
        this.busyTimeProviders = busyTimeProviders;
        this.bookingProperties = bookingProperties;
        this.clock = clock;
    }

    public record DaySchedule(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, List<TimeWindow> breaks) {
    }

    public record SlotSearchResult(ProviderProfile provider, ServiceOffering service, LocalDate from, LocalDate to,
            List<Slot> slots) {
    }

    // ------------------------------------------------------------------ weekly schedule

    /**
     * Replaces the whole weekly schedule atomically. Existing appointments are
     * not touched; the new schedule only affects future slot generation.
     */
    @Transactional
    public List<WeeklyScheduleDay> replaceWeeklySchedule(UUID providerUserId, List<DaySchedule> days) {
        validateSchedule(days);
        UUID providerId = providerService.getByUserId(providerUserId).getId();
        // Serialise with concurrent bookings / schedule edits of the same provider.
        providerService.lockForScheduling(providerId);
        scheduleRepository.deleteByProviderId(providerId);
        ProviderProfile provider = providerService.getById(providerId);

        List<WeeklyScheduleDay> saved = new ArrayList<>();
        for (DaySchedule day : days) {
            WeeklyScheduleDay entity = new WeeklyScheduleDay(provider, day.dayOfWeek(), day.startTime(), day.endTime());
            day.breaks().forEach(b -> entity.addBreak(b.start(), b.end()));
            saved.add(scheduleRepository.save(entity));
        }
        saved.sort((a, b) -> a.getDayOfWeek().compareTo(b.getDayOfWeek()));
        log.info("Provider {} replaced weekly schedule ({} working day(s))", providerId, saved.size());
        return saved;
    }

    public List<WeeklyScheduleDay> getWeeklySchedule(UUID providerId) {
        return scheduleRepository.findByProviderIdOrderByDayOfWeekAsc(providerId);
    }

    static void validateSchedule(List<DaySchedule> days) {
        Set<DayOfWeek> seen = EnumSet.noneOf(DayOfWeek.class);
        for (DaySchedule day : days) {
            if (!seen.add(day.dayOfWeek())) {
                throw new BadRequestException("INVALID_SCHEDULE", "Duplicate entry for " + day.dayOfWeek());
            }
            TimeWindow hours = new TimeWindow(day.startTime(), day.endTime());
            List<TimeWindow> breaks = new ArrayList<>(day.breaks());
            breaks.sort((a, b) -> a.start().compareTo(b.start()));
            for (int i = 0; i < breaks.size(); i++) {
                TimeWindow br = breaks.get(i);
                if (!hours.contains(br)) {
                    throw new BadRequestException("INVALID_SCHEDULE",
                            "Break " + br.start() + "-" + br.end() + " on " + day.dayOfWeek()
                                    + " is outside working hours");
                }
                if (i > 0 && breaks.get(i - 1).overlaps(br)) {
                    throw new BadRequestException("INVALID_SCHEDULE",
                            "Breaks on " + day.dayOfWeek() + " overlap");
                }
            }
        }
    }

    // ------------------------------------------------------------------ exceptions

    @Transactional
    public AvailabilityException addException(UUID providerUserId, LocalDate date, ExceptionType type,
            LocalTime startTime, LocalTime endTime, String reason) {
        ProviderProfile provider = providerService.getByUserId(providerUserId);
        LocalDate today = LocalDate.now(clock.withZone(provider.zoneId()));
        if (date.isBefore(today)) {
            throw new BusinessRuleException("EXCEPTION_IN_PAST", "Exceptions cannot be added for past dates");
        }
        boolean hasTimes = startTime != null && endTime != null;
        if (type == ExceptionType.HOLIDAY && (startTime != null || endTime != null)) {
            throw new BadRequestException("INVALID_EXCEPTION", "HOLIDAY exceptions cover the whole day; omit times");
        }
        if (type == ExceptionType.EXTRA_HOURS && !hasTimes) {
            throw new BadRequestException("INVALID_EXCEPTION", "EXTRA_HOURS exceptions require startTime and endTime");
        }
        if ((startTime == null) != (endTime == null)) {
            throw new BadRequestException("INVALID_EXCEPTION", "Provide both startTime and endTime, or neither");
        }
        providerService.lockForScheduling(provider.getId());
        AvailabilityException saved = exceptionRepository.save(
                new AvailabilityException(provider, date, type, startTime, endTime, reason));
        log.info("Provider {} added {} exception on {}", provider.getId(), type, date);
        return saved;
    }

    public List<AvailabilityException> listOwnExceptions(UUID providerUserId, LocalDate from, LocalDate to) {
        ProviderProfile provider = providerService.getByUserId(providerUserId);
        DateRange range = resolveRange(provider.zoneId(), from, to, defaultListingDays(), maxListingDays());
        return exceptionRepository.findByProviderIdAndDateBetweenOrderByDateAscStartTimeAsc(provider.getId(),
                range.from(), range.to());
    }

    public List<AvailabilityException> listPublicExceptions(UUID providerId, LocalDate from, LocalDate to) {
        ProviderProfile provider = providerService.getPublicById(providerId);
        DateRange range = resolveRange(provider.zoneId(), from, to, defaultListingDays(), maxListingDays());
        return exceptionRepository.findByProviderIdAndDateBetweenOrderByDateAscStartTimeAsc(providerId,
                range.from(), range.to());
    }

    @Transactional
    public void deleteException(UUID providerUserId, UUID exceptionId) {
        ProviderProfile provider = providerService.getByUserId(providerUserId);
        AvailabilityException exception = exceptionRepository.findById(exceptionId)
                .orElseThrow(() -> new NotFoundException("Availability exception", exceptionId));
        if (!exception.getProvider().getId().equals(provider.getId())) {
            throw new ForbiddenException("You can only manage your own availability");
        }
        providerService.lockForScheduling(provider.getId());
        exceptionRepository.delete(exception);
    }

    // ------------------------------------------------------------------ slots

    public SlotSearchResult findAvailableSlots(UUID providerId, UUID serviceId, LocalDate from, LocalDate to) {
        ProviderProfile provider = providerService.getPublicById(providerId);
        ServiceOffering service = serviceOfferingService.getPublic(serviceId);
        if (!service.getProvider().getId().equals(providerId)) {
            throw new BadRequestException("SERVICE_NOT_OFFERED", "The service is not offered by this provider");
        }
        ZoneId zone = provider.zoneId();
        DateRange range = resolveRange(zone, from, to, 7, bookingProperties.maxSlotQueryDays());

        Instant rangeStart = range.from().atStartOfDay(zone).toInstant();
        // +1 day: a late slot's buffer may extend past midnight
        Instant rangeEnd = range.to().plusDays(2).atStartOfDay(zone).toInstant();
        Instant now = clock.instant();
        SlotQuery query = new SlotQuery(range.from(), range.to(), service.duration(), service.buffer(),
                now.plus(bookingProperties.minBookingNotice()), now.plus(bookingProperties.maxBookingHorizon()),
                busyIntervals(providerId, rangeStart, rangeEnd, null));
        List<Slot> slots = calculator.calculate(loadRules(provider, range.from(), range.to()), query);
        return new SlotSearchResult(provider, service, range.from(), range.to(), slots);
    }

    /**
     * Verifies that {@code start} is a slot the provider offers for an
     * appointment of the given duration and buffer, and that it does not
     * collide with existing appointments.
     * <p>
     * Callers must hold the provider lock ({@link ProviderService#lockForScheduling})
     * so that the result stays valid until their transaction commits.
     *
     * @throws BusinessRuleException 422 when the time is not bookable in principle
     * @throws ConflictException     409 when the time is taken by another appointment
     */
    public void assertSlotBookable(ProviderProfile provider, Duration duration, Duration buffer, Instant start,
            UUID excludeAppointmentId) {
        Instant now = clock.instant();
        Instant occupiedUntil = start.plus(duration).plus(buffer);
        LocalDate localDate = start.atZone(provider.zoneId()).toLocalDate();
        SlotCheck check = calculator.check(loadRules(provider, localDate, localDate), start, duration,
                buffer, now.plus(bookingProperties.minBookingNotice()),
                now.plus(bookingProperties.maxBookingHorizon()),
                busyIntervals(provider.getId(), start, occupiedUntil, excludeAppointmentId));
        switch (check) {
            case AVAILABLE -> {
            }
            case TOO_SOON -> throw start.isBefore(now)
                    ? new BusinessRuleException("BOOKING_IN_PAST", "Appointments cannot be booked in the past")
                    : new BusinessRuleException("BOOKING_TOO_SOON", "Appointments must be booked at least "
                            + bookingProperties.minBookingNotice().toMinutes() + " minutes in advance");
            case TOO_FAR_AHEAD -> throw new BusinessRuleException("BOOKING_TOO_FAR_AHEAD",
                    "Appointments can be booked at most " + bookingProperties.maxBookingHorizon().toDays()
                            + " days in advance");
            case OUTSIDE_AVAILABILITY -> throw new BusinessRuleException("SLOT_NOT_OFFERED",
                    "The requested time is not an available slot for this service");
            case CONFLICT -> throw new ConflictException("SLOT_UNAVAILABLE",
                    "The requested time slot is no longer available");
        }
    }

    ScheduleRules loadRules(ProviderProfile provider, LocalDate from, LocalDate to) {
        Map<DayOfWeek, DayRule> weekly = new EnumMap<>(DayOfWeek.class);
        for (WeeklyScheduleDay day : scheduleRepository.findByProviderIdOrderByDayOfWeekAsc(provider.getId())) {
            List<TimeWindow> breaks = day.getBreaks().stream()
                    .map(b -> new TimeWindow(b.getStartTime(), b.getEndTime()))
                    .toList();
            weekly.put(day.getDayOfWeek(), new DayRule(day.getStartTime(), day.getEndTime(), breaks));
        }
        List<DateException> exceptions = exceptionRepository
                .findByProviderIdAndDateBetweenOrderByDateAscStartTimeAsc(provider.getId(), from, to).stream()
                .map(e -> new DateException(e.getDate(), e.getType(), e.getStartTime(), e.getEndTime()))
                .toList();
        return new ScheduleRules(provider.zoneId(), Duration.ofMinutes(provider.getSlotIntervalMinutes()), weekly,
                exceptions);
    }

    private List<Interval> busyIntervals(UUID providerId, Instant from, Instant to, UUID excludeAppointmentId) {
        List<Interval> busy = new ArrayList<>();
        for (BusyTimeProvider source : busyTimeProviders) {
            busy.addAll(source.findBusyIntervals(providerId, from, to, excludeAppointmentId));
        }
        return busy;
    }

    // ------------------------------------------------------------------ helpers

    record DateRange(LocalDate from, LocalDate to) {
    }

    private DateRange resolveRange(ZoneId zone, LocalDate from, LocalDate to, int defaultDays, int maxDays) {
        LocalDate start = from != null ? from : LocalDate.now(clock.withZone(zone));
        LocalDate end = to != null ? to : start.plusDays(defaultDays - 1L);
        if (end.isBefore(start)) {
            throw new BadRequestException("INVALID_DATE_RANGE", "'to' must not be before 'from'");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > maxDays) {
            throw new BadRequestException("INVALID_DATE_RANGE", "Date range must not exceed " + maxDays + " days");
        }
        return new DateRange(start, end);
    }

    private static int defaultListingDays() {
        return 30;
    }

    private int maxListingDays() {
        return (int) Math.max(bookingProperties.maxBookingHorizon().toDays(), 1) + 1;
    }
}
