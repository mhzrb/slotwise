package com.slotwise.catalog;

import com.slotwise.catalog.domain.ServiceOffering;
import com.slotwise.catalog.domain.ServiceOfferingRepository;
import com.slotwise.catalog.service.ServiceOfferingService;
import com.slotwise.catalog.service.ServiceOfferingService.ServiceDetails;
import com.slotwise.common.exception.ConflictException;
import com.slotwise.common.exception.ForbiddenException;
import com.slotwise.provider.domain.ProviderProfile;
import com.slotwise.provider.service.ProviderService;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceOfferingServiceTest {

    @Mock
    ServiceOfferingRepository repository;
    @Mock
    ProviderService providerService;
    @InjectMocks
    ServiceOfferingService service;

    private final ServiceDetails details = new ServiceDetails(" Haircut ", null, 30, 5, new BigDecimal("20.00"), null);

    @Test
    void createTrimsNameAndRejectsDuplicates() {
        UUID userId = UUID.randomUUID();
        ProviderProfile provider = provider();
        when(providerService.getByUserId(userId)).thenReturn(provider);
        when(repository.existsByProviderIdAndNameIgnoreCase(provider.getId(), "Haircut")).thenReturn(true);

        assertThatThrownBy(() -> service.create(userId, details)).isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateOfForeignServiceIsForbidden() {
        UUID userId = UUID.randomUUID();
        ProviderProfile mine = provider();
        ServiceOffering foreign = withId(new ServiceOffering(provider(), "Other", null, 30, 0, BigDecimal.TEN));
        when(providerService.getByUserId(userId)).thenReturn(mine);
        when(repository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.update(userId, foreign.getId(), details))
                .isInstanceOf(ForbiddenException.class);
        assertThat(foreign.getName()).isEqualTo("Other");
    }

    @Test
    void deactivateIsSoftDelete() {
        UUID userId = UUID.randomUUID();
        ProviderProfile mine = provider();
        ServiceOffering offering = withId(new ServiceOffering(mine, "Mine", null, 30, 0, BigDecimal.TEN));
        when(providerService.getByUserId(userId)).thenReturn(mine);
        when(repository.findById(offering.getId())).thenReturn(Optional.of(offering));

        service.deactivate(userId, offering.getId());

        assertThat(offering.isActive()).isFalse();
        verify(repository, never()).delete(any(ServiceOffering.class));
    }

    private static ProviderProfile provider() {
        User user = new User("p" + UUID.randomUUID() + "@example.com", "h", "P", null, Role.PROVIDER);
        ProviderProfile profile = new ProviderProfile(user, "Biz", "UTC");
        ReflectionTestUtils.setField(profile, "id", UUID.randomUUID());
        return profile;
    }

    private static ServiceOffering withId(ServiceOffering offering) {
        ReflectionTestUtils.setField(offering, "id", UUID.randomUUID());
        return offering;
    }
}
