import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from prepare_credentials import (
    ConfigurationError,
    apply,
    plan,
    read_env,
    render_env,
)


class CredentialPreparationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "cdc").mkdir()
        self.key = base64.b64encode(b"k" * 32).decode()
        self.environment = {
            "DEPLOY_POSTGRES_PASSWORD": "db-test-only",
            "DEPLOY_INITIAL_ADMIN_PASSWORD": "admin-test-only",
            "DEPLOY_GRAFANA_PASSWORD": "grafana-test-only",
            "DEPLOY_INTERNAL_TOKEN": "token-test-only",
            "DEPLOY_SOURCE_KEY": self.key,
            "DEPLOY_ERP_CDC_PASSWORD": "erp-test-only",
            "DEPLOY_MES_CDC_PASSWORD": "mes-test-only",
        }
        self.values = {
            "POSTGRES_DB": "axcore", "POSTGRES_USER": "axcore",
            "GRAFANA_ADMIN_USER": "admin-check", "BACKEND_TAG": "abc1234",
            "FRONTEND_TAG": "abc1234", "AI_TAG": "def5678",
            "POSTGRES_PASSWORD": "db-test-only",
            "AXCORE_SOURCE_CREDENTIAL_KEY_1": self.key,
            "AXCORE_INTERNAL_API_TOKEN": "token-test-only",
            "AI_ONTOLOGY_INTERNAL_TOKEN": "token-test-only",
        }
        self.write(".env", self.values)
        self.write("cdc/.env", {
            "ERP_CDC_PASSWORD": "erp-test-only", "MES_CDC_PASSWORD": "mes-test-only",
        })

    def write(self, name, values):
        (self.root / name).write_text(render_env(values), encoding="utf-8")

    def test_shared_token_general_settings_and_tags_survive(self):
        apply(self.root, self.environment)
        config = read_env(self.root / ".env.config")
        runtime = read_env(self.root / ".env")
        self.assertEqual(config["POSTGRES_DB"], "axcore")
        self.assertNotIn("POSTGRES_PASSWORD", config)
        self.assertNotIn("BACKEND_TAG", config)
        self.assertEqual(runtime["BACKEND_TAG"], "abc1234")
        self.assertEqual(runtime["AXCORE_INTERNAL_API_TOKEN"], runtime["AI_ONTOLOGY_INTERNAL_TOKEN"])
        self.assertEqual(runtime["AXCORE_SOURCE_CREDENTIAL_KEY_1"], self.key)
        self.assertEqual(set(read_env(self.root / "cdc/.env")), {"ERP_CDC_PASSWORD", "MES_CDC_PASSWORD"})

    def test_missing_credential_does_not_write_any_file(self):
        del self.environment["DEPLOY_MES_CDC_PASSWORD"]
        before = (self.root / ".env").read_bytes()
        with self.assertRaises(ConfigurationError):
            apply(self.root, self.environment)
        self.assertEqual((self.root / ".env").read_bytes(), before)
        self.assertFalse((self.root / ".env.config").exists())

    def test_accidental_secret_rotation_stops_before_write(self):
        for name in ("DEPLOY_SOURCE_KEY", "DEPLOY_POSTGRES_PASSWORD", "DEPLOY_INTERNAL_TOKEN", "DEPLOY_ERP_CDC_PASSWORD"):
            with self.subTest(name=name):
                with self.assertRaises(ConfigurationError):
                    plan(self.root, {**self.environment, name: "different-test-only"})
        self.assertFalse((self.root / ".env.config").exists())

    def test_special_characters_roundtrip_without_shell_evaluation(self):
        password = "test-only $HOME $(whoami) # ' \\ \" !"
        self.values["POSTGRES_PASSWORD"] = password
        self.environment["DEPLOY_POSTGRES_PASSWORD"] = password
        self.write(".env", self.values)
        apply(self.root, self.environment)
        self.assertEqual(read_env(self.root / ".env")["POSTGRES_PASSWORD"], password)

    @unittest.skipUnless(shutil.which("docker"), "Docker CLI가 필요합니다.")
    def test_compose_preserves_literal_special_characters(self):
        password = "test-only $HOME ${NOT_A_VARIABLE} $$ # ' \\ \" !"
        self.values["POSTGRES_PASSWORD"] = password
        self.environment["DEPLOY_POSTGRES_PASSWORD"] = password
        self.write(".env", self.values)
        apply(self.root, self.environment)
        compose = self.root / "compose.yaml"
        compose.write_text(
            'services:\n  check:\n    image: busybox:1.37\n'
            '    environment:\n      PASSWORD: ${POSTGRES_PASSWORD}\n',
            encoding="utf-8",
        )
        environment = {key: value for key, value in os.environ.items() if key != "POSTGRES_PASSWORD"}
        result = subprocess.run(
            ["docker", "compose", "--env-file", str(self.root / ".env"), "-f", str(compose),
             "config", "--format", "json"],
            check=True, capture_output=True, text=True, encoding="utf-8", env=environment,
        )
        # Compose config는 재사용 가능한 설정 출력에서 리터럴 달러를 다시 이스케이프한다.
        # https://github.com/docker/compose/blob/main/cmd/compose/config.go (runConfig)
        self.assertEqual(
            json.loads(result.stdout)["services"]["check"]["environment"]["PASSWORD"],
            password.replace("$", "$$"),
        )
        resolved = subprocess.run(
            ["docker", "compose", "--env-file", str(self.root / ".env"), "-f", str(compose),
             "config", "--environment"],
            check=True, capture_output=True, text=True, encoding="utf-8", env=environment,
        )
        # 출력용 이스케이프와 별개로 dotenv를 읽은 실제 치환 환경은 원래 값이어야 한다.
        resolved_values = dict(line.partition("=")[::2] for line in resolved.stdout.splitlines() if "=" in line)
        self.assertEqual(resolved_values["POSTGRES_PASSWORD"], password)

    def test_duplicate_keys_are_rejected(self):
        with (self.root / ".env").open("a", encoding="utf-8") as handle:
            handle.write("POSTGRES_DB=another\n")
        with self.assertRaises(ConfigurationError):
            plan(self.root, self.environment)

    def test_unregistered_ai_key_is_not_discarded(self):
        self.values["AI_ONTOLOGY_LLM_API_KEY"] = "test-api-key"
        self.write(".env", self.values)
        with self.assertRaises(ConfigurationError):
            plan(self.root, self.environment)

    def test_general_config_cannot_contain_secrets(self):
        self.write(".env.config", {"POSTGRES_PASSWORD": "bad-test-only"})
        with self.assertRaises(ConfigurationError):
            plan(self.root, self.environment)

    def test_general_config_changes_and_new_deployment_tag_are_preserved(self):
        apply(self.root, self.environment)
        config = read_env(self.root / ".env.config")
        config["AI_ONTOLOGY_LLM_MODEL"] = "model-check"
        self.write(".env.config", config)
        runtime = read_env(self.root / ".env")
        runtime["BACKEND_TAG"] = "new5678"
        self.write(".env", runtime)
        apply(self.root, self.environment)
        self.assertEqual(read_env(self.root / ".env")["BACKEND_TAG"], "new5678")
        self.assertEqual(read_env(self.root / ".env")["AI_ONTOLOGY_LLM_MODEL"], "model-check")

    def test_variable_expression_is_rejected_without_execution(self):
        (self.root / ".env").write_text("POSTGRES_PASSWORD=$UNRESOLVED\n", encoding="utf-8")
        with self.assertRaises(ConfigurationError):
            plan(self.root, self.environment)

    def test_partial_write_failure_restores_originals(self):
        from prepare_credentials import atomic_write
        # 실행 OS와 무관하게 CRLF 원본의 바이트 단위 복구를 확인한다.
        path = self.root / ".env"
        path.write_bytes(path.read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n"))
        before = (self.root / ".env").read_bytes()
        calls = 0

        def failing_write(path, content):
            nonlocal calls
            calls += 1
            if calls == 3:
                raise OSError("test-only failure")
            atomic_write(path, content)

        with patch("prepare_credentials.atomic_write", side_effect=failing_write):
            with self.assertRaises(ConfigurationError):
                apply(self.root, self.environment)
        self.assertEqual((self.root / ".env").read_bytes(), before)
        self.assertFalse((self.root / ".env.config").exists())


if __name__ == "__main__":
    unittest.main()
