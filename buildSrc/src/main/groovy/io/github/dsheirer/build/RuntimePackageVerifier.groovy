package io.github.dsheirer.build

import java.security.MessageDigest
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import org.gradle.api.GradleException

/** Package admission independent of Gradle script state, shared by cache-restored ZIP tasks and regressions. */
final class RuntimePackageVerifier {
    static byte[] archiveEntryBytes(ZipFile zip, String entryName) {
        def entry = zip.getEntry(entryName)

        if(entry == null || entry.directory) {
            throw new GradleException("Required package entry is missing: ${entryName}")
        }

        zip.getInputStream(entry).withCloseable { input -> input.readAllBytes() }
    }

    static String archiveEntryText(ZipFile zip, String entryName) {
        new String(archiveEntryBytes(zip, entryName), java.nio.charset.StandardCharsets.UTF_8)
    }

    static void verifyVoiceDecryptionEntries(Set<String> entries, String archiveName,
            List<String> bundledVoiceDecryptionEntries, List<String> retiredEncryptionEntries,
            List<String> retiredEncryptionPrefixes) {
        Set<String> missing = (bundledVoiceDecryptionEntries as Set) - entries
        if(!missing.isEmpty()) {
            throw new GradleException("Bundled voice decryption is missing from ${archiveName}: ${missing}")
        }
        Set<String> retired = entries.findAll { entry ->
            retiredEncryptionEntries.contains(entry) || retiredEncryptionPrefixes.any { entry.startsWith(it) }
        } as Set
        if(!retired.isEmpty()) {
            throw new GradleException("Retired encryption UI, vault or module-installation classes remain in " +
                    "${archiveName}: ${retired}")
        }
    }

    static void verify(RuntimeTarget target, File archive, Map metadata) {
        String packagedApplicationName = metadata.applicationName
        String releaseVersion = metadata.releaseVersion
        String updateTrack = metadata.updateTrack
        String updateBuild = metadata.updateBuild
        String libericaJavaVersion = metadata.libericaJavaVersion
        String libericaRuntimeVersion = metadata.libericaRuntimeVersion
        String expectedManifestClassPath = metadata.expectedManifestClassPath
        if(!archive.isFile() || archive.length() == 0) {
            throw new GradleException("Runtime package is missing or empty: ${archive}")
        }

        String root = target.packageRoot as String

        if(archive.name != "${root}.zip") {
            throw new GradleException("Package root ${root} does not match archive ${archive.name}")
        }
        String runtimeLauncher = "${root}/bin/java${target.windows ? '.exe' : ''}"
        String applicationJar = "${root}/lib/${packagedApplicationName}-${releaseVersion}.jar"
        ZipFile zip = new ZipFile(archive)

        try {
            def entries = zip.entries().toList()
            def outsideRoot = entries.findAll { entry ->
                entry.name != "${root}/" && !entry.name.startsWith("${root}/")
            }

            if(!outsideRoot.isEmpty()) {
                throw new GradleException("Package contains entries outside ${root}: ${outsideRoot*.name}")
            }

            def vendorRootEntries = entries.findAll { entry ->
                entry.name.startsWith("${root}/${target.archiveRoot}/")
            }

            if(!vendorRootEntries.isEmpty()) {
                throw new GradleException("Package retains the vendor archive root: ${vendorRootEntries*.name}")
            }

            def emptyLegalFiles = entries.findAll { entry ->
                !entry.directory && entry.name.startsWith("${root}/legal/") && entry.size == 0
            }

            if(!emptyLegalFiles.isEmpty()) {
                throw new GradleException("Package contains empty vendor legal files: ${emptyLegalFiles*.name}")
            }

            def packagedFileNames = entries.findAll { !it.directory }*.name
            def emptyDirectories = entries.findAll { directory ->
                directory.directory && !packagedFileNames.any { it.startsWith(directory.name) }
            }

            if(!emptyDirectories.isEmpty()) {
                throw new GradleException("Package contains empty directories: ${emptyDirectories*.name}")
            }

            def rootLaunchers = entries.findAll { entry ->
                !entry.directory && entry.name.startsWith("${root}/Start VCE.")
            }*.name as Set
            Set<String> expectedRootLaunchers = ["${root}/${target.rootLauncher}"] as Set

            if(rootLaunchers != expectedRootLaunchers) {
                throw new GradleException("Package ${archive.name} has unexpected root launchers: ${rootLaunchers}")
            }

            [
                    runtimeLauncher,
                    "${root}/${target.rootLauncher}",
                    "${root}/bin/${packagedApplicationName}",
                    "${root}/bin/${packagedApplicationName}.bat",
                    applicationJar,
                    "${root}/stats-web/index.html",
                    "${root}/LICENSE",
                    "${root}/RUNTIME-LICENSE",
                    "${root}/NOTICE",
                    "${root}/release"
            ].each { archiveEntryBytes(zip, it) }

            String rootLauncherText = archiveEntryText(zip, "${root}/${target.rootLauncher}")

            if(target.windows) {
                if(!rootLauncherText.contains('set "JAVA_HOME=%~dp0"') ||
                        !rootLauncherText.contains('call "%~dp0bin\\sdrtrunk-vce.bat" %*') ||
                        !rootLauncherText.contains('if errorlevel 1 pause')) {
                    throw new GradleException("Root launcher does not use the bundled Windows runtime, forward to " +
                            "the application launcher, and pause when it fails")
                }
            }
            else if(!rootLauncherText.startsWith('#!/bin/sh\n') ||
                    !rootLauncherText.contains('JAVA_HOME="$launcher_root" exec "$launcher_root/bin/sdrtrunk-vce" "$@"')) {
                throw new GradleException("Root launcher does not use the bundled Unix runtime and forward to the " +
                        "application launcher")
            }

            if(zip.getEntry("${root}/bin/javac") != null || zip.getEntry("${root}/bin/javac.exe") != null) {
                throw new GradleException("Package ${archive.name} contains a JDK instead of the smaller JRE")
            }

            String releaseText = archiveEntryText(zip, "${root}/release")

            if(!releaseText.contains('IMPLEMENTOR="BellSoft"') ||
                    !releaseText.contains("JAVA_VERSION=\"${libericaJavaVersion}\"") ||
                    !releaseText.contains("JAVA_RUNTIME_VERSION=\"${libericaRuntimeVersion}-LTS\"") ||
                    !releaseText.contains("OS_ARCH=\"${target.releaseArchitecture}\"")) {
                throw new GradleException("Package ${archive.name} has unexpected Liberica runtime metadata")
            }

            ["${root}/bin/${packagedApplicationName}", "${root}/bin/${packagedApplicationName}.bat"].each { launcherName ->
                String launcher = archiveEntryText(zip, launcherName)

                [
                        '--add-modules=jdk.incubator.vector,jdk.httpserver',
                        '--enable-native-access=ALL-UNNAMED',
                        '--enable-native-access=javafx.graphics',
                        '-Xmx2g',
                        '-XX:+UseCompactObjectHeaders'
                ].each { requiredArgument ->
                    if(!launcher.contains(requiredArgument)) {
                        throw new GradleException("Launcher ${launcherName} is missing ${requiredArgument}")
                    }
                }

                String applicationJarName = "${packagedApplicationName}-${releaseVersion}.jar"
                String expectedClasspath = launcherName.endsWith('.bat') ?
                        "set CLASSPATH=%APP_HOME%\\lib\\${applicationJarName}" :
                        "CLASSPATH=\$APP_HOME/lib/${applicationJarName}"
                String classpathPrefix = launcherName.endsWith('.bat') ? 'set CLASSPATH=' : 'CLASSPATH='

                if(launcher.readLines().findAll { it.startsWith(classpathPrefix) } != [expectedClasspath]) {
                    throw new GradleException("Launcher ${launcherName} does not use the application manifest classpath")
                }
            }

            byte[] applicationJarBytes = archiveEntryBytes(zip, applicationJar)
            new JarInputStream(new ByteArrayInputStream(applicationJarBytes)).withCloseable { applicationJarInput ->
                def manifest = applicationJarInput.manifest

                if(manifest == null ||
                        manifest.mainAttributes.getValue('Class-Path') != expectedManifestClassPath ||
                        manifest.mainAttributes.getValue('Implementation-Version') != releaseVersion ||
                        manifest.mainAttributes.getValue('Update-Track') != updateTrack ||
                        manifest.mainAttributes.getValue('Update-Build') != updateBuild) {
                    throw new GradleException("Application metadata does not match package ${archive.name}")
                }

                Set<String> applicationEntries = [] as Set
                def applicationEntry

                while((applicationEntry = applicationJarInput.getNextJarEntry()) != null) {
                    applicationEntries.add(applicationEntry.name)
                }

                verifyVoiceDecryptionEntries(applicationEntries, archive.name,
                        metadata.requiredVoiceEntries, metadata.retiredVoiceEntries, metadata.retiredVoicePrefixes)

                Set<String> requiredApplicationIcons = [
                        'images/app/vce-app-icon.png',
                        'images/app/vce-app-icon.svg',
                        'images/app/vce-wordmark.png'
                ] as Set
                Set<String> missingApplicationIcons = requiredApplicationIcons - applicationEntries

                if(!missingApplicationIcons.isEmpty()) {
                    throw new GradleException("Application branding is missing from ${archive.name}: " +
                            "${missingApplicationIcons}")
                }

                Set<String> retiredApplicationIcons = [
                        'images/app/sdr-trunk-icon.png',
                        'images/app/sdr-trunk-icon.svg'
                ] as Set
                Set<String> bundledRetiredApplicationIcons = retiredApplicationIcons.intersect(applicationEntries)

                if(!bundledRetiredApplicationIcons.isEmpty()) {
                    throw new GradleException("Retired application branding remains in ${archive.name}: " +
                            "${bundledRetiredApplicationIcons}")
                }
            }

            if(target.id == 'OsxAarch64') {
                String nativeEntry = 'org/usb4java/darwin-aarch64/libusb4java.dylib'
                byte[] nativeLibrary
                def nativeMetadata
                byte[] nativeJar = archiveEntryBytes(zip, "${root}/lib/usb4java-native-libraries-1.3.3.jar")
                new JarInputStream(new ByteArrayInputStream(nativeJar)).withCloseable { input ->
                    def entry
                    while((entry = input.getNextJarEntry()) != null) {
                        if(entry.name == nativeEntry) {
                            nativeLibrary = input.readAllBytes()
                        }
                        if(entry.name == 'META-INF/sdrtrunk-vce-native-usb.json') {
                            nativeMetadata = new groovy.json.JsonSlurper().parseText(
                                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        }
                    }
                }
                if(nativeLibrary == null || nativeMetadata?.libusb_version != '1.0.30' ||
                        nativeMetadata?.jni_version != '1.3.0' || nativeMetadata?.native_entry?.path != nativeEntry ||
                        MessageDigest.getInstance('SHA-256').digest(nativeLibrary).encodeHex().toString() !=
                                nativeMetadata?.native_entry?.sha256) {
                    throw new GradleException('macOS ARM package is missing its verified self-contained USB library')
                }
                archiveEntryBytes(zip, "${root}/legal/native-usb/provenance.json")
            }

        }
        finally {
            zip.close()
        }
    }
}
