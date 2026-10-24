package za.co.onlinepos.integration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ModuleArchitectureIT {
  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("za.co.onlinepos");

  @Test
  void domainAndContractsStayIndependentOfUiServerAndDatabaseFrameworks() {
    noClasses()
        .that()
        .resideInAnyPackage("..domain..", "..contracts..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "javafx..",
            "org.springframework..",
            "java.sql..",
            "jakarta.persistence..",
            "jakarta.enterprise..",
            "jakarta.inject..",
            "jakarta.annotation..")
        .check(classes);
  }

  @Test
  void javafxDoesNotBypassBackendOrOwnDatabaseConnections() {
    noClasses()
        .that()
        .resideInAPackage("..desktop..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..server..", "java.sql..", "org.postgresql..", "org.h2..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..server..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("javafx..")
        .check(classes);
  }

  @Test
  void backendHasNoCdiAndPackagesHaveNoCycles() {
    noClasses()
        .that()
        .resideInAPackage("..server..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.jboss.weld..", "jakarta.enterprise..", "jakarta.inject..")
        .check(classes);
    noClasses()
        .that()
        .resideInAnyPackage("..desktop..", "..installation..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..installation..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("jakarta.enterprise..", "jakarta.inject..", "javafx..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..desktop..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.lang.ProcessBuilder")
        .check(classes);
    com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices()
        .matching("za.co.onlinepos.(*)..")
        .should()
        .beFreeOfCycles()
        .check(classes);
    com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices()
        .matching("za.co.onlinepos.(**)")
        .should()
        .beFreeOfCycles()
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..desktop..")
        .and()
        .doNotHaveSimpleName("DesktopContainer")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.jboss.weld..")
        .check(classes);
  }
}
