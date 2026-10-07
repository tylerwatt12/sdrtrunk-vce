"""Isolated, hardware-free contracts for release smoke target and SQLite checks."""
from contextlib import closing
import importlib.util
import inspect
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest import mock


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

    def test_windows_shutdown_waits_for_transient_handle_release_then_runs_all_checks(self):
        database = self.database(45)
        strict_check = SMOKE.check_database
        for code in (5, 6, 10):
            with self.subTest(code=code):
                transient = sqlite3.OperationalError("shutdown handle not released")
                transient.sqlite_errorcode = code
                calls = []
                def probe(path, expected, *, timeout):
                    calls.append((path, expected))
                    self.assertGreaterEqual(timeout, 0)
                    self.assertLessEqual(timeout, 5)
                    if len(calls) == 1:
                        raise transient
                    strict_check(path, expected, timeout=timeout)
                with mock.patch.object(SMOKE.os, "name", "nt"), \
                     mock.patch.object(SMOKE, "check_database", side_effect=probe), \
                     mock.patch.object(SMOKE.time, "sleep") as pause:
                    SMOKE.check_database_after_stop(database, 45)
                self.assertEqual([(database, 45), (database, 45)], calls)
                pause.assert_called_once_with(0.05)

    def test_persistent_windows_shutdown_io_error_remains_a_bounded_failure(self):
        database = self.database(45)
        error = sqlite3.OperationalError("disk I/O error")
        error.sqlite_errorcode = 10
        error.sqlite_errorname = "SQLITE_IOERR"
        with mock.patch.object(SMOKE.os, "name", "nt"), \
             mock.patch.object(SMOKE, "check_database", side_effect=error) as probe, \
             mock.patch.object(SMOKE.time, "monotonic", side_effect=(0.0, 0.0, 0.0, 5.0, 5.0)), \
             mock.patch.object(SMOKE.time, "sleep") as pause:
            with self.assertRaises(sqlite3.OperationalError) as failure:
                SMOKE.check_database_after_stop(database, 45)
            self.assertIs(error, failure.exception)
            self.assertIn("sqlite_errorcode=10", str(failure.exception))
            self.assertIn("sqlite_errorname=SQLITE_IOERR", str(failure.exception))
        self.assertEqual(2, probe.call_count)
        pause.assert_called_once_with(0.05)

    def test_shutdown_does_not_retry_corruption_or_failed_integrity_foreign_key_and_target_gates(self):
        database = self.database(45)
        corrupt = sqlite3.DatabaseError("database disk image is malformed")
        for error in (corrupt, RuntimeError("Profile failed SQLite integrity check"),
                      RuntimeError("Profile failed SQLite foreign-key check"),
                      RuntimeError("Expected database format 45, found 44"),
                      sqlite3.OperationalError("unknown operational failure")):
            with self.subTest(error=str(error)), mock.patch.object(SMOKE.os, "name", "nt"), \
                 mock.patch.object(SMOKE, "check_database", side_effect=error) as probe, \
                 mock.patch.object(SMOKE.time, "sleep") as pause:
                with self.assertRaises(type(error)) as failure:
                    SMOKE.check_database_after_stop(database, 45)
                self.assertIs(error, failure.exception)
                self.assertEqual((database, 45), probe.call_args.args)
                pause.assert_not_called()

    def test_non_windows_shutdown_does_not_retry_io_errors(self):
        database = self.database(45)
        error = sqlite3.OperationalError("disk I/O error")
        error.sqlite_errorcode = 10
        with mock.patch.object(SMOKE.os, "name", "posix"), \
             mock.patch.object(SMOKE, "check_database", side_effect=error) as probe, \
             mock.patch.object(SMOKE.time, "sleep") as pause:
            with self.assertRaises(sqlite3.OperationalError):
                SMOKE.check_database_after_stop(database, 45)
        probe.assert_called_once_with(database, 45, timeout=5)
        pause.assert_not_called()

    def test_actual_invalid_target_and_corrupt_files_fail_windows_final_probe_immediately(self):
        wrong_target = self.database(44)
        corrupt = self.root / "invalid-after-stop.sqlite"
        corrupt.write_bytes(b"invalid sqlite file")
        for path, failure in ((wrong_target, RuntimeError), (corrupt, sqlite3.DatabaseError)):
            with self.subTest(path=path.name), mock.patch.object(SMOKE.os, "name", "nt"), \
                 mock.patch.object(SMOKE.time, "sleep") as pause:
                with self.assertRaises(failure):
                    SMOKE.check_database_after_stop(path, 45)
                pause.assert_not_called()


if __name__ == "__main__":
    unittest.main()
