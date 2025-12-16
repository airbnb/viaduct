# Jacoco Code Coverage Setup

This project has been configured with Jacoco code coverage reporting optimized.

## Configuration Details

### What's Configured
- **Individual module coverage**: Each submodule generates its own coverage reports
- **Aggregated coverage**: Combined coverage report across all modules
- **Github integration**: XML reports generated in standardized paths
- **Gradle integration**: Coverage automatically runs after tests

### Available Gradle Tasks

#### For individual modules:
```bash
./gradlew test jacocoTestReport
```

#### For aggregated coverage across all modules:
```bash
./gradlew testAndCoverage
```

This will:
1. Run all tests across all submodules
2. Generate individual coverage reports for each module
3. Create an aggregated coverage report combining all modules

#### Coverage verification:
```bash
./gradlew jacocoAggregatedCoverageVerification
```

### Report Locations

- **Individual module XML reports**: `*/build/reports/jacoco/test/jacocoTestReport.xml`
- **Individual module HTML reports**: `*/build/reports/jacoco/test/html/index.html`
- **Aggregated XML report**: `build/reports/jacoco/aggregate/jacocoAggregatedReport.xml`
- **Aggregated HTML report**: `build/reports/jacoco/aggregate/html/index.html`


#### Sample Github Actions Config
```yaml
name: Upload JaCoCo coverage reports
if: always()
uses: actions/upload-artifact@v4
with:
  name: coverage-reports-java-${{ matrix.java }}-${{ matrix.os }}
  path: build/reports/jacoco
```

#### Coverage Thresholds
The initial coverage thresholds are set to 0% to allow gradual adoption. You can increase these values in:
- Individual modules: `build-logic/src/main/kotlin/jacoco-project.gradle.kts`
- Aggregated: `build.gradle.kts` (jacocoAggregatedCoverageVerification task)

#### Customization
To exclude specific classes or packages from coverage:
```kotlin
tasks.jacocoTestReport {
    classDirectories.setFrom(
        classDirectories.files.map {
            fileTree(it).exclude(
                "**/generated/**",
                "**/config/**",
                "**/*Application*"
            )
        }
    )
}
```

### Best Practices
1. Run `./gradlew testAndCoverage` locally before pushing
2. Monitor coverage trends
3. Gradually increase coverage thresholds as code coverage improves
4. Use the HTML reports for detailed coverage analysis
