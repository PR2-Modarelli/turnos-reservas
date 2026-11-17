package ar.com.modarelli.turnos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.*;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "ar.com.modarelli.turnos", importOptions = DoNotIncludeTests.class)
class HexagonalArchitectureTest {
    @ArchTest
    static final ArchRule dominioIndependiente =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..application..",
                            "..infrastructure..",
                            "org.springframework..",
                            "jakarta..",
                            "tools.jackson..",
                            "com.fasterxml..",
                            "java.sql..");

    @ArchTest
    static final ArchRule aplicacionUsaPuertos =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..infrastructure..",
                            "org.springframework.jdbc..",
                            "org.springframework.data..",
                            "org.springframework.security..",
                            "org.springframework.web..",
                            "org.springframework.http..",
                            "jakarta.persistence..",
                            "tools.jackson..",
                            "com.fasterxml..",
                            "java.sql..");

    @ArchTest
    static final ArchRule entradaUsaCasosDeUso =
            noClasses()
                    .that()
                    .resideInAnyPackage(
                            "..infrastructure.web.controller..", "..infrastructure.scheduling..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..application..");

    @ArchTest
    static final ArchRule puertosSonInterfaces =
            classes()
                    .that()
                    .resideInAnyPackage("..domain.ports.in..", "..domain.ports.out..")
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .beInterfaces();
}
