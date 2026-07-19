package com.rageval.workbench.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * The lightweight architecture rules from spec §28.5. Deliberately no full layered-architecture
 * model — the three rules below are the ones the spec names, and they run against production
 * classes only.
 */
@AnalyzeClasses(
        packages = "com.rageval.workbench",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule controllersDoNotDependOnRepositories = noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .because("controllers must go through application services (spec §28.5)");

    @ArchTest
    static final ArchRule domainDoesNotDependOnApi = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..api..", "..api.dto..")
            .because("domain concepts must not depend on the web/API layer (spec §28.5)");

    @ArchTest
    static final ArchRule controllersNeverReturnJpaEntities = methods()
            .that()
            .areDeclaredInClassesThat()
            .haveSimpleNameEndingWith("Controller")
            .and()
            .arePublic()
            .should(notReturnJpaEntities())
            .because("controllers return DTOs, never JPA entities (spec §28.5) — including inside "
                    + "generic containers like PageResponse or ResponseEntity");

    private static ArchCondition<JavaMethod> notReturnJpaEntities() {
        return new ArchCondition<>("not return a JPA entity, directly or as a generic type argument") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaClass involved : method.getReturnType().getAllInvolvedRawTypes()) {
                    if (involved.isAnnotatedWith("jakarta.persistence.Entity")) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                method.getFullName() + " returns JPA entity " + involved.getName()));
                    }
                }
            }
        };
    }
}
