package com.agentpaygate.policy;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Determinism and purity rules for policy-core. See ledger-core for the shared rationale. */
class DeterminismTest {

    private static final String ROOT_PACKAGE = "com.agentpaygate.policy";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT_PACKAGE);

    private static final List<String> FORBIDDEN_SOURCE_TOKENS = List.of(
            "java.time",
            "synchronized",
            "new HashMap",
            "new HashSet",
            "Math.random",
            "SecureRandom",
            "UUID.randomUUID",
            "java.util.concurrent",
            "java.nio.file",
            "java.io.",
            "java.net.",
            "org.springframework",
            "System.currentTimeMillis",
            "System.nanoTime",
            "Thread.sleep");

    @Test
    void noWallClockConcurrencyIoNetworkingOrFrameworkDependencies() {
        noClasses()
                .that().resideInAPackage(ROOT_PACKAGE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.time..",
                        "java.util.concurrent..",
                        "java.io..",
                        "java.nio.file..",
                        "java.nio.channels..",
                        "java.net..",
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework..",
                        "jakarta.persistence..")
                .because("pure cores take time and randomness as parameters and never perform I/O")
                .check(CLASSES);
    }

    @Test
    void noRandomnessThreadsOrUnseededIdentifiers() {
        List<String> forbiddenTypes = List.of(
                "java.util.Random",
                "java.security.SecureRandom",
                "java.util.UUID",
                "java.lang.Thread",
                "java.lang.ThreadLocal",
                "java.lang.Runtime");
        for (String forbidden : forbiddenTypes) {
            noClasses()
                    .should().accessClassesThat().haveFullyQualifiedName(forbidden)
                    .because(forbidden + " would make a run unreproducible or non-deterministic")
                    .check(CLASSES);
        }
    }

    @Test
    void sourceContainsNoClockSynchronisationOrHashOrderedCollections() throws IOException {
        Path sources = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    for (String token : FORBIDDEN_SOURCE_TOKENS) {
                        if (lines.get(i).contains(token)) {
                            violations.add(file + ":" + (i + 1) + " contains '" + token + "'");
                        }
                    }
                }
            }
        }
        assertThat(violations).as("forbidden tokens in pure-core sources").isEmpty();
    }
}
