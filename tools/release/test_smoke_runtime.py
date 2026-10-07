"""Isolated, hardware-free contracts for release smoke target and SQLite checks."""
from contextlib import closing
import importlib.util
import inspect
from pathlib import Path
import sqlite3
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("smoke_runtime", Path(__file__).with_name("smoke-runtime.py"))
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class SmokeRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)

    def database(self, version):
        path = self.root / f"format-{version}.sqlite"
        with closing(sqlite3.connect(path)) as connection, connection:
            connection.execute("CREATE TABLE database_metadata(key TEXT PRIMARY KEY, value TEXT)")
            connection.execute("INSERT INTO database_metadata VALUES('database_format_version', ?)", (str(version),))
        return path

    def test_target_metadata_supports_later_catalog_versions_without_python_edits(self):
        metadata = self.root / "target-format.txt"
        for version in (45, 46, 100):
            with self.subTest(version=version):
                metadata.write_text(str(version), encoding="utf-8")
                expected = SMOKE.read_target_format(metadata)
                self.assertEqual(version, expected)
                SMOKE.check_database(self.database(version), expected)

    def test_missing_unreadable_and_malformed_metadata_fail_closed(self):
        metadata = self.root / "target-format.txt"
        with self.assertRaisesRegex(RuntimeError, "missing or unreadable"):
            SMOKE.read_target_format(metadata)
        for value in ("", "0", "-1", "44.0", "45\n46", "true", "+45", "045", "４５", "2147483648"):
            with self.subTest(value=value):
                metadata.write_text(value, encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "invalid"):
                    SMOKE.read_target_format(metadata)
        metadata.write_bytes(b"\xff")
        with self.assertRaisesRegex(RuntimeError, "missing or unreadable"):
            SMOKE.read_target_format(metadata)

    def test_expected_target_is_required_and_is_not_inferred_from_database(self):
        self.assertIs(inspect.signature(SMOKE.check_database).parameters["expected_format"].default,
                      inspect.Parameter.empty)
        for version in (44, 46):
            with self.subTest(version=version):
                with self.assertRaisesRegex(RuntimeError, "Expected database format 45"):
                    SMOKE.check_database(self.database(version), 45)

    def test_legacy_source_still_requires_its_exact_source_version(self):
        source = self.database(20)
        SMOKE.check_database(source, 20)
        with self.assertRaisesRegex(RuntimeError, "Expected database format 45"):
            SMOKE.check_database(source, 45)

    def test_integrity_damage_is_refused_before_version_admission(self):
        damaged = self.root / "corrupt.sqlite"
        damaged.write_bytes(b"invalid sqlite file")
        with self.assertRaises(sqlite3.DatabaseError):
            SMOKE.check_database(damaged, 45)

    def test_foreign_key_damage_is_refused_before_version_admission(self):
        damaged = self.database(45)
        with closing(sqlite3.connect(damaged)) as connection, connection:
            connection.execute("CREATE TABLE parent(id INTEGER PRIMARY KEY)")
            connection.execute("CREATE TABLE child(parent_id INTEGER REFERENCES parent(id))")
            connection.execute("INSERT INTO child VALUES(1)")
        with self.assertRaisesRegex(RuntimeError, "foreign-key check"):
            SMOKE.check_database(damaged, 45)


if __name__ == "__main__":
    unittest.main()
