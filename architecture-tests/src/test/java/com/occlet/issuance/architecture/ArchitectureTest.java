package com.occlet.issuance.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import java.util.Date;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@AnalyzeClasses(packages = "com.occlet.issuance", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    private static final String ROOT = "com.occlet.issuance.";
    private static final String WORKFLOWS = ROOT + "workflows..";
    private static final String CONTRACTS = ROOT + "contracts..";
    private static final String PORTS = ROOT + "ports..";
    private static final String ENGINE = ROOT + "engine..";
    private static final String ACTIVITIES_API = ROOT + "activities.api..";
    private static final String ADAPTERS = ROOT + "adapters..";
    private static final String MOCK = ROOT + "mock..";
    private static final String WORKER = ROOT + "worker..";
    private static final String API = ROOT + "api..";

    private static final Set<String> NON_DETERMINISTIC_CALLS = Set.of(
            "java.lang.System.currentTimeMillis",
            "java.lang.System.nanoTime",
            "java.lang.System.getenv",
            "java.lang.System.getProperty",
            "java.lang.Math.random",
            "java.util.UUID.randomUUID",
            "java.time.Instant.now",
            "java.time.LocalDate.now",
            "java.time.LocalDateTime.now",
            "java.time.OffsetDateTime.now",
            "java.time.ZonedDateTime.now");

    private static final DescribedPredicate<JavaMethodCall> NON_DETERMINISTIC = DescribedPredicate.describe(
            "a wall-clock, randomness or environment API",
            call -> NON_DETERMINISTIC_CALLS.contains(
                    call.getTarget().getOwner().getFullName() + "." + call.getTarget().getName()));

    private static final DescribedPredicate<JavaClass> CONCURRENCY_OR_TIME_TYPE = JavaClass.Predicates.belongToAnyOf(
            Thread.class, Date.class, Random.class, Clock.class, Executor.class, CompletableFuture.class,
            java.io.File.class);

    @ArchTest
    static final ArchRule workflowsStayFreeOfIoAndFrameworks = noClasses()
            .that().resideInAPackage(WORKFLOWS)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "java.sql..", "javax.sql..",
                    "java.net..", "java.nio.file..", "okhttp3..",
                    PORTS, ADAPTERS, MOCK, WORKER, API, ROOT + "activities");

    @ArchTest
    static final ArchRule workflowsAvoidNonDeterministicCalls = noClasses()
            .that().resideInAPackage(WORKFLOWS)
            .should().callMethodWhere(NON_DETERMINISTIC);

    @ArchTest
    static final ArchRule workflowsAvoidThreadsAndClocks = noClasses()
            .that().resideInAPackage(WORKFLOWS)
            .should().dependOnClassesThat(CONCURRENCY_OR_TIME_TYPE);

    @ArchTest
    static final ArchRule contractsDependOnNothingInternal = noClasses()
            .that().resideInAPackage(CONTRACTS)
            .should().dependOnClassesThat().resideInAnyPackage(
                    PORTS, ENGINE, ROOT + "activities..", WORKFLOWS, ADAPTERS, MOCK, WORKER, API);

    @ArchTest
    static final ArchRule portsDependOnlyOnContracts = noClasses()
            .that().resideInAPackage(PORTS)
            .should().dependOnClassesThat().resideInAnyPackage(
                    ENGINE, ROOT + "activities..", WORKFLOWS, ADAPTERS, MOCK, WORKER, API,
                    "org.springframework..", "io.temporal..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule activityApiDependsOnlyOnContracts = noClasses()
            .that().resideInAPackage(ACTIVITIES_API)
            .should().dependOnClassesThat().resideInAnyPackage(
                    PORTS, ENGINE, ROOT + "activities", WORKFLOWS, ADAPTERS, MOCK, WORKER, API,
                    "org.springframework..");

    @ArchTest
    static final ArchRule apiNeverImportsWorkflowsOrActivities = noClasses()
            .that().resideInAPackage(API)
            .should().dependOnClassesThat().resideInAnyPackage(
                    WORKFLOWS, ROOT + "activities..", PORTS, ADAPTERS, WORKER, MOCK);
}
