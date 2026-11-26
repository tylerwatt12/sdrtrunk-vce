import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("schema_changes", Path(__file__).with_name("schema_changes.py"))
SCHEMA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCHEMA)


class SchemaChecksTest(unittest.TestCase):
    def test_logging_ui_and_release_infrastructure_leave_every_other_group_enabled(self):
        result = SCHEMA.plan(["src/main/java/io/github/dsheirer/source/tuner/manager/TunerManager.java",
                              "stats-web/assets/app.js", ".github/workflows/nightly.yml",
                              "tools/release/smoke-runtime.py"])
        self.assertFalse(result["required"])
        self.assertEqual(["core", "activity", "web", "http", "platform-filesystem"],
                         [row["test_group"] for row in result["matrix"]["include"]])

    def test_schema_versions_definitions_and_adjacent_steps_require_migrations(self):
        for path in (
                "src/main/java/io/github/dsheirer/database/upgrade/DatabaseFormatCatalog.java",
                "src/main/java/io/github/dsheirer/database/upgrade/DatabaseMigrationChain.java",
                "src/main/java/io/github/dsheirer/database/upgrade/Format78To79DatabaseMigration.java",
                "src/main/java/io/github/dsheirer/database/upgrade/Format4SchemaSql.java",
                "src/main/java/io/github/dsheirer/database/SdrTrunkDatabaseSchema.java",
                "src/main/java/io/github/dsheirer/record/managed/ManagedRecordingSchema.java",
                "src/main/java/io/github/dsheirer/alerts/TopicAlertSchema.java",
                "src/main/resources/database/schema.sql"):
            with self.subTest(path=path):
                result = SCHEMA.plan([path])
                self.assertTrue(result["required"])
                self.assertEqual("migrations", result["matrix"]["include"][-1]["test_group"])

    def test_unknown_baseline_cannot_skip_migrations(self):
        self.assertTrue(SCHEMA.plan([], False)["required"])

    def test_release_comparison_includes_schema_update_before_a_later_logging_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.check_output(["git", "-C", directory, *args], text=True).strip()
            git("init", "-q")
            git("config", "user.name", "Release fixture")
            git("config", "user.email", "release-fixture@example.invalid")
            schema = Path(directory) / "src/main/java/example/ManagedRecordingSchema.java"
            schema.parent.mkdir(parents=True)
            schema.write_text("format 12")
            git("add", "."); git("commit", "-qm", "Published baseline")
            base = git("rev-parse", "HEAD")
            schema.write_text("format 13")
            git("commit", "-qam", "Schema update")
            (Path(directory) / "logging.txt").write_text("Quiet polling")
            git("add", "."); git("commit", "-qm", "Logging update")
            paths = git("diff", "--name-only", "--no-renames", base, "HEAD").splitlines()
            self.assertTrue(SCHEMA.plan(paths)["required"])
            self.assertFalse(SCHEMA.plan(git("diff", "--name-only", "HEAD^", "HEAD").splitlines())["required"])


if __name__ == "__main__":
    unittest.main()
