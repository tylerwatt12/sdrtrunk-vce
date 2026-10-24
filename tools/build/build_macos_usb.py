#!/usr/bin/env python3
"""Replace the Tahoe ARM JNI payload with a pinned, self-contained libusb build.

Only the modern macOS ARM entry changes. Older macOS and every other platform
retain the Maven payload. Build caches and compiler output stay under build/.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import platform
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / "build" / "macos-usb"
NATIVE_ENTRY = "org/usb4java/darwin-aarch64/libusb4java.dylib"
JAR_MARKER = "META-INF/sdrtrunk-vce-native-usb.json"
LEGAL = Path("legal/native-usb")
PROVENANCE = LEGAL / "provenance.json"
MAVEN_JAR_SHA256 = "40efa710fa89765cad2f18a8c7472d79a0a42ef78edb5aa3cccbba121dc5c9e7"
SOURCES = {
    "libusb": {
        "version": "1.0.30",
        "archive": "libusb-1.0.30.tar.bz2",
        "url": "https://github.com/libusb/libusb/releases/download/v1.0.30/libusb-1.0.30.tar.bz2",
        "sha256": "fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf",
    },
    "jni": {
        "version": "1.3.0",
        "archive": "libusb4java-1.3.0.tar.gz",
        "url": "https://codeload.github.com/usb4java/libusb4java/tar.gz/2df684b0bb5495276858dcc6c945b0ca2e13a6c5",
        "sha256": "f4203f5b4e06d8ace8a7218cc9175b3c45dd5a4e46961dd8690d227774bc554d",
    },
}
FRAMEWORKS = ["-framework", "IOKit", "-framework", "CoreFoundation", "-framework", "Security", "-lobjc"]


class BuildError(RuntimeError):
    pass


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def run(command, *, cwd=None, env=None, log=None):
    result = subprocess.run([str(value) for value in command], cwd=cwd, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if log:
        with log.open("a") as stream:
            stream.write(result.stdout)
    if result.returncode:
        raise BuildError(f"{Path(command[0]).name} failed; see build/macos-usb/build.log")
    return result.stdout


def source_archive(spec):
    downloads = CACHE / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    target = downloads / spec["archive"]
    if target.is_file():
        if digest(target) != spec["sha256"]:
            raise BuildError(f"Cached source checksum mismatch: {target.name}")
        return target
    with urllib.request.urlopen(spec["url"], timeout=60) as response:
        data = response.read(16 * 1024 * 1024 + 1)
    if len(data) > 16 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != spec["sha256"]:
        raise BuildError(f"Source checksum mismatch: {target.name}")
    temporary = target.with_suffix(target.suffix + ".download")
    temporary.write_bytes(data)
    temporary.replace(target)
    return target


def extract_source(archive, destination):
    destination.mkdir()
    with tarfile.open(archive) as source:
        # Support the system Python on macOS while refusing links and path escapes.
        for member in source.getmembers():
            path = PurePosixPath(member.name)
            windows_path = PureWindowsPath(member.name)
            if path.is_absolute() or windows_path.drive or ".." in path.parts or \
                    ".." in windows_path.parts or not (member.isdir() or member.isfile()):
                raise BuildError(f"Unsafe source archive entry: {archive.name}")
        source.extractall(destination)
    roots = list(destination.iterdir())
    if len(roots) != 1 or not roots[0].is_dir():
        raise BuildError(f"Unexpected source archive layout: {archive.name}")
    return roots[0]


def validate_native(library, log=None):
    architectures = run(["lipo", "-archs", library], log=log).strip()
    if architectures != "arm64":
        raise BuildError("Rebuilt USB native library must contain exactly arm64")
    lines = run(["otool", "-L", library], log=log).splitlines()[1:]
    dependencies = [line.strip().split(" (", 1)[0] for line in lines]
    allowed_id = "@rpath/libusb4java.dylib"
    if not dependencies or dependencies[0] != allowed_id:
        raise BuildError("Unexpected rebuilt USB native library install name")
    if any(not item.startswith(("/usr/lib/", "/System/Library/")) for item in dependencies[1:]):
        raise BuildError("USB native library contains a non-system dynamic dependency")
    exports = run(["nm", "-gU", library], log=log)
    for name in ("Java_org_usb4java_LibUsb_init", "Java_org_usb4java_LibUsb_getDeviceList",
                 "Java_org_usb4java_LibUsb_claimInterface", "Java_org_usb4java_LibUsb_submitTransfer"):
        if f"_{name}\n" not in exports:
            raise BuildError(f"Required JNI export is missing: {name}")
    run(["codesign", "--verify", "--strict", library], log=log)


def validate_repacked_jar(original, repacked, native_sha256):
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(repacked) as after:
        if set(after.namelist()) != set(before.namelist()) | {JAR_MARKER}:
            raise BuildError("Repacked native JAR entry inventory changed unexpectedly")
        for entry in before.namelist():
            if entry != NATIVE_ENTRY and before.read(entry) != after.read(entry):
                raise BuildError(f"Unrelated native JAR entry changed: {entry}")
        if hashlib.sha256(after.read(NATIVE_ENTRY)).hexdigest() != native_sha256:
            raise BuildError("Repacked USB native payload checksum mismatch")
        marker = json.loads(after.read(JAR_MARKER))
        if marker != native_marker(native_sha256):
            raise BuildError("Repacked USB native payload marker mismatch")


def native_marker(native_sha256):
    return {"libusb_version": SOURCES["libusb"]["version"], "jni_version": SOURCES["jni"]["version"],
            "native_entry": {"path": NATIVE_ENTRY, "sha256": native_sha256}}


def repack(original, target, library):
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(target, "w") as result:
        if NATIVE_ENTRY not in source.namelist() or JAR_MARKER in source.namelist():
            raise BuildError("Expected unmodified Maven 1.3.3 native JAR")
        for entry in source.infolist():
            result.writestr(entry, library.read_bytes() if entry.filename == NATIVE_ENTRY else source.read(entry))
        entry = zipfile.ZipInfo(JAR_MARKER, (2026, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        result.writestr(entry, json_bytes(native_marker(digest(library))))
    validate_repacked_jar(original, target, digest(library))


RELINK = """#!/bin/sh
# Run from this directory with a replacement libusb-1.0.a built for macOS arm64.
set -eu
if [ "$#" -ne 1 ]; then
    echo 'Usage: sh relink.sh /path/to/replacement/libusb-1.0.a' >&2
    exit 2
fi
clang -arch arm64 -mmacosx-version-min=12.0 -dynamiclib \\
    -Wl,-install_name,@rpath/libusb4java.dylib \\
    -Wl,-force_load,libusb4java-jni.a "$1" \\
    -framework IOKit -framework CoreFoundation -framework Security -lobjc \\
    -o libusb4java.dylib
codesign --force --sign - --timestamp=none libusb4java.dylib
echo 'Replace org/usb4java/darwin-aarch64/libusb4java.dylib in the native JAR with this file.'
"""


def compile_native(work, archives):
    if platform.system() != "Darwin" or platform.machine() not in ("arm64", "aarch64"):
        raise BuildError("Building portable macOS USB natives requires an ARM Mac; use --prebuilt DIR elsewhere")
    java = Path(os.environ.get("JAVA_HOME", ""))
    if not (java / "include/jni.h").is_file():
        javac = shutil.which("javac")
        java = Path(javac).resolve().parent.parent if javac else Path("/")
    if not (java / "include/jni.h").is_file():
        raise BuildError("A Java 25 JDK with JNI headers is required (set JAVA_HOME)")
    version = run([java / "bin/javac", "-version"])
    if not version.startswith("javac 25"):
        raise BuildError("A Java 25 JDK is required for the portable USB build")
    log = CACHE / "build.log"
    log.write_text("")
    libusb = extract_source(archives["libusb"], work / "libusb-source")
    jni = extract_source(archives["jni"], work / "jni-source")
    # Keep developer shell include/library overrides out of this hermetic build.
    env = {key: value for key, value in os.environ.items() if key not in
           ("CFLAGS", "CPPFLAGS", "CXXFLAGS", "LDFLAGS", "LIBRARY_PATH", "CPATH", "C_INCLUDE_PATH",
            "CPLUS_INCLUDE_PATH", "PKG_CONFIG_PATH", "SDKROOT", "MACOSX_DEPLOYMENT_TARGET")}
    flags = ["-O2", "-fPIC", "-arch", "arm64", "-mmacosx-version-min=12.0", f"-ffile-prefix-map={work}=."]
    env.update({"CC": "clang", "CFLAGS": shlex.join(flags),
                "LDFLAGS": "-arch arm64 -mmacosx-version-min=12.0", "MACOSX_DEPLOYMENT_TARGET": "12.0"})
    run(["sh", "configure", "--disable-shared", "--enable-static", "--disable-examples-build",
         "--disable-tests-build"], cwd=libusb, env=env, log=log)
    run(["make", f"-j{min(os.cpu_count() or 2, 8)}"], cwd=libusb, env=env, log=log)
    objects = work / "objects"
    objects.mkdir()
    for source in sorted((jni / "src").glob("*.c")):
        run(["clang", *flags,
             f"-I{java / 'include'}", f"-I{java / 'include/darwin'}", f"-I{libusb / 'libusb'}",
             "-c", source, "-o", objects / (source.stem + ".o")], env=env, log=log)
    object_archive = work / "libusb4java-jni.a"
    run(["ar", "rcs", object_archive, *sorted(objects.glob("*.o"))], env=env, log=log)
    library = work / "libusb4java.dylib"
    run(["clang", "-arch", "arm64", "-mmacosx-version-min=12.0", "-dynamiclib",
         "-Wl,-install_name,@rpath/libusb4java.dylib", f"-Wl,-force_load,{object_archive}",
         libusb / "libusb/.libs/libusb-1.0.a", *FRAMEWORKS, "-o", library], env=env, log=log)
    run(["codesign", "--force", "--sign", "-", "--timestamp=none", library], log=log)
    validate_native(library, log)
    return library, object_archive, libusb / "COPYING", jni / "LICENSE.md"


def expected_files(original):
    return {original.name, *((LEGAL / spec["archive"]).as_posix() for spec in SOURCES.values()),
            (LEGAL / "libusb-COPYING").as_posix(), (LEGAL / "libusb4java-LICENSE.md").as_posix(),
            (LEGAL / "libusb4java-jni.a").as_posix(), (LEGAL / "relink.sh").as_posix(),
            (LEGAL / "README.txt").as_posix()}


def validate_inventory(directory, original, *, complete):
    expected = expected_files(original) | {PROVENANCE.as_posix()}
    directories = {parent.as_posix() for relative in expected for parent in Path(relative).parents
                   if parent.as_posix() != "."}
    actual = set()
    if directory.is_symlink():
        raise BuildError("USB payload directory must not be a symbolic link")
    if directory.exists() and not directory.is_dir():
        raise BuildError("USB payload output must be a directory")
    for path in directory.rglob("*"):
        relative = path.relative_to(directory).as_posix()
        if path.is_symlink():
            raise BuildError(f"USB payload contains a symbolic link: {relative}")
        if path.is_file() and relative in expected:
            actual.add(relative)
        elif not (path.is_dir() and relative in directories):
            raise BuildError(f"USB payload contains an unexpected entry: {relative}")
    if complete and actual != expected:
        raise BuildError("Prepared USB payload file inventory is incomplete")


def read_prepared(directory, original):
    validate_inventory(directory, original, complete=True)
    try:
        metadata = json.loads((directory / PROVENANCE).read_text())
        expected = native_marker(metadata["native_entry"]["sha256"])
        if any(metadata.get(key) != value for key, value in expected.items()) or \
                metadata["input_jar_sha256"] != digest(original) or \
                metadata["builder_sha256"] != digest(Path(__file__)) or metadata["sources"] != SOURCES:
            raise BuildError("Prepared USB payload provenance does not match this build")
        if set(metadata["file_sha256"]) != expected_files(original):
            raise BuildError("Prepared USB payload file inventory is incomplete")
        for relative, expected_digest in metadata["file_sha256"].items():
            if digest(directory / relative) != expected_digest:
                raise BuildError(f"Prepared USB payload checksum mismatch: {relative}")
        if metadata["output_jar_sha256"] != digest(directory / original.name):
            raise BuildError("Prepared USB native JAR checksum mismatch")
        for spec in SOURCES.values():
            if digest(directory / LEGAL / spec["archive"]) != spec["sha256"]:
                raise BuildError("Prepared USB source archive checksum mismatch")
        validate_repacked_jar(original, directory / original.name, expected["native_entry"]["sha256"])
        return metadata
    except (OSError, KeyError, TypeError, ValueError, zipfile.BadZipFile) as error:
        raise BuildError("Prepared USB native payload is missing or invalid") from error


def prepare(output, original, prebuilt=None):
    if output.is_symlink():
        raise BuildError("USB payload directory must not be a symbolic link")
    output, original = output.resolve(), original.resolve()
    if not output.is_relative_to(ROOT / "build"):
        raise BuildError("USB build output must be inside this checkout's build directory")
    if digest(original) != MAVEN_JAR_SHA256:
        raise BuildError("Expected the pinned usb4java-native-libraries 1.3.3 Maven JAR")
    # Refuse unknown content before any rebuild, including when provenance is stale.
    validate_inventory(output, original, complete=False)
    if prebuilt is not None:
        prepared = prebuilt.absolute()
        metadata = read_prepared(prepared, original)
        prepared = prepared.resolve()
        validate_inventory(output, original, complete=False)
        output.mkdir(parents=True, exist_ok=True)
        if prepared != output:
            for relative in [*metadata["file_sha256"], PROVENANCE.as_posix()]:
                target = output / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(prepared / relative, target)
        return metadata
    if (output / PROVENANCE).is_file():
        try:
            return read_prepared(output, original)
        except BuildError:
            pass  # Source or builder changed: prepare a fresh payload before replacing output.
    if platform.system() != "Darwin" or platform.machine() not in ("arm64", "aarch64"):
        raise BuildError("Building portable macOS USB natives requires an ARM Mac; use --prebuilt DIR elsewhere")
    CACHE.mkdir(parents=True, exist_ok=True)
    archives = {name: source_archive(spec) for name, spec in SOURCES.items()}
    with tempfile.TemporaryDirectory(prefix="work-", dir=CACHE) as temporary:
        work = Path(temporary)
        library, objects, libusb_license, jni_license = compile_native(work, archives)
        stage = work / "output"
        legal = stage / LEGAL
        legal.mkdir(parents=True)
        repack(original, stage / original.name, library)
        for name, archive in archives.items():
            shutil.copyfile(archive, legal / SOURCES[name]["archive"])
        shutil.copyfile(libusb_license, legal / "libusb-COPYING")
        shutil.copyfile(jni_license, legal / "libusb4java-LICENSE.md")
        shutil.copyfile(objects, legal / "libusb4java-jni.a")
        (legal / "relink.sh").write_text(RELINK)
        (legal / "README.txt").write_text(
            "The modern macOS ARM usb4java JNI library is linked with libusb 1.0.30.\n"
            "libusb is LGPL-2.1-or-later; the JNI wrapper is MIT licensed.\n"
            "Unmodified, checksum-pinned source archives and the JNI object archive are included.\n"
            "To use a modified libusb, extract its source archive, run ./configure --disable-shared "
            "--enable-static with CFLAGS='-O2 -fPIC -arch arm64 -mmacosx-version-min=12.0', then make.\n"
            "On an ARM Mac with Xcode command line tools, run sh relink.sh against the resulting "
            "libusb/.libs/libusb-1.0.a. The script signs the replacement library and names the JAR entry to replace.\n"
            "No receiver interface is claimed by this build.\n")
        metadata = {**native_marker(digest(library)), "sources": SOURCES,
                    "input_jar_sha256": digest(original), "output_jar_sha256": digest(stage / original.name),
                    "builder_sha256": digest(Path(__file__)), "architecture": "arm64", "minimum_macos": "12.0",
                    "file_sha256": {path.relative_to(stage).as_posix(): digest(path)
                                    for path in sorted(stage.rglob("*")) if path.is_file()}}
        (stage / PROVENANCE).write_bytes(json_bytes(metadata))
        read_prepared(stage, original)
        validate_inventory(output, original, complete=False)
        output.mkdir(parents=True, exist_ok=True)
        shutil.copytree(stage, output, dirs_exist_ok=True)
        return metadata


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--native-jar", type=Path, required=True)
    parser.add_argument("--prebuilt", type=Path, help="Reuse a verified directory produced by this exact builder")
    options = parser.parse_args(argv)
    try:
        metadata = prepare(options.output, options.native_jar, options.prebuilt)
    except (BuildError, OSError) as error:
        print(f"macOS USB build: {error}", file=sys.stderr)
        return 1
    print(f"Prepared portable macOS ARM USB natives: libusb {metadata['libusb_version']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
