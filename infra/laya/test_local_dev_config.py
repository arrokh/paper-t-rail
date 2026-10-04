import re
import stat
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[2] / "scripts"
sys.path.insert(0, str(SCRIPTS))
from prepare_local_laya_env import configure_local_laya  # noqa: E402


class LocalDevConfigTest(unittest.TestCase):
    def test_creates_a_private_compose_environment_and_generates_an_api_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_path = root / ".env"
            example_path = root / ".env.example"
            example_path.write_text("POSTGRES_PASSWORD=example\nLAYA_API_KEY=\n", encoding="utf-8")

            enabled = configure_local_laya(env_path, example_path)

            self.assertTrue(enabled)
            contents = env_path.read_text(encoding="utf-8")
            key = re.search(r"^LAYA_API_KEY=([0-9a-f]{64})$", contents, re.MULTILINE)
            self.assertIsNotNone(key)
            self.assertIn("POSTGRES_PASSWORD=example", contents)
            self.assertIn("LAYA_ENABLED=true", contents)
            self.assertIn("SYSTEM_ONE_DEFAULT_PROVIDER=laya", contents)
            self.assertIn("SYSTEM_ONE_AGGREGATION_ENABLED=true", contents)
            self.assertEqual(stat.S_IMODE(env_path.stat().st_mode), 0o600)

    def test_replaces_a_quoted_blank_key_when_local_enablement_is_true(self):
        with tempfile.TemporaryDirectory() as directory:
            env_path = Path(directory) / ".env"
            env_path.write_text('LAYA_ENABLED=true\nLAYA_API_KEY="   "\n', encoding="utf-8")

            configure_local_laya(env_path, Path(directory) / "unused-example")

            contents = env_path.read_text(encoding="utf-8")
            self.assertIsNotNone(re.search(r"^LAYA_API_KEY=[0-9a-f]{64}$", contents, re.MULTILINE))

    def test_preserves_an_explicitly_disabled_local_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            env_path = Path(directory) / ".env"
            env_path.write_text(
                "LAYA_ENABLED=false\nSYSTEM_ONE_DEFAULT_PROVIDER=laya\nLAYA_API_KEY=\n",
                encoding="utf-8",
            )

            enabled = configure_local_laya(env_path, Path(directory) / "unused-example")

            self.assertFalse(enabled)
            self.assertIn("LAYA_ENABLED=false", env_path.read_text(encoding="utf-8"))
            self.assertIn("LAYA_API_KEY=", env_path.read_text(encoding="utf-8"))
            self.assertIn("SYSTEM_ONE_DEFAULT_PROVIDER=mock", env_path.read_text(encoding="utf-8"))

    def test_defaults_to_mock_when_legacy_env_disables_laya_without_a_system_one_default(self):
        with tempfile.TemporaryDirectory() as directory:
            env_path = Path(directory) / ".env"
            env_path.write_text("LAYA_ENABLED=false\nLAYA_API_KEY=\n", encoding="utf-8")

            configure_local_laya(env_path, Path(directory) / "unused-example")

            self.assertIn("SYSTEM_ONE_DEFAULT_PROVIDER=mock", env_path.read_text(encoding="utf-8"))

    def test_preserves_system_one_aggregation_setting_and_thresholds(self):
        with tempfile.TemporaryDirectory() as directory:
            env_path = Path(directory) / ".env"
            env_path.write_text(
                "LAYA_ENABLED=true\nSYSTEM_ONE_AGGREGATION_ENABLED=true\n"
                "SYSTEM_ONE_AGGREGATION_DIRECT_SUPPORT_THRESHOLD=0.80\n",
                encoding="utf-8",
            )

            configure_local_laya(env_path, Path(directory) / "unused-example")

            contents = env_path.read_text(encoding="utf-8")
            self.assertIn("SYSTEM_ONE_AGGREGATION_ENABLED=true", contents)
            self.assertIn("SYSTEM_ONE_AGGREGATION_DIRECT_SUPPORT_THRESHOLD=0.80", contents)

    def test_preserves_an_explicit_system_one_aggregation_opt_out(self):
        with tempfile.TemporaryDirectory() as directory:
            env_path = Path(directory) / ".env"
            env_path.write_text(
                "LAYA_ENABLED=true\nSYSTEM_ONE_AGGREGATION_ENABLED=false\nLAYA_API_KEY=key\n",
                encoding="utf-8",
            )

            configure_local_laya(env_path, Path(directory) / "unused-example")

            self.assertIn("SYSTEM_ONE_AGGREGATION_ENABLED=false", env_path.read_text(encoding="utf-8"))

    def test_migrates_legacy_laya_aggregation_settings_to_shared_system_one_names(self):
        with tempfile.TemporaryDirectory() as directory:
            env_path = Path(directory) / ".env"
            env_path.write_text(
                "LAYA_ENABLED=true\nLOCAL_LAYA_AGGREGATION_ENABLED=false\n"
                "LOCAL_LAYA_AGGREGATION_DIRECT_SUPPORT_THRESHOLD=0.91\nLAYA_API_KEY=key\n",
                encoding="utf-8",
            )

            configure_local_laya(env_path, Path(directory) / "unused-example")

            contents = env_path.read_text(encoding="utf-8")
            self.assertIn("SYSTEM_ONE_AGGREGATION_ENABLED=false", contents)
            self.assertIn("SYSTEM_ONE_AGGREGATION_DIRECT_SUPPORT_THRESHOLD=0.91", contents)

    def test_preserves_an_existing_private_api_key(self):
        with tempfile.TemporaryDirectory() as directory:
            env_path = Path(directory) / ".env"
            env_path.write_text("LAYA_ENABLED=true\nLAYA_API_KEY=operator-provided-key\n", encoding="utf-8")

            configure_local_laya(env_path, Path(directory) / "unused-example")

            self.assertIn("LAYA_API_KEY=operator-provided-key", env_path.read_text(encoding="utf-8"))
            self.assertEqual(stat.S_IMODE(env_path.stat().st_mode), 0o600)


if __name__ == "__main__":
    unittest.main()
