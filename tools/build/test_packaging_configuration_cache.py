#!/usr/bin/env python3
"""Exercise production packaging tasks with tiny, offline runtime fixtures.

Run directly with Python 3 and JDK 25 available. Gradle uses the repository wrapper
and its normal dependency cache; no vendor runtimes or application dependencies are
downloaded. Fixtures live in a disposable internal temporary directory. Set
VCE_PACKAGING_TEST_LOG_DIRECTORY to retain the raw Gradle output for each phase.
"""

import hashlib
import http.server
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import threading
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PACKAGE_ROOT = "vce-fixture"
ARCHIVE_NAME = PACKAGE_ROOT + ".zip"
VOICE_ENTRY = "example/RequiredVoiceProvider.class"
RETIRED_VOICE_ENTRY = "example/RetiredVoiceEditor.class"
RETIRED_VOICE_PREFIX = "example/retired/"
BRANDING = ("images/app/vce-app-icon.png", "images/app/vce-app-icon.svg",
            "images/app/vce-wordmark.png")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def jar_bytes(version="fixture-dev", classpath="dependency.jar", track="none", build="0", add=(), omit=()):
    output = io.BytesIO()
    manifest = (f"Manifest-Version: 1.0\r\nClass-Path: {classpath}\r\n"
                f"Implementation-Version: {version}\r\nUpdate-Track: {track}\r\n"
                f"Update-Build: {build}\r\n\r\n")
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", manifest)
        for name in (*BRANDING, VOICE_ENTRY, *add):
            if name not in omit:
                archive.writestr(name, b"fixture")
    return output.getvalue()


def make_runtime():
    output = io.BytesIO()
    files = {
        "bin/java": b"#!/bin/sh\nexit 0\n",
        "LICENSE": b"fixture vendor license\n",
        "release": (b'IMPLEMENTOR="BellSoft"\nJAVA_VERSION="25.0.1"\n'
                    b'JAVA_RUNTIME_VERSION="25.0.1+8-LTS"\nOS_ARCH="x86_64"\n'),
        "legal/java.base/LICENSE": b"fixture legal notice\n",
    }
    with tarfile.open(fileobj=output, mode="w:gz") as archive:
        for name, contents in files.items():
            entry = tarfile.TarInfo("vendor-runtime/" + name)
            entry.size = len(contents)
            entry.mode = 0o755 if name == "bin/java" else 0o644
            archive.addfile(entry, io.BytesIO(contents))
        link = tarfile.TarInfo("vendor-runtime/legal/java.desktop/LICENSE")
        link.type = tarfile.SYMTYPE
        link.linkname = "../java.base/LICENSE"
        archive.addfile(link)
    return output.getvalue()


def write_inputs(work, runtime, url):
    source = work / "application"
    (source / "bin").mkdir(parents=True)
    (source / "lib").mkdir()
    (source / "stats-web").mkdir()
    arguments = ("--add-modules=jdk.incubator.vector,jdk.httpserver "
                 "--enable-native-access=ALL-UNNAMED "
                 "--enable-native-access=javafx.graphics -Xmx2g -XX:+UseCompactObjectHeaders")
    (source / "bin/sdrtrunk-vce").write_text(
        "#!/bin/sh\nCLASSPATH=$APP_HOME/lib/sdrtrunk-vce-fixture-dev.jar\n" + arguments + "\n")
    (source / "bin/sdrtrunk-vce.bat").write_text(
        "@echo off\nset CLASSPATH=%APP_HOME%\\lib\\sdrtrunk-vce-fixture-dev.jar\n" + arguments + "\n")
    (source / "lib/sdrtrunk-vce-fixture-dev.jar").write_bytes(jar_bytes())
    (source / "stats-web/index.html").write_text("<!doctype html><title>Fixture</title>\n")
    for name in ("LICENSE", "NOTICE"):
        (source / name).write_text("fixture application notice\n")
    shutil.copyfile(ROOT / "packaging/launchers/Start VCE.sh", work / "Start VCE.sh")
    specification = {
        "target": {
            "id": "LinuxX86_64", "targetName": PACKAGE_ROOT, "packageRoot": PACKAGE_ROOT,
            "archiveName": "fixture-runtime.tar.gz", "archiveRoot": "vendor-runtime",
            "archiveType": "tar.gz", "url": url, "size": len(runtime),
            "sha256": hashlib.sha256(runtime).hexdigest(), "releaseArchitecture": "x86_64",
            "rootLauncher": "Start VCE.sh", "windows": False,
            "archiveFile": str(work / "runtime-cache/fixture-runtime.tar.gz"),
        },
        "metadata": {
            "applicationName": "sdrtrunk-vce", "releaseVersion": "fixture-dev",
            "updateTrack": "none", "updateBuild": "0", "libericaJavaVersion": "25.0.1",
            "libericaRuntimeVersion": "25.0.1+8", "expectedManifestClassPath": "dependency.jar",
            "requiredVoiceEntries": [VOICE_ENTRY], "retiredVoiceEntries": [RETIRED_VOICE_ENTRY],
            "retiredVoicePrefixes": [RETIRED_VOICE_PREFIX],
        },
    }
    (work / "specification.json").write_text(json.dumps(specification))
    images = work / "archive-set"
    images.mkdir()
    for number in range(6):
        (images / f"vce-platform{number}-fixture.zip").write_bytes(b"fixture package")
    gates = work / "gates"
    gates.mkdir()
    release_metadata = "version=0.6.2-alpha-999\ntitle=Fixture release\nstatus=approved\n"
    (gates / "approved.properties").write_text(release_metadata)
    (gates / "draft.properties").write_text(release_metadata.replace("approved", "draft"))
    (gates / "wrong-version.properties").write_text(release_metadata.replace("alpha-999", "alpha-998"))
    headings = "".join(f"<h2>{heading}</h2>" for heading in
                       ("What", "Added", "Changed", "Fixed", "Removed", "Before You Upgrade"))
    (gates / "complete.html").write_text(headings + "<p>" + " ".join(f"word{x}" for x in range(160)) + "</p>")
    (gates / "missing-section.html").write_text("<h2>What</h2><p>Incomplete release notes.</p>")
    (gates / "brief.html").write_text(headings + "<p>Too brief.</p>")
    (gates / "valid.jar").write_bytes(jar_bytes())
    (gates / "missing.jar").write_bytes(jar_bytes(omit=(VOICE_ENTRY,)))
    (gates / "retired.jar").write_bytes(jar_bytes(add=(RETIRED_VOICE_ENTRY,)))
    (gates / "retired-prefix.jar").write_bytes(jar_bytes(add=(RETIRED_VOICE_PREFIX + "Editor.class",)))
    return specification


def package_cases(work, package):
    """Mutate independently valid packages, then require production validation to reject them."""
    with zipfile.ZipFile(package) as archive:
        original = {entry.filename: archive.read(entry) for entry in archive.infolist()}
    cases = []
    root = PACKAGE_ROOT + "/"
    application_jar = root + "lib/sdrtrunk-vce-fixture-dev.jar"

    def add(name, expected, changes=None, remove=(), target=None, raw=None, base=None):
        contents = dict(original if base is None else base)
        for path in remove:
            contents.pop(root + path)
        for path, value in (changes or {}).items():
            contents[root + path] = value
        destination = work / "rejections" / name / ARCHIVE_NAME
        destination.parent.mkdir(parents=True)
        if raw is not None:
            destination.write_bytes(raw)
        else:
            with zipfile.ZipFile(destination, "w") as archive:
                for path, value in contents.items():
                    archive.writestr(path, value)
        cases.append({"name": name, "archive": str(destination), "expected": expected,
                      "targetOverrides": target or {}})

    add("empty-package", "missing or empty", raw=b"")
    add("corrupt-package", "zip", raw=b"not a ZIP")
    outside = dict(original)
    outside["outside-root.txt"] = b"unexpected"
    add("outside-root", "outside", base=outside)
    add("vendor-root-retained", "vendor archive root", {"vendor-runtime/file": b"unexpected"})
    add("empty-legal-notice", "empty vendor legal", {"legal/java.base/LICENSE": b""})
    add("empty-directory", "empty directories", {"unused/": b""})
    add("missing-root-launcher", "unexpected root launchers", remove=("Start VCE.sh",))
    add("extra-root-launcher", "unexpected root launchers", {"Start VCE.bat": b"unexpected"})
    add("broken-root-launcher", "Root launcher", {"Start VCE.sh": b"#!/bin/sh\njava\n"})
    add("missing-runtime-launcher", "Required package entry", remove=("bin/java",))
    add("jdk-instead-of-jre", "JDK", {"bin/javac": b"compiler"})
    add("wrong-runtime-metadata", "runtime metadata", {"release": b'IMPLEMENTOR="Other"\n'})
    launcher = original[root + "bin/sdrtrunk-vce"]
    add("missing-launcher-argument", "missing -Xmx2g",
        {"bin/sdrtrunk-vce": launcher.replace(b"-Xmx2g", b"")})
    add("wrong-launcher-classpath", "manifest classpath",
        {"bin/sdrtrunk-vce": launcher.replace(b"CLASSPATH=$APP_HOME/lib/", b"CLASSPATH=wrong/")})
    add("wrong-application-metadata", "Application metadata",
        {application_jar[len(root):]: jar_bytes(version="wrong")})
    add("wrong-manifest-classpath", "Application metadata",
        {application_jar[len(root):]: jar_bytes(classpath="wrong.jar")})
    add("wrong-update-track", "Application metadata",
        {application_jar[len(root):]: jar_bytes(track="nightly")})
    add("wrong-update-build", "Application metadata",
        {application_jar[len(root):]: jar_bytes(build="123")})
    add("missing-voice-provider", "missing", {application_jar[len(root):]: jar_bytes(omit=(VOICE_ENTRY,))})
    add("retired-voice-entry", "retired", {application_jar[len(root):]: jar_bytes(add=(RETIRED_VOICE_ENTRY,))})
    add("retired-voice-prefix", "retired",
        {application_jar[len(root):]: jar_bytes(add=(RETIRED_VOICE_PREFIX + "Editor.class",))})
    add("missing-branding", "branding", {application_jar[len(root):]: jar_bytes(omit=(BRANDING[0],))})
    add("retired-branding", "Retired application branding",
        {application_jar[len(root):]: jar_bytes(add=("images/app/sdr-trunk-icon.png",))})

    native_entry = "org/usb4java/darwin-aarch64/libusb4java.dylib"
    native_library = b"fixture native library"

    def native_jar(version="1.0.30", jni="1.3.0", digest=None, path=native_entry, library=True, metadata=True):
        output = io.BytesIO()
        details = {"libusb_version": version, "jni_version": jni, "native_entry": {
            "path": path, "sha256": digest or hashlib.sha256(native_library).hexdigest()}}
        with zipfile.ZipFile(output, "w") as archive:
            if library:
                archive.writestr(native_entry, native_library)
            if metadata:
                archive.writestr("META-INF/sdrtrunk-vce-native-usb.json", json.dumps(details))
        return output.getvalue()

    mac = dict(original)
    mac[root + "release"] = original[root + "release"].replace(b'OS_ARCH="x86_64"', b'OS_ARCH="aarch64"')
    mac[root + "lib/usb4java-native-libraries-1.3.3.jar"] = native_jar()
    mac[root + "legal/native-usb/provenance.json"] = b"{}"
    mac_target = {"id": "OsxAarch64", "releaseArchitecture": "aarch64"}
    # A successful control prevents these cases from passing because the synthetic Mac package is invalid.
    add("valid-macos-control", "", base=mac, target=mac_target)
    add("wrong-native-version", "verified self-contained USB",
        {"lib/usb4java-native-libraries-1.3.3.jar": native_jar(version="wrong")}, base=mac, target=mac_target)
    add("wrong-native-digest", "verified self-contained USB",
        {"lib/usb4java-native-libraries-1.3.3.jar": native_jar(digest="0" * 64)}, base=mac, target=mac_target)
    for name, options in (("wrong-jni-version", {"jni": "wrong"}),
                          ("wrong-native-path", {"path": "wrong"}),
                          ("missing-native-library", {"library": False}),
                          ("missing-native-metadata", {"metadata": False})):
        add(name, "verified self-contained USB",
            {"lib/usb4java-native-libraries-1.3.3.jar": native_jar(**options)}, base=mac, target=mac_target)
    add("missing-native-provenance", "Required package entry",
        remove=("legal/native-usb/provenance.json",), base=mac, target=mac_target)
    windows = dict(original)
    windows.pop(root + "Start VCE.sh")
    windows[root + "Start VCE.bat"] = (ROOT / "packaging/launchers/Start VCE.bat").read_bytes()
    windows[root + "bin/java.exe"] = windows.pop(root + "bin/java")
    windows_target = {"id": "WindowsX86_64", "windows": True, "rootLauncher": "Start VCE.bat"}
    add("valid-windows-control", "", base=windows, target=windows_target)
    add("broken-windows-root-launcher", "Root launcher", {"Start VCE.bat": b"java.exe\r\n"},
        base=windows, target=windows_target)
    (work / "rejections/cases.json").write_text(json.dumps(cases))
    return len(cases)


class RuntimeServer(http.server.BaseHTTPRequestHandler):
    payload = b""
    requests = 0

    def do_GET(self):
        type(self).requests += 1
        self.send_response(200)
        self.send_header("Content-Length", str(len(type(self).payload)))
        self.end_headers()
        self.wfile.write(type(self).payload)

    def log_message(self, *_):
        pass


def check():
    runtime = make_runtime()
    RuntimeServer.payload = runtime
    RuntimeServer.requests = 0
    with tempfile.TemporaryDirectory(prefix="vce-packaging-cache-") as temporary:
        work = Path(temporary)
        shutil.copytree(ROOT / "buildSrc", work / "buildSrc",
                        ignore=lambda directory, names: set(names) & {"build", ".gradle"}
                        if Path(directory) == ROOT / "buildSrc" else set())
        fixture = Path(__file__).with_name("fixtures")
        shutil.copyfile(fixture / "packaging-configuration-cache.gradle", work / "build.gradle")
        shutil.copyfile(fixture / "VerifyPackageRejections.groovy",
                        work / "buildSrc/src/main/groovy/io/github/dsheirer/build/VerifyPackageRejections.groovy")
        (work / "settings.gradle").write_text("rootProject.name = 'packaging-cache-fixture'\n")
        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), RuntimeServer) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                spec = write_inputs(work, runtime, f"http://127.0.0.1:{server.server_port}/runtime.tar.gz")
                command = [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")), "-p", str(work),
                           "--configuration-cache", "--configuration-cache-problems=fail", "--offline",
                           "--console=plain", "--max-workers=2"]

                def gradle(label, *tasks, success=True, cache=None, expected=None):
                    print(label, flush=True)
                    result = subprocess.run(command + list(tasks), cwd=ROOT, text=True,
                                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
                    output = result.stdout
                    log_directory = os.environ.get("VCE_PACKAGING_TEST_LOG_DIRECTORY")
                    if log_directory:
                        destination = Path(log_directory)
                        destination.mkdir(parents=True, exist_ok=True)
                        (destination / (re.sub(r"[^a-z0-9]+", "-", label.lower()).strip("-") + ".log")).write_text(output)
                    require((result.returncode == 0) == success, output)
                    if cache:
                        require(cache in output, output)
                    if expected:
                        require(expected.lower() in output.lower(), output)
                    require("configuration cache entry discarded" not in output.lower(), output)
                    for line in output.splitlines():
                        if ("Configuration cache entry" in line or "Reusing configuration cache" in line or
                                line.startswith("> Task :runtimeZip") or line.startswith("Verified LinuxX86_64")):
                            print("  " + line, flush=True)
                    return output

                package_task = "runtimeZipLinuxX86_64"
                archive_set_task = "verifyFixtureArchiveSet"
                tasks = (package_task, archive_set_task, "verifyFixtureGates")
                package = work / "build/image" / ARCHIVE_NAME
                cached_runtime = Path(spec["target"]["archiveFile"])
                gradle("Store configuration while executing packaging actions", *tasks,
                       cache="Configuration cache entry stored.")
                require(package.is_file(), "Packaging did not create its ZIP")
                require(RuntimeServer.requests == 1, "Initial execution must download the tiny runtime exactly once")
                with zipfile.ZipFile(package) as archive:
                    require(archive.read(PACKAGE_ROOT + "/legal/java.desktop/LICENSE") == b"fixture legal notice\n",
                            "Legal symlink was not materialized")
                    require(PACKAGE_ROOT + "/bin/java" in archive.namelist(), "Vendor root was not stripped")
                    require(not any("/vendor-runtime/" in name for name in archive.namelist()),
                            "Vendor root remains in the packaged runtime")
                    for name in ("bin/java", "bin/sdrtrunk-vce", "Start VCE.sh"):
                        require((archive.getinfo(PACKAGE_ROOT + "/" + name).external_attr >> 16) & 0o777 == 0o755,
                                f"Executable permissions were not preserved for {name}")
                baseline = package.read_bytes()
                output = gradle("Reuse stored configuration and ordinary up-to-date checks", *tasks,
                                cache="Reusing configuration cache.")
                require(f":{package_task} UP-TO-DATE" in output, output)
                require(RuntimeServer.requests == 1, "Up-to-date execution unexpectedly fetched the runtime")
                package.unlink()
                output = gradle("Rebuild ZIP through cache-restored copy actions and verifier", *tasks,
                                cache="Reusing configuration cache.")
                require(re.search(rf"> Task :{package_task}\s*\n", output), output)
                require(package.read_bytes() == baseline, "Cache-restored ZIP differs from the initial package")
                cached_runtime.unlink()
                gradle("Fetch a missing cached runtime after cache reuse", *tasks,
                       cache="Reusing configuration cache.")
                require(RuntimeServer.requests == 2, "Missing cached runtime was not fetched")
                corrupted = bytearray(runtime)
                corrupted[-1] ^= 1
                cached_runtime.write_bytes(corrupted)
                gradle("Repair a same-size corrupted cached runtime after cache reuse", *tasks,
                       cache="Reusing configuration cache.")
                require(RuntimeServer.requests == 3, "Same-size corruption escaped checksum validation")
                require(cached_runtime.read_bytes() == runtime, "Corrupted cache was not repaired")
                RuntimeServer.payload = b"bad runtime"
                cached_runtime.write_bytes(corrupted)
                gradle("Reject a bad download and clean its partial file", *tasks, success=False,
                       cache="Reusing configuration cache.", expected="size or checksum verification")
                require(cached_runtime.read_bytes() == corrupted, "Failed download replaced the existing cache")
                require(not list(cached_runtime.parent.glob("*.part-*")), "A failed download left a partial file")
                RuntimeServer.payload = runtime
                cached_runtime.write_bytes(runtime)
                count = package_cases(work, package)
                gradle(f"Reject damaged packages with the production verifier ({count - 2} cases and valid Mac/Windows controls)",
                       "verifyPackageRejections", cache="Configuration cache entry stored.")
                missing = work / "archive-set/vce-platform0-fixture.zip"
                saved = missing.read_bytes()
                missing.unlink()
                gradle("Reject an incomplete six-archive set", *tasks, success=False,
                       cache="Reusing configuration cache.", expected="exactly six")
                missing.write_bytes(saved)
                extra = work / "archive-set/vce-extra-fixture.zip"
                extra.write_bytes(b"unexpected package")
                gradle("Reject an extra current-version archive", *tasks, success=False,
                       cache="Reusing configuration cache.", expected="exactly six")
                for task, reason in (
                        ("rejectDraftNotes", "still a draft"),
                        ("rejectMismatchedNotesVersion", "version must exactly match"),
                        ("rejectMissingNotesSection", "missing the 'Added' section"),
                        ("rejectBriefNotes", "too brief"),
                        ("rejectWrongUpdateChannel", "requires an Alpha version"),
                        ("rejectWrongUpdateBuild", "must have a positive updateBuild"),
                        ("rejectOverflowUpdateBuild", "64-bit integer"),
                        ("rejectMissingJarEntry", "missing"),
                        ("rejectRetiredJarEntry", "Retired"),
                        ("rejectRetiredJarPrefix", "Retired")):
                    gradle("Validate production gate " + task, task, success=False,
                           cache="Configuration cache entry stored.", expected=reason)
            finally:
                server.shutdown()
                thread.join(timeout=2)
    print("Packaging configuration-cache regression checks passed.", flush=True)


class PackagingConfigurationCacheTest(unittest.TestCase):
    def test_packaging_actions_reuse_configuration_and_keep_validation(self):
        check()


if __name__ == "__main__":
    unittest.main()
