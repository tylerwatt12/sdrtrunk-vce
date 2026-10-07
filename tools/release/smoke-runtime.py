#!/usr/bin/env python3
"""Exercise a release ZIP's bundled Java, SQLite, migrator, launcher and web assets.

Run on the archive's native OS/CPU, with JDK 25 available for the small profile helper.
All writes and the receiver process stay inside the supplied empty work directory.
"""
import argparse
import base64
from contextlib import closing
import gzip
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import signal
import socket
import sqlite3
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile


def run(command, *, cwd, env, log):
    with log.open("wb") as output:
        subprocess.run(command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT,
                       timeout=120, check=True)


def extract(archive, destination):
    with zipfile.ZipFile(archive) as package:
        for entry in package.infolist():
            target = (destination / entry.filename).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise RuntimeError("Archive contains an unsafe path")
            package.extract(entry, destination)
            if os.name != "nt" and not entry.is_dir():
                mode = (entry.external_attr >> 16) & 0o777
                if mode:
                    target.chmod(mode)
    roots = list(destination.iterdir())
    if len(roots) != 1 or not roots[0].is_dir():
        raise RuntimeError("Archive must contain one installation folder")
    return roots[0]


def read_target_format(metadata):
    try:
        value = metadata.read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError) as error:
        raise RuntimeError("Packaged database target metadata is missing or unreadable") from error
    if not re.fullmatch(r"[1-9][0-9]{0,9}", value) or int(value) > 2_147_483_647:
        raise RuntimeError("Packaged database target metadata is invalid")
    return int(value)


def check_database(database, expected_format, *, timeout=5):
    with closing(sqlite3.connect(database, timeout=timeout)) as connection:
        if connection.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
            raise RuntimeError("Profile failed SQLite integrity check")
        if connection.execute("PRAGMA foreign_key_check").fetchall():
            raise RuntimeError("Profile failed SQLite foreign-key check")
        version = connection.execute(
            "SELECT value FROM database_metadata WHERE key='database_format_version'").fetchone()
        if version != (str(expected_format),):
            raise RuntimeError(f"Expected database format {expected_format}, found {version!r}")


def check_database_after_stop(database, expected_format):
    # taskkill can return after the cmd launcher exits while its JVM's SQLite handles are still closing.
    # Every attempt keeps the full database checks; persistent I/O errors and invalid data still fail.
    deadline = time.monotonic() + 5 if os.name == "nt" else 0
    while True:
        try:
            remaining = max(0, deadline - time.monotonic()) if deadline else 5
            check_database(database, expected_format, timeout=remaining)
            return
        except sqlite3.OperationalError as error:
            code = getattr(error, "sqlite_errorcode", 0) & 0xff
            remaining = deadline - time.monotonic()
            # SQLite's stable primary result codes: BUSY=5, LOCKED=6, IOERR=10.
            if code not in (5, 6, 10) or remaining <= 0:
                error.args = (f"{error} (sqlite_errorcode={getattr(error, 'sqlite_errorcode', None)}, "
                              f"sqlite_errorname={getattr(error, 'sqlite_errorname', None)})",)
                raise
            time.sleep(min(0.05, remaining))


def retained_counts(database):
    with closing(sqlite3.connect(database)) as connection:
        return {table: connection.execute(f'SELECT count(*) FROM "{table}"').fetchone()[0]
                for table in ("configuration_channel", "configuration_broadcast_stream", "alias", "web_user")}


def check_manifest(installation, expected_build):
    jars = list((installation / "lib").glob("sdrtrunk-vce-*.jar"))
    if len(jars) != 1:
        raise RuntimeError("Cannot identify the packaged application JAR")
    with zipfile.ZipFile(jars[0]) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n ", "")
    if f"Update-Track: nightly\r\n" not in manifest:
        raise RuntimeError("Candidate does not carry Nightly updater identity")
    if f"Update-Build: {expected_build}\r\n" not in manifest:
        raise RuntimeError("Candidate updater build does not match its preparation run")


def check_candidate(archive, expected_build, expected_commit):
    candidate = json.loads(archive.with_name("candidate.json").read_text(encoding="utf-8"))
    if (candidate.get("purpose") != "prepare" or candidate.get("run_id") != expected_build or
            candidate.get("source_commit") != expected_commit):
        raise RuntimeError("Candidate source or build does not match the requested preparation")
    checksums = {}
    for line in archive.with_name("SHA256SUMS").read_text(encoding="utf-8").splitlines():
        digest, filename = line.split(maxsplit=1)
        checksums[filename.lstrip(" *")] = digest
    for path in [archive, archive.with_name("candidate.json"), archive.with_name("build_info.txt"),
                 archive.with_name("update.properties")]:
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        if digest != checksums.get(path.name):
            raise RuntimeError(f"Candidate checksum failed: {path.name}")


def stop(process):
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False, timeout=30)
    else:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
    process.wait(timeout=30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--expected-build", required=True)
    parser.add_argument("--expected-commit", required=True)
    args = parser.parse_args()
    archive = args.archive.resolve(strict=True)
    work = args.work_dir.resolve()
    if work.exists() and any(work.iterdir()):
        raise RuntimeError("Smoke-test work directory must be empty")
    work.mkdir(parents=True, exist_ok=True)
    evidence = work / "evidence"
    evidence.mkdir()
    check_candidate(archive, args.expected_build, args.expected_commit)
    installation = extract(archive, work / "extracted package")
    check_manifest(installation, args.expected_build)
    java = installation / "bin" / ("java.exe" if os.name == "nt" else "java")
    profile = work / "profile with spaces"
    home = work / "isolated home"
    home.mkdir()
    temporary = work / "isolated temp"
    temporary.mkdir()
    password = work / "temporary-password.txt"
    password.write_text(secrets.token_urlsafe(32), encoding="utf-8")
    password.chmod(0o600)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    classes = work / "helper classes"
    classes.mkdir()
    classpath = str(installation / "lib" / "*")
    env = os.environ.copy()
    env.pop("JAVA_TOOL_OPTIONS", None)
    env.pop("JDK_JAVA_OPTIONS", None)
    env.pop("JAVA_OPTS", None)
    javac = shutil.which("javac")
    if javac is None:
        raise RuntimeError("JDK 25 javac is required for the profile helper")
    run([javac, "--release", "25", "-cp", classpath, "-d", str(classes),
         str(Path(__file__).with_name("SmokeProfile.java"))],
        cwd=work, env=env, log=evidence / "helper-compile.log")
    options = ["-Djava.awt.headless=true", f"-Duser.home={home}", f"-Djava.io.tmpdir={temporary}",
               f"-Dsdrtrunk.vce.data.root={profile}"]
    target_metadata = evidence / "target-format.txt"
    try:
        run([str(java), *options, "-cp", str(classes) + os.pathsep + classpath,
             "SmokeProfile", str(password), str(port), str(target_metadata)],
            cwd=installation, env=env, log=evidence / "profile.log")
    finally:
        password.unlink(missing_ok=True)
    database = profile / "database" / "sdrtrunk.sqlite"
    target_format = read_target_format(target_metadata)
    check_database(database, target_format)
    staged = database.with_name(".sdrtrunk.sqlite.migration-" + str(uuid.uuid4()))
    with closing(sqlite3.connect(database)) as source, closing(sqlite3.connect(staged)) as destination:
        source.backup(destination)
    run([str(java), *options, "-cp", classpath,
         "io.github.dsheirer.database.upgrade.ApplicationDatabaseMigrator", str(staged)],
        cwd=installation, env=env, log=evidence / "migrator.log")
    check_database(staged, target_format)
    # This schema comes from the actual previously published format-20 runtime, with synthetic data only.
    legacy = work / "published-format20.sqlite"
    resource = (Path(__file__).resolve().parents[2] / "src/test/resources/io/github/dsheirer/database/upgrade"
                / "format20-populated.sqlite.gz.b64")
    legacy.write_bytes(gzip.decompress(base64.b64decode(resource.read_bytes())))
    check_database(legacy, 20)
    legacy_bytes = legacy.read_bytes()
    expected_counts = retained_counts(legacy)
    migrated = database.with_name(".sdrtrunk.sqlite.migration-" + str(uuid.uuid4()))
    shutil.copyfile(legacy, migrated)
    run([str(java), *options, "-cp", classpath,
         "io.github.dsheirer.database.upgrade.ApplicationDatabaseMigrator", str(migrated)],
        cwd=installation, env=env, log=evidence / f"format20-to{target_format}.log")
    check_database(migrated, target_format)
    if retained_counts(migrated) != expected_counts or legacy.read_bytes() != legacy_bytes:
        raise RuntimeError("Packaged format-20 upgrade changed retained counts or the selected source")
    launcher = installation / ({"nt": "Start VCE.bat"}.get(os.name) or
                               ("Start VCE.command" if os.sys.platform == "darwin" else "Start VCE.sh"))
    env["JAVA_OPTS"] = " ".join(f'"{option}"' for option in options)
    command = [str(launcher)]
    if os.name == "nt":
        command = ["cmd.exe", "/d", "/c", str(launcher)]
    with (evidence / "receiver.log").open("wb") as log:
        process = subprocess.Popen(command, cwd=installation, env=env, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=os.name != "nt")
        try:
            deadline = time.monotonic() + 120
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"Packaged launcher exited early ({process.returncode}); see receiver.log")
                try:
                    with opener.open(f"http://127.0.0.1:{port}/", timeout=2) as response:
                        body = response.read().decode("utf-8")
                        started = "starting main application headless" in (
                            evidence / "receiver.log").read_text(encoding="utf-8", errors="replace")
                        if response.status == 200 and "sdrtrunk-web-revision" in body and started:
                            break
                except (urllib.error.URLError, TimeoutError):
                    pass
                time.sleep(0.5)
            else:
                raise RuntimeError("Packaged receiver did not serve its web interface; see receiver.log")
            with opener.open(f"http://127.0.0.1:{port}/assets/app.js", timeout=10) as response:
                if response.status != 200 or len(response.read()) < 1000:
                    raise RuntimeError("Packaged web JavaScript was not served")
        finally:
            stop(process)
    check_database_after_stop(database, target_format)
    with archive.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    print(f"PASS: {archive.name}: bundled Java, fresh format {target_format} profile, format 20-to-{target_format} migration, native launcher and web assets")
    print(f"SHA256: {digest}")


if __name__ == "__main__":
    main()
