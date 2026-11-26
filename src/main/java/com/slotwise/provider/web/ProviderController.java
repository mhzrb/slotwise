package com.slotwise.provider.web;

import com.slotwise.auth.security.AuthenticatedUser;
import com.slotwise.common.web.PageResponse;
import com.slotwise.provider.service.ProviderService;
import com.slotwise.provider.web.dto.ProviderProfileResponse;
import com.slotwise.provider.web.dto.ProviderResponse;
import com.slotwise.provider.web.dto.UpdateProviderProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/providers")
@Tag(name = "Providers", description = "Provider discovery and provider self-management")
public class ProviderController {

    private final ProviderService providerService;

    public ProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    @SecurityRequirements
    @Operation(summary = "Search active providers", description = "Case-insensitive match on business name.")
    public PageResponse<ProviderResponse> search(@RequestParam(required = false) String q,
            @ParameterObject @PageableDefault(sort = "businessName") Pageable pageable) {
        return PageResponse.from(providerService.search(q, pageable),
                ProviderResponse::from);
    }

    @GetMapping("/{providerId}")
    @SecurityRequirements
    @Operation(summary = "Get public provider details")
    public ProviderResponse get(@PathVariable UUID providerId) {
        return ProviderResponse.from(providerService.getPublicById(providerId));
    }

    @GetMapping("/me")
    @Operation(summary = "Get own provider profile (PROVIDER)")
    public ProviderProfileResponse me(AuthenticatedUser currentUser) {
        return ProviderProfileResponse.from(providerService.getByUserId(currentUser.id()));
    }

    @PutMapping("/me")
    @Operation(summary = "Update own provider profile (PROVIDER)")
    public ProviderProfileResponse updateMe(AuthenticatedUser currentUser,
            @Valid @RequestBody UpdateProviderProfileRequest request) {
        return ProviderProfileResponse.from(providerService.updateOwnProfile(currentUser.id(),
                request.businessName(), request.description(), request.timeZone(), request.slotIntervalMinutes(),
                request.active()));
    }
}
