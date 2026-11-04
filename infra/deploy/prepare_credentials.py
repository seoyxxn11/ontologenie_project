"""Jenkins 배포 전 비밀값을 검증하고 Compose 실행용 환경 파일을 생성한다."""

import argparse
import base64
import json
import os
from pathlib import Path
import re
import tempfile


MAIN_SECRETS = {
    "POSTGRES_PASSWORD": "DEPLOY_POSTGRES_PASSWORD",
    "AXCORE_INITIAL_ADMIN_PASSWORD": "DEPLOY_INITIAL_ADMIN_PASSWORD",
    "GRAFANA_ADMIN_PASSWORD": "DEPLOY_GRAFANA_PASSWORD",
    "AXCORE_INTERNAL_API_TOKEN": "DEPLOY_INTERNAL_TOKEN",
    "AI_ONTOLOGY_INTERNAL_TOKEN": "DEPLOY_INTERNAL_TOKEN",
    "AXCORE_SOURCE_CREDENTIAL_KEY_1": "DEPLOY_SOURCE_KEY",
}
CDC_SECRETS = {
    "ERP_CDC_PASSWORD": "DEPLOY_ERP_CDC_PASSWORD",
    "MES_CDC_PASSWORD": "DEPLOY_MES_CDC_PASSWORD",
}
TAGS = {"BACKEND_TAG", "FRONTEND_TAG", "AI_TAG"}
OPTIONAL_KEYS = {"AI_ONTOLOGY_LLM_API_KEY", "AI_ONTOLOGY_GMS_API_KEY"}


class ConfigurationError(Exception):
    """실제 값을 포함하지 않는 설정 오류."""


def read_env(path):
    """셸 실행 없이 단일 행 dotenv를 읽는다. 모호한 표현식은 변경 전에 거부한다."""
    values = {}
    if not path.is_file() or path.is_symlink():
        raise ConfigurationError(f"{path.name}: 일반 파일이 필요합니다.")
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, raw = line.removeprefix("export ").partition("=")
        key, raw = key.strip(), raw.strip()
        if not sep or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ConfigurationError(f"{path.name}: 지원하지 않는 설정 형식입니다.")
        if key in values:
            raise ConfigurationError(f"{path.name}: {key} 중복 설정입니다.")
        if raw.startswith("'"):
            if len(raw) < 2 or not raw.endswith("'"):
                raise ConfigurationError(f"{key}: 단일 행 따옴표 형식 확인이 필요합니다.")
            value = raw[1:-1].replace("\\'", "'")
        else:
            if raw.startswith('"'):
                try:
                    value = json.loads(raw)
                except (ValueError, TypeError):
                    raise ConfigurationError(f"{key}: 따옴표 형식 확인이 필요합니다.") from None
                if not isinstance(value, str):
                    raise ConfigurationError(f"{key}: 문자열이 필요합니다.")
            else:
                value = re.split(r"\s+#", raw, maxsplit=1)[0].rstrip()
            # Compose가 해석할 수 있는 변수 치환을 임의로 재해석하지 않는다.
            if re.search(r"(?<!\$)\$(?!\$)", value.replace("$$", "")):
                raise ConfigurationError(f"{key}: 달러 표현식은 수동 확인이 필요합니다.")
            value = value.replace("$$", "$")
        values[key] = value
    return values


def render_env(values, generated=True):
    lines = ["# Jenkins 생성 파일. 비밀값 원본은 Credentials. 직접 수정하지 않는다." if generated
             else "# 일반 설정 원본. 비밀값·이미지 태그를 넣지 않는다."]
    for key, value in sorted(values.items()):
        if any(char in value for char in "\r\n\0"):
            raise ConfigurationError(f"{key}: 줄바꿈·NUL은 지원하지 않습니다.")
        encoded = value if re.fullmatch(r"[A-Za-z0-9_.:/@+\-=]*", value) else (
            json.dumps(value, ensure_ascii=False).replace("$", "$$")
        )
        lines.append(f"{key}={encoded}")
    return "\n".join(lines) + "\n"


def credentials(mapping, environment, current):
    result = {}
    for key, source in mapping.items():
        value = environment.get(source, "")
        if not value or any(char in value for char in "\r\n\0"):
            raise ConfigurationError(f"{key}: Credentials 누락 또는 잘못된 형식입니다.")
        # 이번 전환은 키·비밀번호 회전이 아니다. 실수로 다른 값을 넣으면 중단한다.
        if current.get(key) and current[key] != value:
            raise ConfigurationError(f"{key}: 기존 값과 Credentials가 달라 중단했습니다.")
        result[key] = value
    return result


def plan(root, environment):
    main_path, cdc_path = root / ".env", root / "cdc/.env"
    main, cdc = read_env(main_path), read_env(cdc_path)
    main_secrets = credentials(MAIN_SECRETS, environment, main)
    cdc_secrets = credentials(CDC_SECRETS, environment, cdc)
    try:
        if len(base64.b64decode(main_secrets["AXCORE_SOURCE_CREDENTIAL_KEY_1"], validate=True)) != 32:
            raise ValueError()
    except ValueError:
        raise ConfigurationError("암호화 키는 기존 32바이트 base64 값이어야 합니다.") from None
    for key in OPTIONAL_KEYS:
        if main.get(key):
            raise ConfigurationError(f"{key}: 별도 Credentials 등록·연결이 필요합니다.")

    writes = {}
    previous_settings = {}
    for path, old, secrets_map, tags in (
        (main_path, main, main_secrets, TAGS),
        (cdc_path, cdc, cdc_secrets, set()),
    ):
        config_path = path.with_name(".env.config")
        config = read_env(config_path) if config_path.exists() else {
            key: value for key, value in old.items()
            if key not in secrets_map and key not in tags and key not in OPTIONAL_KEYS
        }
        for key in config:
            if key in secrets_map or key in tags or re.search(
                r"PASSWORD|TOKEN|SECRET|API_KEY|CREDENTIAL_KEY_(?!VERSION)", key
            ):
                raise ConfigurationError(f".env.config: {key}는 일반 설정이 아닙니다.")
        if path == main_path:
            for key in ("POSTGRES_DB", "POSTGRES_USER", "GRAFANA_ADMIN_USER"):
                if not config.get(key):
                    raise ConfigurationError(f".env.config: {key}가 필요합니다.")
            for key in ("POSTGRES_DB", "POSTGRES_USER", "AXCORE_SOURCE_CREDENTIAL_KEY_VERSION"):
                if old.get(key, "1" if key.endswith("VERSION") else "") != config.get(key, "1" if key.endswith("VERSION") else ""):
                    raise ConfigurationError(f"{key}: DB·암호화 키 전환은 별도 절차가 필요합니다.")
            for key in tags:
                if old.get(key) and not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]*", old[key]):
                    raise ConfigurationError(f"{key}: 이미지 태그 형식 오류입니다.")
        writes[config_path] = render_env(config, generated=False)
        writes[path] = render_env({**config, **{key: old.get(key, "") for key in tags}, **secrets_map})
        previous_settings["main" if path == main_path else "cdc"] = {
            key: value for key, value in old.items()
            if key not in secrets_map and key not in tags and key not in OPTIONAL_KEYS
        }
    writes[root / ".deployment-settings-before.json"] = json.dumps(previous_settings)
    return writes


def atomic_write(path, content):
    fd, name = tempfile.mkstemp(prefix=".credentials-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(content if isinstance(content, bytes) else content.encode("utf-8"))
        os.chmod(name, 0o600)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def apply(root, environment):
    writes = plan(root, environment)  # 모든 파일·Credentials 검증 후에만 쓰기 시작
    originals = {path: path.read_bytes() if path.exists() else None for path in writes}
    updated = []
    try:
        for path, content in writes.items():
            atomic_write(path, content)
            updated.append(path)
    except OSError:
        for path in reversed(updated):
            if originals[path] is None:
                path.unlink(missing_ok=True)
            else:
                atomic_write(path, originals[path])
        raise ConfigurationError("환경 파일 저장 실패. 기존 내용을 복원했습니다.") from None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    try:
        apply(args.root, os.environ)
    except (ConfigurationError, OSError, UnicodeError) as error:
        # 값·환경변수·트레이스백은 출력하지 않는다.
        message = str(error) if isinstance(error, ConfigurationError) else "환경 파일 접근 실패"
        parser.exit(1, message + "\n")
    print("Credentials 일치 확인·실행용 환경 파일 생성 완료 (값 출력 없음)")


if __name__ == "__main__":
    main()
