package com.slotwise.notification.web;

import com.slotwise.auth.security.AuthenticatedUser;
import com.slotwise.common.web.PageResponse;
import com.slotwise.common.web.SortWhitelist;
import com.slotwise.notification.service.NotificationService;
import com.slotwise.notification.web.dto.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Notifications sent to the authenticated user")
public class NotificationController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "sentAt", "type", "status");

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/me")
    @Operation(summary = "List own notifications")
    public PageResponse<NotificationResponse> mine(AuthenticatedUser currentUser,
            @ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(notificationService.listForRecipient(currentUser.id(),
                SortWhitelist.restrict(pageable, SORTABLE)),
                NotificationResponse::from);
    }
}
