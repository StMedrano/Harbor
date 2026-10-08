# Parent Android Task 1 compatibility amendment

Status: explicitly approved by the user on 2026-10-06. Original specification and nine milestones remain binding. No runtime app implementation or backend changes have been made.

## Observed blocker

The approved Task 1 test-host command (`gradlew.bat -PparentCiFixture=true --no-daemon --write-verification-metadata sha256 --write-locks testDebugUnitTest`) failed at `:app:checkDebugAarMetadata` with 22 compatibility errors. Compose BOM 2026.09.00 resolves Compose 1.12.1; 11 resolved Android components require compile API 37 and AGP >=9.1.0. The approved API36/AGP9.0.1 graph is incompatible. Unit tests did not run, so this is dependency RED, not proof that the eight configuration assertions fail correctly. Task 1 remains incomplete.

## Proposed bounded correction

For `apps/parent-android` only:

- Compile SDK: 36 -> 37. Keep target SDK 36 and minimum SDK 29.
- Android Gradle Plugin: 9.0.1 -> 9.1.1, the documented supported plugin for API37.
- Gradle wrapper distribution: 9.1.0 -> 9.3.1; verify the official distribution checksum before use. Reuse wrapper scripts/JAR only if compatible with the documented wrapper update procedure.
- Keep JDK17, built-in Kotlin with pinned KGP2.4.0, Compose/serialization plugins2.4.0, KSP2.3.12, SDK3.8.0 and every other approved dependency pin.
- Install API37 into the existing local Android SDK and the isolated parent CI job. Keep existing notification acceptance builds and CI jobs unchanged.

[Official AGP9.1.1 compatibility guidance](https://developer.android.com/build/releases/agp-9-1-0-release-notes) lists API37, Gradle9.3.1 and JDK17. This establishes a proposed compatible toolchain, not compilation proof.

## Resume gate after approval

Apply only the correction above. Rerun the complete Task1 test-host dependency graph; observe the expected missing EnvironmentConfig RED before implementation. A further incompatibility stops for another precise review, rather than suppressing checks or silently changing pins. Generate locks/checksums for the approved graph, then run strict unit/lint/assembly checks and all required CI. No hosted deployment, new permissions, UI scope, child wire protocol or production changes are authorized by this amendment.
