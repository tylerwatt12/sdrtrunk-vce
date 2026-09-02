#!/usr/bin/env python3
"""Focused regression tests for the finished release archive verifier."""

from __future__ import annotations

import io
import plistlib
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY_ROOT))

from scripts import verify_release_archives as verifier  # noqa: E402


ALPHA_VERSION = "0.6.2-alpha-13"
ALPHA_TRACK = "alpha"
ALPHA_BUILD = "8"


def application_jar(version: str, track: str, build: str, windows: bool) -> bytes:
    output = io.BytesIO()

    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        manifest = (
            "Manifest-Version: 1.0\r\n"
            f"Implementation-Version: {version}\r\n"
            f"Update-Track: {track}\r\n"
            f"Update-Build: {build}\r\n"
            "\r\n"
        )
        archive.writestr("META-INF/MANIFEST.MF", manifest)

        for class_name in verifier.REQUIRED_APPLICATION_CLASSES:
            archive.writestr(class_name, b"class")

        if not windows:
            archive.writestr(verifier.WINDOWS_COMPATIBILITY_CLASS, b"class")

    return output.getvalue()


def mach_o_aarch64() -> bytes:
    return b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C)


def pe_x86_64() -> bytes:
    executable = bytearray(256)
    executable[0:2] = b"MZ"
    struct.pack_into("<I", executable, 0x3C, 0x80)
    executable[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<H", executable, 0x84, 0x8664)
    return bytes(executable)


class VerifyReleaseArchivesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary_directory.name)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_mac_app(self, *, jar_version: str = ALPHA_VERSION,
                      extra_entries: dict[str, bytes] | None = None) -> Path:
        archive_path = self.directory / f"sdrtrunk-vce-macos-app-aarch64-v{ALPHA_VERSION}.zip"
        root = "sdrtrunk-vce.app/Contents"
        bundle_version = verifier.mac_app_version(ALPHA_VERSION)
        information = plistlib.dumps({
            "CFBundleExecutable": "sdrtrunk-vce",
            "CFBundleShortVersionString": bundle_version,
            "CFBundleVersion": bundle_version,
        })
        configuration = (
            "[Application]\n"
            "app.mainmodule=sdr.trunk/io.github.dsheirer.gui.SDRTrunk\n"
            "[JavaOptions]\n"
            "java-options=-Dsdrtrunk.stats.web.root=$APPDIR/stats-web\n"
            "java-options=--module-path\n"
            "java-options=$APPDIR/mods\n"
        )

        with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(f"{root}/Info.plist", information)
            archive.writestr(f"{root}/MacOS/sdrtrunk-vce", mach_o_aarch64())
            archive.writestr(f"{root}/runtime/Contents/Home/bin/java", mach_o_aarch64())
            archive.writestr(f"{root}/app/mods/sdrtrunk-vce-jpms.jar",
                             application_jar(jar_version, ALPHA_TRACK, ALPHA_BUILD, windows=False))
            archive.writestr(f"{root}/app/sdrtrunk-vce.cfg", configuration)
            archive.writestr(f"{root}/app/stats-web/index.html", "<!doctype html>")
            archive.writestr(f"{root}/app/LICENSE", "license")
            archive.writestr(f"{root}/app/NOTICE", "notice")

            for name, contents in (extra_entries or {}).items():
                archive.writestr(name, contents)

        return archive_path

    def write_windows_console(self, launcher_arguments: tuple[str, ...]) -> Path:
        archive_path = self.directory / "sdrtrunk-vce-windows-x86_64-vnightly.zip"
        root = archive_path.stem
        launcher = "@echo off\r\n" + "\r\n".join(launcher_arguments) + "\r\n"

        with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(f"{root}/bin/sdrtrunk-vce.bat", launcher)
            archive.writestr(f"{root}/bin/java.exe", pe_x86_64())
            archive.writestr(f"{root}/lib/sdrtrunk-vce-nightly.jar",
                             application_jar("nightly", "nightly", "123", windows=True))
            archive.writestr(f"{root}/stats-web/index.html", "<!doctype html>")
            archive.writestr(f"{root}/LICENSE", "license")
            archive.writestr(f"{root}/NOTICE", "notice")

        return archive_path

    def test_accepts_valid_mac_app_metadata_and_application_jar(self) -> None:
        archive = self.write_mac_app()

        verifier.verify_mac_app_archive(archive, ALPHA_VERSION, ALPHA_TRACK, ALPHA_BUILD)

    def test_rejects_stale_mac_application_jar_identity(self) -> None:
        archive = self.write_mac_app(jar_version="0.6.2-alpha-12")

        with self.assertRaisesRegex(verifier.VerificationError, "Implementation-Version"):
            verifier.verify_mac_app_archive(archive, ALPHA_VERSION, ALPHA_TRACK, ALPHA_BUILD)

    def test_rejects_unsafe_zip_path(self) -> None:
        archive = self.write_mac_app(extra_entries={"../outside.txt": b"escape"})

        with self.assertRaisesRegex(verifier.VerificationError, "unsafe path"):
            verifier.verify_mac_app_archive(archive, ALPHA_VERSION, ALPHA_TRACK, ALPHA_BUILD)

    def test_rejects_each_missing_windows_launcher_argument(self) -> None:
        for missing_argument in verifier.WINDOWS_ARGUMENTS:
            with self.subTest(missing_argument=missing_argument):
                included = tuple(argument for argument in verifier.WINDOWS_ARGUMENTS
                                 if argument != missing_argument)
                archive = self.write_windows_console(included)

                with self.assertRaisesRegex(verifier.VerificationError, "launcher is missing"):
                    verifier.verify_console_archive(archive, "windows-x86_64", "nightly", "nightly", "123")


if __name__ == "__main__":
    unittest.main()
