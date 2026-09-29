"""Create a private local Compose Laya key without displaying or checking it in."""

import argparse
import os
import secrets
import shutil
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
ENV_PATH = REPO_ROOT / ".env"
EXAMPLE_PATH = REPO_ROOT / ".env.example"


def parse_values(lines: list[str]) -> dict[str, str]:
    values = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        name, value = stripped.split("=", 1)
        values[name.strip()] = value.strip().strip('"').strip("'").strip()
    return values


def set_value(lines: list[str], name: str, value: str) -> list[str]:
    prefix = name + "="
    matching_indices = [index for index, line in enumerate(lines) if line.strip().startswith(prefix)]
    if len(matching_indices) > 1:
        raise ValueError(f".env contains duplicate {name} entries.")
    updated = list(lines)
    if matching_indices:
        updated[matching_indices[0]] = prefix + value
    else:
        if updated and updated[-1].strip():
            updated.append("")
        updated.append(prefix + value)
    return updated


def configure_local_laya(env_path: Path = ENV_PATH, example_path: Path = EXAMPLE_PATH) -> bool:
    if not env_path.exists():
        shutil.copyfile(example_path, env_path)
    lines = env_path.read_text(encoding="utf-8").splitlines()
    values = parse_values(lines)
    if "LAYA_ENABLED" not in values:
        lines = set_value(lines, "LAYA_ENABLED", "true")
        values["LAYA_ENABLED"] = "true"
    if "LOCAL_LAYA_AGGREGATION_ENABLED" not in values:
        lines = set_value(lines, "LOCAL_LAYA_AGGREGATION_ENABLED", "true")
        values["LOCAL_LAYA_AGGREGATION_ENABLED"] = "true"

    enabled = values["LAYA_ENABLED"].lower() == "true"
    default_provider = values.get("SYSTEM_ONE_DEFAULT_PROVIDER", "").strip()
    if not enabled and default_provider in {"", "laya"}:
        lines = set_value(lines, "SYSTEM_ONE_DEFAULT_PROVIDER", "mock")
        values["SYSTEM_ONE_DEFAULT_PROVIDER"] = "mock"
    elif enabled and not default_provider:
        lines = set_value(lines, "SYSTEM_ONE_DEFAULT_PROVIDER", "laya")
        values["SYSTEM_ONE_DEFAULT_PROVIDER"] = "laya"
    key = values.get("LAYA_API_KEY", "")
    if enabled and not key:
        lines = set_value(lines, "LAYA_API_KEY", secrets.token_hex(32))

    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=env_path.parent,
        prefix=f".{env_path.name}.",
        suffix=".tmp",
        delete=False,
    ) as temporary_file:
        temporary_path = Path(temporary_file.name)
        os.chmod(temporary_path, 0o600)
        temporary_file.write("\n".join(lines) + "\n")
    os.replace(temporary_path, env_path)
    os.chmod(env_path, 0o600)
    return enabled


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enabled-only", action="store_true", help="print only whether local Laya is enabled")
    args = parser.parse_args()
    enabled = configure_local_laya()
    if args.enabled_only:
        print(str(enabled).lower())
    elif enabled:
        print("Local Laya evaluation is enabled; its API key is stored privately in .env.")
    else:
        print("Local Laya evaluation is disabled by .env.")


if __name__ == "__main__":
    main()
