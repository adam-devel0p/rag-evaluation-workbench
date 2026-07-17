package com.rageval.workbench.target.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rageval.workbench.common.error.ValidationException;
import org.junit.jupiter.api.Test;

class TargetUrlValidatorTest {

    private final TargetUrlValidator validator = new TargetUrlValidator();

    @Test
    void acceptsHttpsUrl() {
        validator.validate("https://acme.example.com/chat");
    }

    @Test
    void acceptsHttpUrl() {
        validator.validate("http://acme.example.com/chat");
    }

    @Test
    void allowsLocalhostSinceThisIsALocalWorkbench() {
        validator.validate("http://localhost:8080/demo-target/chat");
    }

    @Test
    void rejectsFileScheme() {
        assertThatThrownBy(() -> validator.validate("file:///etc/passwd"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("http or https");
    }

    @Test
    void rejectsFtpScheme() {
        assertThatThrownBy(() -> validator.validate("ftp://example.com/file"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> validator.validate("  "))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("required");
    }

    @Test
    void rejectsNullUrl() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsMalformedUri() {
        assertThatThrownBy(() -> validator.validate("http://[invalid"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void violationCarriesFieldName() {
        assertThatThrownBy(() -> validator.validate("ftp://example.com"))
                .isInstanceOfSatisfying(ValidationException.class, ex -> assertThat(ex.getViolations())
                        .anyMatch(v -> v.field().equals("endpointUrl")));
    }
}
