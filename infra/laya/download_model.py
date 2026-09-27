"""Download the approved checkpoint revision and verify its pinned weight digest."""

import hashlib
import importlib.metadata
import json
import os
from pathlib import Path

MODEL_ID = "convaiinnovations/laya-typed-decisions"
MODEL_REVISION = "1a793eb568e6718f15941d08f85432581df534e3"
RUNTIME_PACKAGE = "laya"
RUNTIME_VERSION = "0.3.20"
RUNTIME_SOURCE_COMMIT = "23a17522aa4942da6cce53a995a275760320b691"
RUNTIME_ID = f"laya-serve-{RUNTIME_VERSION}@{RUNTIME_SOURCE_COMMIT}"
MANIFEST_NAME = "paper-t-rail-laya-model.json"
MODEL_WEIGHTS_SHA256 = "4fa56de72383a9d3efa9cfa78955733c81b9fc8067a587ca4beb82c78107a24e"
MODEL_CONTEXT_TOKENS = 1024
MODEL_GIT_BLOB_OIDS = {
    "rl_agent_config.json": "5f0e1d5f2366fe8ba2ff330dffaeed53b469e97e",
    "encoder/config.json": "d4be4829750fb04c0aa8b9897c3ea827f76c0109",
    "tokenizer/tokenizer.json": "2f4d8583e507b7466d2490e2d6c045647a822698",
    "tokenizer/tokenizer_config.json": "ed1ffabc2ce11120754705709569e365e46da71a",
}
REQUIRED_FILES = (
    "model.safetensors",
    "rl_agent_config.json",
    "encoder/config.json",
    "tokenizer/tokenizer.json",
    "tokenizer/tokenizer_config.json",
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as model_file:
        for chunk in iter(lambda: model_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_blob_oid(path: Path) -> str:
    size = path.stat().st_size
    digest = hashlib.sha1(f"blob {size}\0".encode("ascii"), usedforsecurity=False)
    with path.open("rb") as artifact:
        for chunk in iter(lambda: artifact.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def runtime_identity() -> dict[str, str]:
    installed_version = importlib.metadata.version(RUNTIME_PACKAGE)
    source_commit = os.environ.get("LAYA_SOURCE_COMMIT", "")
    if installed_version != RUNTIME_VERSION or source_commit != RUNTIME_SOURCE_COMMIT:
        raise RuntimeError("The installed Laya runtime does not match the approved version and source commit.")
    return {
        "id": RUNTIME_ID,
        "package": RUNTIME_PACKAGE,
        "version": installed_version,
        "source_commit": source_commit,
    }


def download_and_verify(cache_home: Path) -> Path:
    runtime = runtime_identity()
    from huggingface_hub import HfApi, snapshot_download

    repo = HfApi().model_info(MODEL_ID, revision=MODEL_REVISION)
    if repo.sha != MODEL_REVISION:
        raise RuntimeError("The Hugging Face checkpoint revision does not match the approved commit.")

    snapshot = Path(snapshot_download(
        repo_id=MODEL_ID,
        revision=MODEL_REVISION,
        cache_dir=str(cache_home / "hub"),
    ))
    if snapshot.name != MODEL_REVISION:
        raise RuntimeError("The downloaded checkpoint snapshot is not the approved revision.")
    missing_files = [name for name in REQUIRED_FILES if not (snapshot / name).is_file()]
    if missing_files:
        raise RuntimeError("The approved checkpoint snapshot is missing required model artifacts.")

    model_config = json.loads((snapshot / "rl_agent_config.json").read_text(encoding="utf-8"))
    if model_config.get("max_len") != MODEL_CONTEXT_TOKENS:
        raise RuntimeError("The downloaded checkpoint does not match the approved context length.")
    if any(git_blob_oid(snapshot / filename) != expected_oid for filename, expected_oid in MODEL_GIT_BLOB_OIDS.items()):
        raise RuntimeError("A downloaded checkpoint configuration or tokenizer digest is not approved.")
    weights_sha256 = sha256_file(snapshot / "model.safetensors")
    if weights_sha256 != MODEL_WEIGHTS_SHA256:
        raise RuntimeError("The downloaded checkpoint weight digest does not match the approved artifact.")

    cache_home.mkdir(parents=True, exist_ok=True)
    manifest = {
        "runtime": runtime,
        "model_id": MODEL_ID,
        "revision": MODEL_REVISION,
        "snapshot_path": str(snapshot),
        "model_weights_sha256": weights_sha256,
        "model_file_oids": MODEL_GIT_BLOB_OIDS,
        "context_tokens": MODEL_CONTEXT_TOKENS,
    }
    manifest_path = cache_home / MANIFEST_NAME
    temporary_path = manifest_path.with_suffix(".json.tmp")
    temporary_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary_path, manifest_path)
    return snapshot


def main() -> None:
    cache_home = Path(os.environ.get("HF_HOME", "/models/hf"))
    snapshot = download_and_verify(cache_home)
    print(json.dumps({"status": "verified", "runtime": runtime_identity(), "model_id": MODEL_ID,
                      "revision": MODEL_REVISION, "snapshot_path": str(snapshot),
                      "weights_sha256": MODEL_WEIGHTS_SHA256}))


if __name__ == "__main__":
    main()
