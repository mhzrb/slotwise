package com.slotwise.auth;

import com.slotwise.auth.web.dto.RegisterRequest;
import com.slotwise.user.domain.Role;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterRequestValidationTest {

    static ValidatorFactory factory;
    static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void validCustomerRegistration() {
        assertThat(validator.validate(customer("Secret123"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short1", "allletters", "12345678", ""})
    void weakPasswordsAreRejected(String password) {
        assertThat(messagesFor(customer(password), "password")).isNotEmpty();
    }

    @Test
    void passwordLongerThanBcryptLimitIsRejected() {
        assertThat(messagesFor(customer("a1".repeat(37)), "password")).isNotEmpty();
    }

    @Test
    void adminRoleIsRejected() {
        RegisterRequest request = new RegisterRequest("a@example.com", "Secret123", "A", null, Role.ADMIN, null, null);
        assertThat(messagesFor(request, "roleAllowed")).containsExactly("role must be CUSTOMER or PROVIDER");
    }

    @Test
    void providerRequiresBusinessNameAndTimeZone() {
        RegisterRequest request = new RegisterRequest("p@example.com", "Secret123", "P", null, Role.PROVIDER, null, null);
        assertThat(messagesFor(request, "providerDetailsPresent")).isNotEmpty();
    }

    @Test
    void providerTimeZoneMustBeIanaRegion() {
        RegisterRequest request = new RegisterRequest("p@example.com", "Secret123", "P", null, Role.PROVIDER,
                "Biz", "+02:00");
        assertThat(messagesFor(request, "timeZone")).isNotEmpty();

        RegisterRequest valid = new RegisterRequest("p@example.com", "Secret123", "P", null, Role.PROVIDER,
                "Biz", "Asia/Tokyo");
        assertThat(validator.validate(valid)).isEmpty();
    }

    private static RegisterRequest customer(String password) {
        return new RegisterRequest("c@example.com", password, "C", null, null, null, null);
    }

    private static Set<String> messagesFor(RegisterRequest request, String property) {
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        return violations.stream()
                .filter(v -> v.getPropertyPath().toString().equals(property))
                .map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }
}
