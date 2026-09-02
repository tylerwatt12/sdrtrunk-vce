#!/usr/bin/env python3
"""Verify the exact finished release ZIPs before they can be published."""

from __future__ import annotations

import argparse
import hashlib
import io
import plistlib
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath


TARGETS = (
    "linux-aarch64",
    "linux-x86_64",
    "osx-aarch64",
    "osx-x86_64",
    "windows-aarch64",
    "windows-x86_64",
)
REQUIRED_APPLICATION_CLASSES = (
    "io/github/dsheirer/database/upgrade/ApplicationDatabaseMigrator.class",
    "io/github/dsheirer/source/tuner/sdrplay/api/SDRPlayLibraryHelper.class",
    "io/github/dsheirer/source/tuner/sdrplay/api/SDRPlayLibraryPathResolver.class",
)
WINDOWS_COMPATIBILITY_CLASS = "com/sun/java/swing/plaf/windows/WindowsLookAndFeel.class"
WINDOWS_ARGUMENTS = (
    "--add-exports=java.desktop/com.sun.java.swing.plaf.windows=ALL-UNNAMED",
    r"-Djava.library.path=c:\Program Files\SDRplay\API\x64",
)


class VerificationError(RuntimeError):
    """A release archive is incomplete or inconsistent."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def manifest_values(raw: bytes) -> dict[str, str]:
    unfolded: list[str] = []

    for line in raw.decode("utf-8", errors="strict").replace("\r\n", "\n").split("\n"):
        if line.startswith(" ") and unfolded:
            unfolded[-1] += line[1:]
        else:
            unfolded.append(line)

    values: dict[str, str] = {}

    for line in unfolded:
        if ": " in line:
            key, value = line.split(": ", 1)
            values[key] = value

    return values


def safe_members(archive: Path, names: list[str]) -> None:
    require(len(names) == len(set(names)), f"{archive.name} contains duplicate ZIP entries")

    for name in names:
        path = PurePosixPath(name)
        require(not path.is_absolute() and ".." not in path.parts,
                f"{archive.name} contains an unsafe path: {name}")


def verify_binary_architecture(archive: Path, target: str, binary: bytes) -> None:
    if target.startswith("windows-"):
        require(binary[:2] == b"MZ" and len(binary) >= 64,
                f"{archive.name} does not contain a Windows Java launcher")
        pe_offset = struct.unpack_from("<I", binary, 0x3C)[0]
        require(pe_offset + 6 <= len(binary) and binary[pe_offset:pe_offset + 4] == b"PE\0\0",
                f"{archive.name} has an invalid Windows Java launcher")
        machine = struct.unpack_from("<H", binary, pe_offset + 4)[0]
        expected = 0xAA64 if target.endswith("aarch64") else 0x8664
        require(machine == expected, f"{archive.name} contains the wrong Windows architecture")
        return

    if target.startswith("linux-"):
        require(binary[:4] == b"\x7fELF" and len(binary) >= 20,
                f"{archive.name} does not contain a Linux Java launcher")
        require(binary[5] == 1, f"{archive.name} has an unsupported Linux Java launcher byte order")
        machine = struct.unpack_from("<H", binary, 18)[0]
        expected = 183 if target.endswith("aarch64") else 62
        require(machine == expected, f"{archive.name} contains the wrong Linux architecture")
        return

    require(len(binary) >= 8, f"{archive.name} has a truncated macOS Java launcher")
    expected = 0x0100000C if target.endswith("aarch64") else 0x01000007
    magic = binary[:4]

    if magic == b"\xcf\xfa\xed\xfe":
        machines = {struct.unpack_from("<I", binary, 4)[0]}
    elif magic == b"\xfe\xed\xfa\xcf":
        machines = {struct.unpack_from(">I", binary, 4)[0]}
    elif magic in (b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf"):
        entry_size = 20 if magic.endswith(b"\xbe") else 32
        count = struct.unpack_from(">I", binary, 4)[0]
        require(count <= 16 and 8 + count * entry_size <= len(binary),
                f"{archive.name} has an invalid universal macOS Java launcher")
        machines = {struct.unpack_from(">I", binary, 8 + index * entry_size)[0] for index in range(count)}
    else:
        raise VerificationError(f"{archive.name} does not contain a macOS Java launcher")

    require(expected in machines, f"{archive.name} contains the wrong macOS architecture")


def verify_application_jar(archive: Path, raw: bytes, version: str, track: str, build: str,
                           windows: bool) -> None:
    with zipfile.ZipFile(io.BytesIO(raw)) as application:
        bad_entry = application.testzip()
        require(bad_entry is None, f"{archive.name} contains a corrupt application JAR entry: {bad_entry}")
        entries = set(application.namelist())
        require("META-INF/MANIFEST.MF" in entries, f"{archive.name} application JAR has no manifest")
        manifest = manifest_values(application.read("META-INF/MANIFEST.MF"))
        expected = {
            "Implementation-Version": version,
            "Update-Track": track,
            "Update-Build": build,
        }

        for key, value in expected.items():
            require(manifest.get(key) == value,
                    f"{archive.name} application JAR has {key}={manifest.get(key)!r}, expected {value!r}")

        for class_name in REQUIRED_APPLICATION_CLASSES:
            require(class_name in entries, f"{archive.name} application JAR is missing {class_name}")

        if windows:
            require(WINDOWS_COMPATIBILITY_CLASS not in entries,
                    f"{archive.name} contains the non-Windows look-and-feel compatibility class")
        else:
            require(WINDOWS_COMPATIBILITY_CLASS in entries,
                    f"{archive.name} is missing the non-Windows look-and-feel compatibility class")


def verify_console_archive(archive: Path, target: str, version: str, track: str, build: str) -> None:
    with zipfile.ZipFile(archive) as package:
        bad_entry = package.testzip()
        require(bad_entry is None, f"{archive.name} contains a corrupt entry: {bad_entry}")
        names = package.namelist()
        safe_members(archive, names)
        roots = {PurePosixPath(name).parts[0] for name in names if PurePosixPath(name).parts}
        require(len(roots) == 1, f"{archive.name} must contain one top-level application folder")
        root = next(iter(roots))
        require(root == archive.stem,
                f"{archive.name} top-level folder is {root!r}, expected {archive.stem!r}")
        windows = target.startswith("windows-")
        launcher = f"{root}/bin/sdrtrunk-vce.bat" if windows else f"{root}/bin/sdrtrunk-vce"
        java = f"{root}/bin/java.exe" if windows else f"{root}/bin/java"
        application_jar = f"{root}/lib/sdrtrunk-vce-{version}.jar"
        required = (
            launcher,
            java,
            application_jar,
            f"{root}/stats-web/index.html",
            f"{root}/LICENSE",
            f"{root}/NOTICE",
        )

        for name in required:
            require(name in names, f"{archive.name} is missing {name}")

        populated_data = [name for name in names
                          if name.startswith(f"{root}/data/") and not name.endswith("/")]
        require(not populated_data, f"{archive.name} contains populated portable data")
        launcher_text = package.read(launcher).decode("utf-8", errors="strict")

        if windows:
            for argument in WINDOWS_ARGUMENTS:
                require(argument in launcher_text, f"{archive.name} launcher is missing {argument}")
        else:
            for argument in WINDOWS_ARGUMENTS:
                require(argument not in launcher_text,
                        f"{archive.name} non-Windows launcher contains the Windows argument {argument}")

        verify_binary_architecture(archive, target, package.read(java))
        verify_application_jar(archive, package.read(application_jar), version, track, build, windows)


def mac_app_version(version: str) -> str:
    parts = version.split("-", 1)[0].split(".")
    require(bool(parts) and all(part.isdigit() for part in parts),
            f"Cannot derive the macOS application version from {version!r}")
    return ".".join((["1"] + parts[1:]) if parts[0] == "0" else parts)


def verify_mac_app_archive(archive: Path, version: str, track: str, build: str) -> None:
    with zipfile.ZipFile(archive) as package:
        bad_entry = package.testzip()
        require(bad_entry is None, f"{archive.name} contains a corrupt entry: {bad_entry}")
        names = package.namelist()
        safe_members(archive, names)
        meaningful = [name for name in names if not name.startswith("__MACOSX/") and "/._" not in name]
        roots = {PurePosixPath(name).parts[0] for name in meaningful if PurePosixPath(name).parts}
        require(roots == {"sdrtrunk-vce.app"},
                f"{archive.name} must contain only the sdrtrunk-vce.app application")
        root = "sdrtrunk-vce.app/Contents"
        launcher = f"{root}/MacOS/sdrtrunk-vce"
        runtime_java = f"{root}/runtime/Contents/Home/bin/java"
        application_jar = f"{root}/app/mods/sdrtrunk-vce-jpms.jar"
        configuration = f"{root}/app/sdrtrunk-vce.cfg"
        required = (
            f"{root}/Info.plist",
            launcher,
            runtime_java,
            application_jar,
            configuration,
            f"{root}/app/stats-web/index.html",
            f"{root}/app/LICENSE",
            f"{root}/app/NOTICE",
        )

        for name in required:
            require(name in meaningful, f"{archive.name} is missing {name}")

        populated_data = [name for name in meaningful if "/data/" in name and not name.endswith("/")]
        require(not populated_data, f"{archive.name} contains populated portable data")
        verify_binary_architecture(archive, "osx-aarch64", package.read(launcher))
        verify_binary_architecture(archive, "osx-aarch64", package.read(runtime_java))
        verify_application_jar(archive, package.read(application_jar), version, track, build, False)

        settings = package.read(configuration).decode("utf-8", errors="strict")
        required_settings = (
            "app.mainmodule=sdr.trunk/io.github.dsheirer.gui.SDRTrunk",
            "-Dsdrtrunk.stats.web.root=$APPDIR/stats-web",
            "java-options=--module-path",
            "java-options=$APPDIR/mods",
        )

        for setting in required_settings:
            require(setting in settings, f"{archive.name} configuration is missing {setting}")

        for argument in WINDOWS_ARGUMENTS:
            require(argument not in settings,
                    f"{archive.name} macOS configuration contains the Windows argument {argument}")

        information = plistlib.loads(package.read(f"{root}/Info.plist"))
        expected_app_version = mac_app_version(version)
        require(information.get("CFBundleExecutable") == "sdrtrunk-vce",
                f"{archive.name} has the wrong macOS executable name")
        require(information.get("CFBundleShortVersionString") == expected_app_version,
                f"{archive.name} has the wrong macOS application version")
        require(information.get("CFBundleVersion") == expected_app_version,
                f"{archive.name} has the wrong macOS bundle version")


def checksum(path: Path) -> str:
    digest = hashlib.sha256()

    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)

    return digest.hexdigest()


def verify(directory: Path, version: str, track: str, build: str, include_mac_app: bool,
           checksum_file: Path) -> None:
    expected = {f"sdrtrunk-vce-{target}-v{version}.zip" for target in TARGETS}
    mac_app_name = f"sdrtrunk-vce-macos-app-aarch64-v{version}.zip"

    if include_mac_app:
        expected.add(mac_app_name)

    actual = {path.name for path in directory.glob("sdrtrunk-vce-*.zip")}
    require(actual == expected,
            f"Release ZIP set does not match. Missing: {sorted(expected - actual)}; unexpected: {sorted(actual - expected)}")

    for target in TARGETS:
        verify_console_archive(directory / f"sdrtrunk-vce-{target}-v{version}.zip", target, version, track, build)

    if include_mac_app:
        verify_mac_app_archive(directory / mac_app_name, version, track, build)

    checksum_file.write_text("".join(f"{checksum(directory / name)}  {name}\n" for name in sorted(expected)),
                             encoding="utf-8")
    print(f"Verified {len(expected)} finished release archives for {version} ({track}/{build})")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--track", choices=("alpha", "nightly", "none"), required=True)
    parser.add_argument("--build", required=True)
    parser.add_argument("--include-mac-app", action="store_true")
    parser.add_argument("--checksum-file", type=Path, required=True)
    arguments = parser.parse_args()

    try:
        require(arguments.directory.is_dir(), f"Release directory does not exist: {arguments.directory}")
        require(arguments.build.isdigit(), f"Update build is not a non-negative integer: {arguments.build}")
        numeric_build = int(arguments.build)
        require((arguments.track == "none" and numeric_build == 0) or
                (arguments.track != "none" and numeric_build > 0),
                f"Update track and build do not agree: {arguments.track}/{arguments.build}")
        verify(arguments.directory, arguments.version, arguments.track, arguments.build,
               arguments.include_mac_app, arguments.checksum_file)
    except (OSError, UnicodeError, zipfile.BadZipFile, VerificationError) as error:
        print(f"Release archive verification failed: {error}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
