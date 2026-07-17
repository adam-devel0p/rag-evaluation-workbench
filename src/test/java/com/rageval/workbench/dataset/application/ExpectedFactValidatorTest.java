package com.rageval.workbench.dataset.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.dataset.domain.FactMatchType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpectedFactValidatorTest {

    private final ExpectedFactValidator validator = new ExpectedFactValidator();

    @Test
    void acceptsValidRegex() {
        List<ExpectedFact> facts = List.of(
                new ExpectedFact("f1", "Java\\s+21", FactMatchType.REGEX, true, "version check"));

        assertThat(validator.validate("requiredFacts", facts)).isEmpty();
    }

    @Test
    void rejectsInvalidRegex() {
        List<ExpectedFact> facts = List.of(
                new ExpectedFact("f1", "Java[", FactMatchType.REGEX, true, "broken pattern"));

        List<FieldViolation> violations = validator.validate("requiredFacts", facts);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).field()).isEqualTo("requiredFacts[0].value");
        assertThat(violations.get(0).message()).contains("invalid regex");
    }

    @Test
    void ignoresNonRegexMatchTypesRegardlessOfValue() {
        List<ExpectedFact> facts = List.of(
                new ExpectedFact("f1", "Java[", FactMatchType.EXACT_TEXT, true, "not a regex"),
                new ExpectedFact("f2", "Java[", FactMatchType.CASE_INSENSITIVE_TEXT, true, "also not a regex"));

        assertThat(validator.validate("requiredFacts", facts)).isEmpty();
    }

    @Test
    void returnsEmptyForNullOrEmptyList() {
        assertThat(validator.validate("requiredFacts", null)).isEmpty();
        assertThat(validator.validate("requiredFacts", List.of())).isEmpty();
    }

    @Test
    void reportsEachInvalidPatternWithItsOwnIndex() {
        List<ExpectedFact> facts = List.of(
                new ExpectedFact("f1", "(unclosed", FactMatchType.REGEX, true, "bad"),
                new ExpectedFact("f2", "valid", FactMatchType.REGEX, true, "ok"),
                new ExpectedFact("f3", "[also-bad", FactMatchType.REGEX, true, "bad too"));

        List<FieldViolation> violations = validator.validate("forbiddenClaims", facts);

        assertThat(violations).extracting(FieldViolation::field)
                .containsExactly("forbiddenClaims[0].value", "forbiddenClaims[2].value");
    }
}
