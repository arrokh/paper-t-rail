"""Exercise local startup with isolated package-manager and service boundaries."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]


class LocalStartupTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.log_path = self.root / "commands.log"
        self.bin_path = self.root / "bin"
        self.bin_path.mkdir()
        shutil.copyfile(REPO_ROOT / "Makefile", self.root / "Makefile")
        (self.root / "scripts").mkdir()
        shutil.copyfile(
            REPO_ROOT / "scripts" / "prepare_local_laya_env.py",
            self.root / "scripts" / "prepare_local_laya_env.py",
        )
        (self.root / ".env.example").write_text("API_HOST_PORT=8080\nLAYA_ENABLED=false\n")
        for service in ("web", "homepage"):
            (self.root / service).mkdir()
        self.env = dict(os.environ)
        # Do not let parent make overrides bypass the isolated command stubs.
        for variable in ("MAKEFLAGS", "MFLAGS", "MAKELEVEL", "MAKEOVERRIDES"):
            self.env.pop(variable, None)
        self.env.update(
            PATH=f"{self.bin_path}{os.pathsep}{os.environ['PATH']}",
            LOCAL_TEST_LOG=str(self.log_path),
        )
        self.write_command(
            "mise",
            """set -eu
printf 'mise %s\n' "$*" >> "$LOCAL_TEST_LOG"
if [ "$1" = install ]; then
    exit "${LOCAL_TEST_TOOL_EXIT_CODE:-0}"
fi
[ "$1" = exec ] && [ "$2" = -- ] && [ "$3" = pnpm ] || exit 2
service="$5"
action="$6"
if [ "$action" = install ]; then
    if [ "${LOCAL_TEST_FAIL_SERVICE:-}" = "$service" ]; then exit 5; fi
    mkdir -p "$service/node_modules/.bin"
    if [ "$service" = web ]; then executable=next; else executable=astro; fi
    touch "$service/node_modules/.bin/$executable"
    chmod +x "$service/node_modules/.bin/$executable"
elif [ "$action" = exec ]; then
    test -x "$service/node_modules/.bin/$7" || exit 3
    printf 'started %s\n' "$service" >> "$LOCAL_TEST_LOG"
else
    exit 2
fi
""",
        )
        for command in ("docker", "curl", "lsof"):
            self.write_command(
                command,
                f"printf '{command} %s\\n' \"$*\" >> \"$LOCAL_TEST_LOG\"\n"
                f"exit {1 if command == 'lsof' else 0}\n",
            )

    def write_command(self, name, body):
        path = self.bin_path / name
        path.write_text("#!/bin/sh\n" + body)
        path.chmod(0o755)

    def run_local(self, *services, **env):
        return subprocess.run(
            ["make", "--no-print-directory", "local", *services],
            cwd=self.root,
            env={**self.env, **env},
            capture_output=True,
            text=True,
            timeout=10,
        )

    def command_log(self):
        return self.log_path.read_text().splitlines() if self.log_path.exists() else []

    def assert_success(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_web_installs_missing_dependencies_before_starting(self):
        env_path = self.root / ".env"
        original_env = "API_HOST_PORT=8091\nLAYA_ENABLED=false\nCUSTOM_SETTING=keep-me\n"
        env_path.write_text(original_env)

        self.assert_success(self.run_local("web"))

        commands = self.command_log()
        self.assertEqual(commands[0], "mise install")
        install = "mise exec -- pnpm --dir web install --frozen-lockfile"
        self.assertLess(commands.index(install), commands.index("started web"))
        self.assertNotIn("mise exec -- pnpm --dir homepage install --frozen-lockfile", commands)
        self.assertTrue((self.root / "web/node_modules/.bin/next").is_file())
        self.assertEqual(env_path.read_text(), original_env)

    def test_homepage_installs_missing_dependencies_without_waiting_for_api(self):
        self.assert_success(self.run_local("homepage"))

        commands = self.command_log()
        install = "mise exec -- pnpm --dir homepage install --frozen-lockfile"
        self.assertLess(commands.index(install), commands.index("started homepage"))
        self.assertFalse(any(command.startswith("curl ") for command in commands))
        self.assertFalse((self.root / "web/node_modules").exists())
        self.assertFalse((self.root / ".env").exists())

    def test_default_startup_prepares_both_frontends_before_starting_containers(self):
        self.assert_success(self.run_local())

        commands = self.command_log()
        first_container_command = next(
            index for index, command in enumerate(commands) if command.startswith("docker ")
        )
        for command in (
            "mise install",
            "mise exec -- pnpm --dir web install --frozen-lockfile",
            "mise exec -- pnpm --dir homepage install --frozen-lockfile",
        ):
            self.assertLess(commands.index(command), first_container_command)
        self.assertIn("started web", commands)
        self.assertIn("started homepage", commands)

    def test_existing_dependencies_are_rechecked_on_every_start(self):
        self.assert_success(self.run_local("web"))
        self.assert_success(self.run_local("web"))

        commands = self.command_log()
        self.assertEqual(commands.count("mise install"), 2)
        self.assertEqual(commands.count("mise exec -- pnpm --dir web install --frozen-lockfile"), 2)
        self.assertEqual(commands.count("started web"), 2)

    def test_container_only_selection_does_not_install_frontend_dependencies(self):
        self.assert_success(self.run_local("api"))

        commands = self.command_log()
        self.assertEqual(commands[0], "mise install")
        self.assertFalse(any("pnpm" in command for command in commands))
        self.assertTrue(any(command.endswith("up -d api") for command in commands))

    def test_setup_failures_stop_before_any_service_is_started(self):
        scenarios = (
            ({"LOCAL_TEST_TOOL_EXIT_CODE": "5"}, "mise install"),
            ({"LOCAL_TEST_FAIL_SERVICE": "web"}, "mise exec -- pnpm --dir web install --frozen-lockfile"),
            (
                {"LOCAL_TEST_FAIL_SERVICE": "homepage"},
                "mise exec -- pnpm --dir homepage install --frozen-lockfile",
            ),
        )
        for env, failed_command in scenarios:
            with self.subTest(env=env):
                self.log_path.unlink(missing_ok=True)

                result = self.run_local(**env)

                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                commands = self.command_log()
                self.assertIn(failed_command, commands)
                self.assertTrue(all(command.startswith("mise ") for command in commands), commands)

    def test_invalid_selection_is_rejected_before_setup(self):
        result = self.run_local("unknown")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unsupported service(s)", result.stderr)
        self.assertEqual(self.command_log(), [])


if __name__ == "__main__":
    unittest.main()
