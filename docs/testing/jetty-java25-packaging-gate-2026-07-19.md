# Jetty Java 25 packaging gate — 2026-07-19

## Purpose

This gate verifies that the initial embedded Jetty 12.1.11 server and native Jetty WebSocket dependencies compile,
resolve as named JPMS modules, and remain usable from the project's trimmed Java 25 runtime. It does not start
SDRTrunk, exercise a tuner, or change either receiver node.

## Dependency and module layout

- Direct artifacts: `org.eclipse.jetty:jetty-server:12.1.11` and
  `org.eclipse.jetty.websocket:jetty-websocket-jetty-server:12.1.11`.
- The resolved distribution contains nine Jetty jars totaling 2,641,423 bytes (2.519 MiB).
- All nine jars have explicit JPMS descriptors. Their resolved modules are `org.eclipse.jetty.server`,
  `org.eclipse.jetty.http`, `org.eclipse.jetty.io`, `org.eclipse.jetty.util`, `org.eclipse.jetty.websocket.server`,
  `org.eclipse.jetty.websocket.api`, `org.eclipse.jetty.websocket.common`,
  `org.eclipse.jetty.websocket.core.server`, and `org.eclipse.jetty.websocket.core.common`.
- `org.eclipse.jetty.jmx` is an optional (`static`) dependency and is intentionally absent because this slice does not
  enable Jetty JMX instrumentation.
- Jetty and OSHI select SLF4J 2.0.18, so the application's direct SLF4J declaration is aligned to 2.0.18.
- `jdk.httpserver` remains in the application descriptor and runtime image while the legacy web server still exists.

## Commands and results

The following completed successfully with BellSoft OpenJDK 25.0.1 and Gradle 9.4.1 on the development Mac without
launching the application:

```text
./gradlew compileJava syncJpmsMods runtimeImageCurrent
build/jre-current/bin/java --module-path build/jpms-mods --validate-modules
build/jre-current/bin/java --dry-run --module-path build/jpms-mods \
  -m sdr.trunk/io.github.dsheirer.gui.SDRTrunk
build/runtime-current/sdrtrunk-vce/runtime/bin/java --dry-run \
  -cp 'build/runtime-current/sdrtrunk-vce/lib/*' io.github.dsheirer.gui.SDRTrunk
```

The Gradle build succeeded. Both modular and classpath dry runs resolved the main class without executing it. The
module validation reported no conflicts or missing modules. Inspection of the descriptors also confirmed that the
bundled modules contain Jetty's HTTP field-encoder and WebSocket extension-parser service providers. The only build
output was the existing warning that `jdk.incubator.vector` is incubating.

The current trimmed JRE contains 28 JDK/JavaFX modules and 83,823,155 file bytes (79.940 MiB). Jetty's named modules
resolve using that image without adding any JDK module to the existing `runtimeModules` list. Therefore the JRE image
size delta from Jetty is zero; the application distribution grows by the 2.519 MiB of Jetty jars before outer ZIP
compression. The complete staged classpath library directory contains 52,583,894 file bytes (50.148 MiB).

## BOSGAME packaging recommendation

Before deployment, run the existing `runtimeZipWindows` release task and select its Windows x86-64 artifact for
BOSGAME. The Windows Java 25 target JDKs were not present in the local Gradle cache during this focused gate, so that
task will download the configured BellSoft target JDKs on its first run. Verify the resulting x86-64 archive with the
same no-execution checks, confirm that all nine Jetty jars are in its `lib` directory, and only then use it for the
bounded BOSGAME lifecycle and synthetic-stream tests. The Windows aarch64 artifact is not a BOSGAME deployment
candidate.
