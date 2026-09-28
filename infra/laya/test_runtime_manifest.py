import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import ModuleType
from unittest.mock import Mock, patch

import download_model


class RuntimeManifestTest(unittest.TestCase):
    @patch.dict(os.environ, {"LAYA_SOURCE_COMMIT": download_model.RUNTIME_SOURCE_COMMIT}, clear=False)
    @patch("download_model.importlib.metadata.version", return_value=download_model.RUNTIME_VERSION)
    def test_verified_checkpoint_manifest_includes_pinned_runtime(self, _version):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot = root / "snapshots" / download_model.MODEL_REVISION
            snapshot.mkdir(parents=True)
            artifacts = {
                "model.safetensors": b"test model weights",
                "rl_agent_config.json": json.dumps({"max_len": download_model.MODEL_CONTEXT_TOKENS}).encode(),
                "encoder/config.json": b"encoder config",
                "tokenizer/tokenizer.json": b"tokenizer",
                "tokenizer/tokenizer_config.json": b"tokenizer config",
            }
            for relative_path, content in artifacts.items():
                artifact = snapshot / relative_path
                artifact.parent.mkdir(parents=True, exist_ok=True)
                artifact.write_bytes(content)

            blob_oids = {
                relative_path: download_model.git_blob_oid(snapshot / relative_path)
                for relative_path in download_model.MODEL_GIT_BLOB_OIDS
            }
            weights_sha256 = hashlib.sha256(artifacts["model.safetensors"]).hexdigest()
            hf_client = Mock()
            hf_client.model_info.return_value.sha = download_model.MODEL_REVISION
            hf_module = ModuleType("huggingface_hub")
            hf_module.HfApi = Mock(return_value=hf_client)
            hf_module.snapshot_download = Mock(return_value=str(snapshot))
            with (
                patch.dict(sys.modules, {"huggingface_hub": hf_module}),
                patch.object(download_model, "MODEL_GIT_BLOB_OIDS", blob_oids),
                patch.object(download_model, "MODEL_WEIGHTS_SHA256", weights_sha256),
            ):
                download_model.download_and_verify(root / "cache")

            manifest_path = root / "cache" / download_model.MANIFEST_NAME
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(download_model.runtime_identity(), manifest["runtime"])
            self.assertEqual(download_model.RUNTIME_ID, manifest["runtime"]["id"])

    @patch.dict(os.environ, {"LAYA_SOURCE_COMMIT": download_model.RUNTIME_SOURCE_COMMIT}, clear=False)
    @patch("download_model.importlib.metadata.version", return_value="0.3.21")
    def test_rejects_an_unpinned_installed_runtime_version(self, _version):
        with self.assertRaisesRegex(RuntimeError, "approved version and source commit"):
            download_model.runtime_identity()

    @patch.dict(os.environ, {"LAYA_SOURCE_COMMIT": "wrong-commit"}, clear=False)
    @patch("download_model.importlib.metadata.version", return_value=download_model.RUNTIME_VERSION)
    def test_rejects_an_unpinned_runtime_source_commit(self, _version):
        with self.assertRaisesRegex(RuntimeError, "approved version and source commit"):
            download_model.runtime_identity()


if __name__ == "__main__":
    unittest.main()
