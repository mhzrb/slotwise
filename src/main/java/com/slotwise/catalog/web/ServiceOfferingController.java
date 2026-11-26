package com.slotwise.catalog.web;

import com.slotwise.auth.security.AuthenticatedUser;
import com.slotwise.catalog.service.ServiceOfferingService;
import com.slotwise.catalog.web.dto.ServiceRequest;
import com.slotwise.catalog.web.dto.ServiceResponse;
import com.slotwise.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Services", description = "Services offered by providers")
public class ServiceOfferingController {

    private final ServiceOfferingService serviceOfferingService;

    public ServiceOfferingController(ServiceOfferingService serviceOfferingService) {
        this.serviceOfferingService = serviceOfferingService;
    }

    @PostMapping("/api/v1/providers/me/services")
    @PreAuthorize("hasRole('PROVIDER')")
    @Operation(summary = "Create a service (PROVIDER)")
    public ResponseEntity<ServiceResponse> create(AuthenticatedUser currentUser,
            @Valid @RequestBody ServiceRequest request) {
        ServiceResponse created = ServiceResponse.from(
                serviceOfferingService.create(currentUser.id(), request.toDetails()));
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/services/{id}").buildAndExpand(created.id()).toUri()).body(created);
    }

    @GetMapping("/api/v1/providers/me/services")
    @PreAuthorize("hasRole('PROVIDER')")
    @Operation(summary = "List own services, including inactive ones (PROVIDER)")
    public List<ServiceResponse> listOwn(AuthenticatedUser currentUser) {
        return serviceOfferingService.listOwn(currentUser.id()).stream().map(ServiceResponse::from).toList();
    }

    @GetMapping("/api/v1/providers/{providerId}/services")
    @SecurityRequirements
    @Operation(summary = "List a provider's active services")
    public List<ServiceResponse> listForProvider(@PathVariable UUID providerId) {
        return serviceOfferingService.listPublicForProvider(providerId).stream().map(ServiceResponse::from).toList();
    }

    @GetMapping("/api/v1/services")
    @SecurityRequirements
    @Operation(summary = "Search available services",
            description = "Full-text match on name/description, optional provider, max price and max duration.")
    public PageResponse<ServiceResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice,
            @RequestParam(required = false) @Min(1) Integer maxDurationMinutes,
            @ParameterObject @PageableDefault(sort = "name") Pageable pageable) {
        return PageResponse.from(serviceOfferingService.search(q, providerId, maxPrice, maxDurationMinutes, pageable),
                ServiceResponse::from);
    }

    @GetMapping("/api/v1/services/{serviceId}")
    @SecurityRequirements
    @Operation(summary = "Get service details")
    public ServiceResponse get(@PathVariable UUID serviceId) {
        return ServiceResponse.from(serviceOfferingService.getPublic(serviceId));
    }

    @PutMapping("/api/v1/services/{serviceId}")
    @PreAuthorize("hasRole('PROVIDER')")
    @Operation(summary = "Update an own service (PROVIDER)")
    public ServiceResponse update(AuthenticatedUser currentUser, @PathVariable UUID serviceId,
            @Valid @RequestBody ServiceRequest request) {
        return ServiceResponse.from(serviceOfferingService.update(currentUser.id(), serviceId, request.toDetails()));
    }

    @DeleteMapping("/api/v1/services/{serviceId}")
    @PreAuthorize("hasRole('PROVIDER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate an own service (PROVIDER)",
            description = "Soft delete: the service is hidden and no longer bookable; history is preserved.")
    public void delete(AuthenticatedUser currentUser, @PathVariable UUID serviceId) {
        serviceOfferingService.deactivate(currentUser.id(), serviceId);
    }
}
