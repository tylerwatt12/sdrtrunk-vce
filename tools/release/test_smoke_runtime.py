"""Isolated, hardware-free contracts for release smoke web, USB, target and SQLite checks."""
from contextlib import closing
import importlib.util
import inspect
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from unittest import mock
import urllib.error


SPEC = importlib.util.spec_from_file_location("smoke_runtime", Path(__file__).with_name("smoke-runtime.py"))
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class SmokeWebRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.web_root = Path(self.work.name) / "stats-web"
        (self.web_root / "assets").mkdir(parents=True)
        self.index = ('<!doctype html><html><head><meta name="sdrtrunk-web-revision" content="538">'
                      '<link rel="canonical" href="__VCE_PUBLIC_ORIGIN__/">'
                      '</head><body>VCE</body></html>').encode("utf-8")
        self.javascript = b"// packaged application\n" + b"const packaged = true;\n" * 60
        (self.web_root / "index.html").write_bytes(self.index)
        (self.web_root / "assets" / "app.js").write_bytes(self.javascript)
        self.process = mock.Mock()
        self.process.poll.return_value = None
        self.port = 12345

    def response(self, body, status=200):
        response = mock.MagicMock()
        response.status = status
        response.read.return_value = body
        response.__enter__.return_value = response
        return response

    def test_logger_free_startup_requires_the_packaged_revision_and_served_javascript(self):
        # The server substitutes the public origin in HTML but serves JavaScript bytes unchanged.
        served_index = self.index.replace(b"__VCE_PUBLIC_ORIGIN__", b"http://127.0.0.1:12345")
        opener = mock.Mock()
        opener.open.side_effect = [self.response(served_index), self.response(self.javascript)]
        with mock.patch.object(SMOKE.urllib.request, "build_opener", return_value=opener), \
             mock.patch.object(SMOKE.time, "sleep") as pause:
            SMOKE.check_web_runtime(self.process, self.port, self.web_root)
        self.assertEqual([mock.call("http://127.0.0.1:12345/", timeout=2),
                          mock.call("http://127.0.0.1:12345/assets/app.js", timeout=10)],
                         opener.open.call_args_list)
        self.assertEqual(2, self.process.poll.call_count)
        pause.assert_not_called()

    def test_wrong_html_revision_or_status_reaches_the_existing_startup_deadline(self):
        for body, status in ((b"<html>unrelated server</html>", 200),
                             (self.index.replace(b'content="538"', b'content="537"'), 200),
                             (self.index, 503)):
            with self.subTest(body=body, status=status):
                opener = mock.Mock()
                opener.open.return_value = self.response(body, status)
                with mock.patch.object(SMOKE.urllib.request, "build_opener", return_value=opener), \
                     mock.patch.object(SMOKE.time, "monotonic", side_effect=(0.0, 0.0, 120.0)), \
                     mock.patch.object(SMOKE.time, "sleep") as pause:
                    with self.assertRaisesRegex(RuntimeError, "did not serve its web interface"):
                        SMOKE.check_web_runtime(self.process, self.port, self.web_root)
                opener.open.assert_called_once_with("http://127.0.0.1:12345/", timeout=2)
                pause.assert_called_once_with(0.5)

    def test_missing_revision_in_packaged_index_fails_closed(self):
        (self.web_root / "index.html").write_text("<html>missing assets</html>", encoding="utf-8")
        with mock.patch.object(SMOKE.urllib.request, "build_opener") as build_opener:
            with self.assertRaisesRegex(RuntimeError, "index does not declare its revision"):
                SMOKE.check_web_runtime(self.process, self.port, self.web_root)
        build_opener.assert_not_called()

    def test_launcher_exit_before_or_during_readiness_fails(self):
        for polls, requests in (((7,), 0), ((None, 7), 2)):
            with self.subTest(polls=polls):
                self.process.poll.side_effect = polls
                self.process.returncode = 7
                opener = mock.Mock()
                opener.open.side_effect = [self.response(self.index), self.response(self.javascript)]
                with mock.patch.object(SMOKE.urllib.request, "build_opener", return_value=opener):
                    with self.assertRaisesRegex(RuntimeError, r"launcher exited early \(7\)"):
                        SMOKE.check_web_runtime(self.process, self.port, self.web_root)
                self.assertEqual(requests, opener.open.call_count)

    def test_missing_short_or_other_javascript_is_not_readiness(self):
        for body, status in ((self.javascript, 404), (b"", 200), (b"x" * 999, 200),
                             (b"x" * len(self.javascript), 200)):
            with self.subTest(status=status, size=len(body)):
                opener = mock.Mock()
                opener.open.side_effect = [self.response(self.index), self.response(body, status)]
                with mock.patch.object(SMOKE.urllib.request, "build_opener", return_value=opener):
                    with self.assertRaisesRegex(RuntimeError, "JavaScript was not served"):
                        SMOKE.check_web_runtime(self.process, self.port, self.web_root)

    def test_javascript_connection_failure_is_not_readiness(self):
        for error in (urllib.error.URLError("unserved asset"), TimeoutError("asset timed out")):
            with self.subTest(error=error):
                opener = mock.Mock()
                opener.open.side_effect = [self.response(self.index), error]
                with mock.patch.object(SMOKE.urllib.request, "build_opener", return_value=opener):
                    with self.assertRaises(type(error)):
                        SMOKE.check_web_runtime(self.process, self.port, self.web_root)

    def test_starting_server_can_recover_from_a_connection_failure(self):
        opener = mock.Mock()
        opener.open.side_effect = [urllib.error.URLError("not started"),
                                   self.response(self.index), self.response(self.javascript)]
        with mock.patch.object(SMOKE.urllib.request, "build_opener", return_value=opener), \
             mock.patch.object(SMOKE.time, "sleep") as pause:
            SMOKE.check_web_runtime(self.process, self.port, self.web_root)
        self.assertEqual(3, self.process.poll.call_count)
        pause.assert_called_once_with(0.5)


class SmokeRuntimeTest(unittest.TestCase):
    PAIR_ID = "93ee9be5-065e-434d-9292-d70a08db23a9"

    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)

    def database(self, version, history_version=None):
        suffix = f"-history-{history_version}" if history_version is not None else ""
        path = self.root / f"format-{version}{suffix}.sqlite"
        with closing(sqlite3.connect(path)) as connection, connection:
            connection.execute("CREATE TABLE database_metadata(key TEXT PRIMARY KEY, value TEXT)")
            connection.execute("INSERT INTO database_metadata VALUES('database_format_version', ?)", (str(version),))
            if version >= 63:
                connection.executemany("INSERT INTO database_metadata VALUES(?,?)", [
                    ("database_role", "configuration"), ("configuration_history_pair_id", self.PAIR_ID)])
        if version >= 63:
            with closing(sqlite3.connect(SMOKE.activity_database(path))) as connection, connection:
                connection.execute("CREATE TABLE database_metadata(key TEXT PRIMARY KEY, value TEXT)")
                connection.executemany("INSERT INTO database_metadata VALUES(?,?)", [
                    ("database_format_version", str(version if history_version is None else history_version)),
                    ("database_role", "activity"),
                    ("configuration_history_pair_id", self.PAIR_ID)])
        return path

    def test_pair_uses_canonical_or_private_stage_companion_names(self):
        for name, companion in (("sdrtrunk.sqlite", "activity.sqlite"),
                                (".sdrtrunk.sqlite.migration-stage", ".sdrtrunk.sqlite.migration-stage.activity.sqlite")):
            self.assertEqual(self.root / companion, SMOKE.activity_database(self.root / name))
        SMOKE.check_database(self.database(63), 63)

    def test_configuration_only_format_64_change_accepts_only_its_supported_activity_versions(self):
        for configuration, history in ((63, 63), (64, 63), (64, 64)):
            with self.subTest(configuration=configuration, history=history):
                SMOKE.check_database(self.database(configuration, history), configuration)

        for configuration, history in ((63, 64), (64, 62), (64, 65), (64, "063"), (64, "64.0")):
            with self.subTest(configuration=configuration, history=history), \
                 self.assertRaisesRegex(RuntimeError, "Expected database format"):
                SMOKE.check_database(self.database(configuration, history), configuration)

        with self.assertRaisesRegex(RuntimeError, "Expected database format 64"):
            SMOKE.check_database(self.database(63), 64)

    def test_format_64_with_retained_format_63_activity_requires_matching_role_and_pair(self):
        database = self.database(64, 63)
        history = SMOKE.activity_database(database)
        for role, pair in (("configuration", self.PAIR_ID),
                           ("activity", "06f6c9c9-7d6d-42c3-ad3c-19952839a55d")):
            with closing(sqlite3.connect(history)) as connection, connection:
                connection.execute("UPDATE database_metadata SET value=? WHERE key='database_role'", (role,))
                connection.execute("UPDATE database_metadata SET value=? WHERE key='configuration_history_pair_id'", (pair,))
            with self.subTest(role=role, pair=pair), \
                 self.assertRaisesRegex(RuntimeError, "identities do not match"):
                SMOKE.check_database(database, 64)

    def test_owned_connections_are_query_only_and_cannot_create_a_missing_file(self):
        database = self.database(63)
        with closing(SMOKE.open_owned_database(database)) as connection:
            self.assertEqual((1,), connection.execute("PRAGMA query_only").fetchone())
            with self.assertRaisesRegex(sqlite3.OperationalError, "readonly"):
                connection.execute("DELETE FROM database_metadata")
        missing = self.root / "missing.sqlite"
        with self.assertRaisesRegex(RuntimeError, "missing or is not an ordinary file"):
            SMOKE.open_owned_database(missing)
        self.assertFalse(missing.exists())

    def test_missing_or_linked_companion_is_refused_without_creating_a_replacement(self):
        database = self.database(63)
        history = SMOKE.activity_database(database)
        history.unlink()
        with self.assertRaisesRegex(RuntimeError, "missing or is not an ordinary file"):
            SMOKE.check_database(database, 63)
        self.assertFalse(history.exists())
        try:
            history.symlink_to(self.root / "missing-target.sqlite")
        except OSError:
            return  # Windows runners may lack symbolic-link privileges; missing-file refusal is still exercised.
        with self.assertRaisesRegex(RuntimeError, "missing or is not an ordinary file"):
            SMOKE.check_database(database, 63)
        self.assertTrue(history.is_symlink())
        self.assertFalse(history.resolve().exists())

    def test_swapped_roles_invalid_configuration_identity_and_mismatched_pair_are_refused(self):
        database = self.database(63)
        history = SMOKE.activity_database(database)
        for config_role, config_pair, history_role, history_pair in (
                ("activity", self.PAIR_ID, "configuration", self.PAIR_ID),
                ("configuration", "invalid", "activity", "invalid"),
                ("configuration", self.PAIR_ID, "configuration", self.PAIR_ID),
                ("configuration", self.PAIR_ID, "activity", "06f6c9c9-7d6d-42c3-ad3c-19952839a55d")):
            for path, role, pair in ((database, config_role, config_pair), (history, history_role, history_pair)):
                with closing(sqlite3.connect(path)) as connection, connection:
                    connection.execute("UPDATE database_metadata SET value=? WHERE key='database_role'", (role,))
                    connection.execute("UPDATE database_metadata SET value=? WHERE key='configuration_history_pair_id'", (pair,))
            with self.subTest(config_role=config_role, history_role=history_role, pair=history_pair), \
                 self.assertRaisesRegex(RuntimeError, "identity is invalid|identities do not match"):
                SMOKE.check_database(database, 63)

    def test_missing_configuration_role_and_wrong_companion_format_are_refused(self):
        database = self.database(63)
        with closing(sqlite3.connect(database)) as connection, connection:
            connection.execute("DELETE FROM database_metadata WHERE key='database_role'")
        with self.assertRaisesRegex(RuntimeError, "configuration identity is invalid"):
            SMOKE.check_database(database, 63)
        with closing(sqlite3.connect(database)) as connection, connection:
            connection.execute("INSERT INTO database_metadata VALUES('database_role','configuration')")
        with closing(sqlite3.connect(SMOKE.activity_database(database))) as connection, connection:
            connection.execute("UPDATE database_metadata SET value='62' WHERE key='database_format_version'")
        with self.assertRaisesRegex(RuntimeError, "Expected database format 63"):
            SMOKE.check_database(database, 63)

    def test_corrupt_activity_and_invalid_activity_foreign_keys_fail_the_pair(self):
        database = self.database(63)
        history = SMOKE.activity_database(database)
        with closing(sqlite3.connect(history)) as connection, connection:
            connection.execute("CREATE TABLE parent(id INTEGER PRIMARY KEY)")
            connection.execute("CREATE TABLE child(parent_id INTEGER REFERENCES parent(id))")
            connection.execute("INSERT INTO child VALUES(1)")
        with self.assertRaisesRegex(RuntimeError, "foreign-key check"):
            SMOKE.check_database(database, 63)
        history.write_bytes(b"invalid SQLite history")
        with self.assertRaises(sqlite3.DatabaseError):
            SMOKE.check_database(database, 63)

    def test_retained_history_counts_read_the_activity_file(self):
        database = self.database(63)
        with closing(sqlite3.connect(SMOKE.activity_database(database))) as connection, connection:
            for index, table in enumerate(("receiver_channel", "receiver_activity_event", "radio_system", "alias_activity_summary"), 1):
                connection.execute(f'CREATE TABLE "{table}"(id INTEGER)')
                connection.executemany(f'INSERT INTO "{table}" VALUES(?)', [(value,) for value in range(index)])
        self.assertEqual({"receiver_channel": 1, "receiver_activity_event": 2, "radio_system": 3,
                          "alias_activity_summary": 4}, SMOKE.retained_history_counts(database))

    def test_snapshot_and_exact_validation_delegate_to_the_bundled_helper(self):
        database = self.root / "sdrtrunk.sqlite"
        staged = self.root / ".sdrtrunk.sqlite.migration-stage"
        options = ["-Djava.awt.headless=true"]
        with mock.patch.object(SMOKE, "run") as run:
            SMOKE.snapshot_database("java", options, "helper:bundled", database, staged,
                                    cwd=self.root, env={}, log=self.root / "snapshot.log")
            SMOKE.validate_pair("java", options, "helper:bundled", staged,
                                cwd=self.root, env={}, log=self.root / "pair.log")
        self.assertEqual(["java", *options, "-cp", "helper:bundled", "SmokeProfile", "--snapshot",
                          str(database), str(staged)], run.call_args_list[0].args[0])
        self.assertEqual(["java", *options, "-cp", "helper:bundled", "SmokeProfile", "--validate", str(staged)],
                         run.call_args_list[1].args[0])

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

    def test_mac_arm_probes_actual_and_modern_payload_in_separate_jvms_without_mutating_launcher_options(self):
        options = ["-Djava.awt.headless=true", "-Duser.home=isolated home"]
        java = Path("package/bin/java")
        for architecture in ("arm64", "aarch64"):
            with self.subTest(architecture=architecture), mock.patch.object(SMOKE.os.sys, "platform", "darwin"), \
                 mock.patch.object(SMOKE.platform, "machine", return_value=architecture), \
                 mock.patch.object(SMOKE, "run") as run:
                SMOKE.check_usb_runtimes(java, options, "helper:package/lib/*",
                                         cwd=self.root, env={}, evidence=self.root)
            self.assertEqual(2, run.call_count)
            normal, modern = run.call_args_list
            self.assertEqual([str(java), *options, "-cp", "helper:package/lib/*", "SmokeUsb"],
                             normal.args[0])
            self.assertEqual([str(java), *options, "-Dos.version=26", "-cp", "helper:package/lib/*", "SmokeUsb"],
                             modern.args[0])
            self.assertEqual(self.root / "usb.log", normal.kwargs["log"])
            self.assertEqual(self.root / "usb-macos26.log", modern.kwargs["log"])
            self.assertEqual(["-Djava.awt.headless=true", "-Duser.home=isolated home"], options)

    def test_other_platforms_only_probe_their_actual_native_runtime(self):
        for system, architecture in (("darwin", "x86_64"), ("linux", "aarch64"), ("win32", "ARM64")):
            with self.subTest(system=system, architecture=architecture), \
                 mock.patch.object(SMOKE.os.sys, "platform", system), \
                 mock.patch.object(SMOKE.platform, "machine", return_value=architecture), \
                 mock.patch.object(SMOKE, "run") as run:
                SMOKE.check_usb_runtimes(Path("package/bin/java"), [], "helper:package/lib/*",
                                         cwd=self.root, env={}, evidence=self.root)
            run.assert_called_once()
            self.assertNotIn("-Dos.version=26", run.call_args.args[0])

    def test_failed_modern_payload_fails_smoke_and_identifies_its_evidence(self):
        with mock.patch.object(SMOKE.os.sys, "platform", "darwin"), \
             mock.patch.object(SMOKE.platform, "machine", return_value="arm64"), \
             mock.patch.object(SMOKE, "run", side_effect=[None, subprocess.CalledProcessError(1, "java")]) as run:
            with self.assertRaisesRegex(RuntimeError, "see usb-macos26.log"):
                SMOKE.check_usb_runtimes(Path("package/bin/java"), [], "helper:package/lib/*",
                                         cwd=self.root, env={}, evidence=self.root)
        self.assertEqual(2, run.call_count)


@unittest.skipUnless(shutil.which("java") and shutil.which("javac"), "JDK 25 is required for USB probe tests")
class SmokeUsbTest(unittest.TestCase):
    """Compile the real probe against a fake JNI API; these tests never load USB natives."""

    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.classes = cls.root / "classes"
        api = cls.root / "org" / "usb4java"
        api.mkdir(parents=True)
        for name in ("Context", "DeviceList"):
            (api / f"{name}.java").write_text(
                f"package org.usb4java; public class {name} {{}}", encoding="utf-8")
        (api / "LibUsb.java").write_text("""
package org.usb4java;
public class LibUsb {
    public static final int SUCCESS = 0;
    static {
        if(Boolean.getBoolean("probe.failLinkage")) {
            throw new UnsatisfiedLinkError("missing native dependency");
        }
    }
    public static int init(Context context) {
        System.out.println("init");
        return Integer.getInteger("probe.initResult", 0);
    }
    public static int getDeviceList(Context context, DeviceList devices) {
        System.out.println("enumerate");
        if(Boolean.getBoolean("probe.failEnumerationLinkage")) {
            throw new UnsatisfiedLinkError("missing enumeration symbol");
        }
        return Integer.getInteger("probe.deviceCount", 0);
    }
    public static void freeDeviceList(DeviceList devices, boolean unref) {
        System.out.println("free " + unref);
    }
    public static void exit(Context context) {
        System.out.println("exit");
    }
}
""", encoding="utf-8")
        cls.java = Path(shutil.which("java"))
        subprocess.run([shutil.which("javac"), "--release", "25", "-d", str(cls.classes),
                        *map(str, api.glob("*.java")), str(Path(__file__).with_name("SmokeUsb.java"))],
                       check=True, capture_output=True, text=True, timeout=30)

    def probe(self, **properties):
        log = self.root / "usb.log"
        environment = os.environ.copy()
        for option in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            environment.pop(option, None)
        SMOKE.check_usb_runtime(self.java, [f"-Dprobe.{key}={value}" for key, value in properties.items()],
                                str(self.classes), cwd=self.root, env=environment, log=log)
        return log.read_text(encoding="utf-8")

    def test_empty_and_nonempty_device_lists_succeed_and_release_references(self):
        for count in (0, 3):
            with self.subTest(count=count):
                output = self.probe(deviceCount=count)
                self.assertEqual(["init", "enumerate", "free true", "exit", "USB native runtime ready"],
                                 output.splitlines())

    def test_failed_initialization_does_not_enumerate_or_exit_an_uninitialized_context(self):
        with self.assertRaisesRegex(RuntimeError, "Packaged USB native runtime failed") as failure:
            self.probe(initResult=-99)
        self.assertIsInstance(failure.exception.__cause__, subprocess.CalledProcessError)
        output = (self.root / "usb.log").read_text(encoding="utf-8")
        self.assertIn("USB native initialization failed: -99", output)
        self.assertNotIn("enumerate", output)
        self.assertNotIn("exit\n", output)
        self.assertNotIn("USB native runtime ready", output)

    def test_enumeration_errors_exit_initialized_context_and_fail_the_package(self):
        for properties, message in (({"deviceCount": -1}, "USB device enumeration failed: -1"),
                                    ({"failEnumerationLinkage": "true"}, "missing enumeration symbol")):
            with self.subTest(properties=properties), self.assertRaisesRegex(RuntimeError, "see usb.log"):
                self.probe(**properties)
            output = (self.root / "usb.log").read_text(encoding="utf-8")
            self.assertIn("init\nenumerate\nexit\n", output)
            self.assertIn(message, output)
            self.assertNotIn("free true", output)
            self.assertNotIn("USB native runtime ready", output)

    def test_native_linkage_error_fails_the_package_before_profile_or_web_readiness(self):
        with self.assertRaisesRegex(RuntimeError, "Packaged USB native runtime failed"):
            self.probe(failLinkage="true")
        output = (self.root / "usb.log").read_text(encoding="utf-8")
        self.assertIn("UnsatisfiedLinkError: missing native dependency", output)
        self.assertNotIn("USB native runtime ready", output)

    def test_hung_native_initialization_is_a_bounded_package_failure(self):
        timeout = subprocess.TimeoutExpired("java", 120)
        with mock.patch.object(SMOKE, "run", side_effect=timeout), \
             self.assertRaisesRegex(RuntimeError, "Packaged USB native runtime failed") as failure:
            self.probe()
        self.assertIs(timeout, failure.exception.__cause__)


if __name__ == "__main__":
    unittest.main()
