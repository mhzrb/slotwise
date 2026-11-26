package com.slotwise.user.service;

import com.slotwise.auth.service.RefreshTokenService;
import com.slotwise.common.exception.BadRequestException;
import com.slotwise.common.exception.NotFoundException;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import com.slotwise.user.domain.UserRepository;
import com.slotwise.user.domain.UserSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
    }

    public User getById(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("User", id));
    }

    @Transactional
    public User updateProfile(UUID userId, String fullName, String phone) {
        User user = getById(userId);
        user.updateProfile(fullName.trim(), blankToNull(phone));
        return user;
    }

    /** Changing the password signs the user out of every other session. */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = getById(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadRequestException("INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        refreshTokenService.revokeAllForUser(userId);
        log.info("Password changed for user {}", userId);
    }

    // ----- administration -----

    public Page<User> search(Role role, Boolean enabled, String text, Pageable pageable) {
        Specification<User> spec = Specification.allOf(
                UserSpecifications.hasRole(role),
                UserSpecifications.isEnabled(enabled),
                UserSpecifications.matches(text));
        return userRepository.findAll(spec, pageable);
    }

    @Transactional
    public User setEnabled(UUID adminId, UUID userId, boolean enabled) {
        if (adminId.equals(userId)) {
            throw new BadRequestException("CANNOT_CHANGE_OWN_STATUS", "Administrators cannot disable themselves");
        }
        User user = getById(userId);
        user.setEnabled(enabled);
        if (!enabled) {
            // Refresh is blocked immediately; outstanding access tokens expire within their short TTL.
            refreshTokenService.revokeAllForUser(userId);
        }
        log.info("Admin {} set enabled={} for user {}", adminId, enabled, userId);
        return user;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
