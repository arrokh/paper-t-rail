"""Run the pinned first-party laya-serve API against the verified local checkpoint."""

import json
import os
from pathlib import Path

from api_key_auth import ApiKeyMiddleware
from download_model import (
    MANIFEST_NAME,
    MODEL_CONTEXT_TOKENS,
    MODEL_GIT_BLOB_OIDS,
    MODEL_ID,
    MODEL_REVISION,
    MODEL_WEIGHTS_SHA256,
    REQUIRED_FILES,
    git_blob_oid,
    runtime_identity,
    sha256_file,
)
from laya_serve_preflight import install_context_guard


def verified_checkpoint(cache_home: Path) -> Path:
    manifest_path = cache_home / MANIFEST_NAME
    if not manifest_path.is_file():
        raise RuntimeError("The approved Laya checkpoint has not been downloaded and verified.")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("runtime") != runtime_identity():
        raise RuntimeError("The local Laya runtime manifest does not match the approved version and source commit.")
    if manifest.get("model_id") != MODEL_ID or manifest.get("revision") != MODEL_REVISION or \
            manifest.get("model_weights_sha256") != MODEL_WEIGHTS_SHA256 or \
            manifest.get("model_file_oids") != MODEL_GIT_BLOB_OIDS or \
            manifest.get("context_tokens") != MODEL_CONTEXT_TOKENS:
        raise RuntimeError("The local Laya artifact manifest does not match the approved checkpoint.")

    checkpoint = Path(manifest.get("snapshot_path", "")).resolve()
    if checkpoint.name != MODEL_REVISION or not checkpoint.is_dir():
        raise RuntimeError("The local Laya snapshot path does not match the approved checkpoint revision.")
    if any(not (checkpoint / filename).is_file() for filename in REQUIRED_FILES):
        raise RuntimeError("The local Laya snapshot is missing required model artifacts.")
    if any(git_blob_oid(checkpoint / filename) != expected_oid for filename, expected_oid in MODEL_GIT_BLOB_OIDS.items()):
        raise RuntimeError("A local Laya configuration or tokenizer digest is not approved.")
    if sha256_file(checkpoint / "model.safetensors") != MODEL_WEIGHTS_SHA256:
        raise RuntimeError("The local Laya weight digest does not match the approved checkpoint artifact.")
    model_config = json.loads((checkpoint / "rl_agent_config.json").read_text(encoding="utf-8"))
    if model_config.get("max_len") != MODEL_CONTEXT_TOKENS:
        raise RuntimeError("The local Laya checkpoint does not match the approved context length.")
    return checkpoint


def build_app():
    runtime_identity()
    if not os.environ.get("LAYA_API_KEY", "").strip():
        raise RuntimeError("Set LAYA_API_KEY before starting the Laya sidecar.")
    if os.environ.get("LAYA_DEVICE", "cpu").lower() != "cpu":
        raise RuntimeError("The approved Docker evaluation runtime is CPU-only.")

    from laya import Router
    from laya.serve import create_app

    cache_home = Path(os.environ.get("HF_HOME", "/models/hf"))
    checkpoint = verified_checkpoint(cache_home)
    router = Router(
        device="cpu",
        max_loaded=1,
        auto_task_detection=False,
        models={"typed-decisions": str(checkpoint)},
    )
    router.preload(["typed-decisions"])
    install_context_guard(router)
    return ApiKeyMiddleware(create_app(router=router), os.environ["LAYA_API_KEY"].strip())


def main() -> None:
    import uvicorn

    uvicorn.run(
        build_app(),
        host=os.environ.get("LAYA_HOST", "0.0.0.0"),
        port=int(os.environ.get("LAYA_PORT", "8000")),
        log_level=os.environ.get("LAYA_LOG_LEVEL", "info"),
    )


if __name__ == "__main__":
    main()
