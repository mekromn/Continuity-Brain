# Build validation

Continuity Brain is validated by the repository's `Android CI` workflow on pull requests. CI runs the privacy guard, installs API 37, builds the debug APK with Gradle 9.6/JDK 17, runs Android lint, and uploads the APK as a workflow artifact.

This file also gives the initial CI validation branch a harmless documentation-only delta so the full application can be compiled and linted before the milestone is considered healthy.
