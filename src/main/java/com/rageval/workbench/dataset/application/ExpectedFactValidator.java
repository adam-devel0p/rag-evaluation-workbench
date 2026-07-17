package com.rageval.workbench.dataset.application;

import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.dataset.domain.FactMatchType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/** Compiles every {@code REGEX}-mode {@link ExpectedFact}'s pattern at import/edit time, never at evaluation time. */
@Component
public class ExpectedFactValidator {

    public List<FieldViolation> validate(String fieldName, List<ExpectedFact> facts) {
        List<FieldViolation> violations = new ArrayList<>();
        if (facts == null) {
            return violations;
        }
        for (int i = 0; i < facts.size(); i++) {
            ExpectedFact fact = facts.get(i);
            if (fact.matchType() == FactMatchType.REGEX) {
                try {
                    Pattern.compile(fact.value());
                } catch (PatternSyntaxException ex) {
                    violations.add(new FieldViolation(
                            fieldName + "[" + i + "].value", "invalid regex pattern: " + ex.getMessage()));
                }
            }
        }
        return violations;
    }
}
