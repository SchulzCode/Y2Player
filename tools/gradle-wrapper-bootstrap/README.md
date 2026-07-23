# Gradle bootstrap source

The project was generated in an environment without a Gradle installation, so
`gradle-wrapper.jar` is a tiny self-contained bootstrap rather than the normal
Gradle wrapper JAR. Its complete source is included here for auditability.

It reads `gradle-wrapper.properties`, downloads Gradle 9.3.1, verifies the
configured SHA-256, rejects ZIP path traversal, extracts the distribution under
`~/.gradle/wrapper/dists`, and launches Gradle.

Once the project has successfully built on your computer, you may replace it
with Gradle's standard wrapper:

```bash
./gradlew wrapper --gradle-version 9.3.1 --distribution-type bin
```
