# Jacoco Code Coverage Setup

This project has been configured with Jacoco code coverage reporting optimized for CircleCI integration.

## Configuration Details

### What's Configured
- **Individual module coverage**: Each submodule generates its own coverage reports 
- **Aggregated coverage**: Combined coverage report across all modules  
- **CircleCI optimization**: XML reports generated in standardized paths
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

### CircleCI Integration

#### Sample CircleCI Config
```yaml
version: 2.1

jobs:
  test-and-coverage:
    docker:
      - image: cimg/openjdk:17.0
    steps:
      - checkout
      - run:
          name: Run tests and generate coverage
          command: |
            cd projects/viaduct/oss
            ./gradlew testAndCoverage
      - store_test_results:
          path: projects/viaduct/oss/build/test-results
      - store_artifacts:
          path: projects/viaduct/oss/build/reports/jacoco/aggregate
          destination: coverage-reports
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
2. Monitor coverage trends in CircleCI
3. Gradually increase coverage thresholds as code coverage improves
4. Use the HTML reports for detailed coverage analysis
