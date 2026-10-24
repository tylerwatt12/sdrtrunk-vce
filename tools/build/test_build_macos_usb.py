"""Hardware-free contracts for portable macOS USB packaging."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile


SPEC = importlib.util.spec_from_file_location("macos_usb", Path(__file__).with_name("build_macos_usb.py"))
USB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(USB)


class MacosUsbPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.original = self.root / "usb4java-native-libraries-1.3.3.jar"
        with zipfile.ZipFile(self.original, "w") as jar:
            jar.writestr(USB.NATIVE_ENTRY, b"nonportable native")
            jar.writestr("org/usb4java/darwin-legacy-aarch64/libusb4java.dylib", b"legacy unchanged")
            jar.writestr("org/usb4java/win32-aarch64/libusb4java.dll", b"Windows unchanged")
            jar.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        self.library = self.root / "libusb4java.dylib"
        self.library.write_bytes(b"self contained native")

    def test_repack_preserves_other_platform_payloads_and_records_actual_checksum(self):
        target = self.root / "repacked.jar"
        USB.repack(self.original, target, self.library)
        with zipfile.ZipFile(target) as jar:
            self.assertEqual(self.library.read_bytes(), jar.read(USB.NATIVE_ENTRY))
            marker = json.loads(jar.read(USB.JAR_MARKER))
            self.assertEqual("1.0.30", marker["libusb_version"])
            self.assertEqual(USB.digest(self.library), marker["native_entry"]["sha256"])
        corrupt = self.root / "corrupt.jar"
        with zipfile.ZipFile(target) as source, zipfile.ZipFile(corrupt, "w") as result:
            for entry in source.infolist():
                result.writestr(entry, b"changed" if entry.filename.endswith(".dll") else source.read(entry))
        with self.assertRaisesRegex(USB.BuildError, "Unrelated native JAR entry changed"):
            USB.validate_repacked_jar(self.original, corrupt, USB.digest(self.library))

    def test_source_extraction_refuses_paths_and_links_outside_build(self):
        for index, (name, kind) in enumerate((("../outside", tarfile.REGTYPE),
                                             ("/outside", tarfile.REGTYPE),
                                             ("source/link", tarfile.SYMTYPE))):
            archive = self.root / f"bad-{index}.tar"
            with tarfile.open(archive, "w") as tar:
                member = tarfile.TarInfo(name)
                member.type = kind
                member.linkname = "/outside" if kind == tarfile.SYMTYPE else ""
                tar.addfile(member, io.BytesIO())
            with self.assertRaisesRegex(USB.BuildError, "Unsafe source archive entry"):
                USB.extract_source(archive, self.root / f"extract-{index}")

    def test_non_mac_preparation_refuses_before_download_or_output_changes(self):
        output = self.root / "build/generated"
        output.mkdir(parents=True)
        sentinel = output / self.original.name
        sentinel.write_text("preserve")
        with mock.patch.object(USB, "ROOT", self.root), \
                mock.patch.object(USB, "MAVEN_JAR_SHA256", USB.digest(self.original)), \
                mock.patch.object(USB.platform, "system", return_value="Linux"), \
                mock.patch.object(USB, "source_archive", side_effect=AssertionError("Unexpected network")):
            with self.assertRaisesRegex(USB.BuildError, "requires an ARM Mac"):
                USB.prepare(output, self.original)
        self.assertEqual("preserve", sentinel.read_text())
        self.assertEqual([sentinel], list(output.iterdir()))

    def test_native_dependency_verification_refuses_external_package_manager_library(self):
        exports = "\n".join("000 T _Java_org_usb4java_LibUsb_" + name for name in
                            ("init", "getDeviceList", "claimInterface", "submitTransfer")) + "\n"
        for dependency, accepted in (("/usr/lib/libSystem.B.dylib", True),
                                     ("/opt/local/lib/libusb-1.0.0.dylib", False),
                                     ("@rpath/libusb-1.0.0.dylib", False)):
            outputs = ["arm64\n", "library:\n\t@rpath/libusb4java.dylib (compatibility version 0)\n"
                       f"\t{dependency} (compatibility version 0)\n", exports, ""]
            with self.subTest(dependency=dependency), mock.patch.object(USB, "run", side_effect=outputs):
                if accepted:
                    USB.validate_native(self.library)
                else:
                    with self.assertRaisesRegex(USB.BuildError, "non-system dynamic dependency"):
                        USB.validate_native(self.library)

    def test_actual_inventory_refuses_symlinks(self):
        prepared = self.root / "prepared"
        legal = prepared / USB.LEGAL
        legal.mkdir(parents=True)
        link = legal / "untracked-link"
        try:
            link.symlink_to(self.library)
        except OSError:
            self.skipTest("This environment does not permit symbolic links")
        with self.assertRaisesRegex(USB.BuildError, "symbolic link"):
            USB.validate_inventory(prepared, self.original, complete=False)

    def test_prebuilt_requires_complete_licenses_relink_objects_and_untampered_sources(self):
        prepared = self.root / "prepared"
        legal = prepared / USB.LEGAL
        legal.mkdir(parents=True)
        USB.repack(self.original, prepared / self.original.name, self.library)
        sources = {}
        for name, spec in USB.SOURCES.items():
            source = b"complete source " + name.encode()
            (legal / spec["archive"]).write_bytes(source)
            sources[name] = {**spec, "sha256": hashlib.sha256(source).hexdigest()}
        for name in ("libusb-COPYING", "libusb4java-LICENSE.md", "libusb4java-jni.a", "relink.sh", "README.txt"):
            (legal / name).write_bytes(b"required redistribution material")
        metadata = {**USB.native_marker(USB.digest(self.library)), "sources": sources,
                    "builder_sha256": USB.digest(Path(USB.__file__)),
                    "input_jar_sha256": USB.digest(self.original),
                    "output_jar_sha256": USB.digest(prepared / self.original.name),
                    "file_sha256": {path.relative_to(prepared).as_posix(): USB.digest(path)
                                    for path in prepared.rglob("*") if path.is_file()}}
        (prepared / USB.PROVENANCE).write_bytes(USB.json_bytes(metadata))
        with mock.patch.object(USB, "SOURCES", sources):
            USB.read_prepared(prepared, self.original)
            extra = legal / "private-note.txt"
            extra.write_text("preserve this undeclared file")
            with self.assertRaisesRegex(USB.BuildError, "unexpected entry"):
                USB.read_prepared(prepared, self.original)
            output = self.root / "build/output"
            with mock.patch.object(USB, "ROOT", self.root), \
                    mock.patch.object(USB, "MAVEN_JAR_SHA256", USB.digest(self.original)), \
                    mock.patch.object(USB, "source_archive", side_effect=AssertionError("Unexpected download")):
                with self.assertRaisesRegex(USB.BuildError, "unexpected entry"):
                    USB.prepare(output, self.original, prebuilt=prepared)
                self.assertFalse(output.exists())
                self.assertEqual("preserve this undeclared file", extra.read_text())
                extra.unlink()
                shutil.copytree(prepared, output)
                stale = {**metadata, "builder_sha256": "outdated builder"}
                (output / USB.PROVENANCE).write_bytes(USB.json_bytes(stale))
                output_extra = output / USB.LEGAL / "private-note.txt"
                output_extra.write_text("preserve this existing output file")
                before = {path.relative_to(output).as_posix(): path.read_bytes()
                          for path in output.rglob("*") if path.is_file()}
                for prebuilt in (None, prepared):
                    with self.assertRaisesRegex(USB.BuildError, "unexpected entry"):
                        USB.prepare(output, self.original, prebuilt=prebuilt)
                    self.assertEqual(before, {path.relative_to(output).as_posix(): path.read_bytes()
                                             for path in output.rglob("*") if path.is_file()})
            (legal / "libusb4java-jni.a").write_bytes(b"tampered")
            with self.assertRaisesRegex(USB.BuildError, "checksum mismatch"):
                USB.read_prepared(prepared, self.original)
            (legal / "libusb4java-jni.a").write_bytes(b"required redistribution material")
            metadata["file_sha256"].pop((USB.LEGAL / "libusb-COPYING").as_posix())
            (prepared / USB.PROVENANCE).write_bytes(USB.json_bytes(metadata))
            with self.assertRaisesRegex(USB.BuildError, "file inventory is incomplete"):
                USB.read_prepared(prepared, self.original)


if __name__ == "__main__":
    unittest.main()
