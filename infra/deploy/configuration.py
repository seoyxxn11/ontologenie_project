"""허용한 운영 설정만 stage·비교·백업·복원한다. Docker 실행은 셸에서 담당한다."""

import argparse
import ast
import json
from pathlib import Path
import shutil
import sys

from prepare_credentials import CDC_SECRETS, MAIN_SECRETS, TAGS, ConfigurationError, atomic_write, read_env, render_env


EXACT = {
    "compose.yaml", "cdc/compose.yaml", "cdc/console.yaml",
    "monitoring/config.alloy", "monitoring/container-metrics.alloy",
    "monitoring/prometheus.yml", "monitoring/loki.yaml",
    "monitoring/docker-state/collect.py",
    "scripts/pg-backup.sh", "scripts/renew-cert.sh",
}
MANUAL = {
    "monitoring/docker-state/axcore-docker-state.service",
    "monitoring/docker-state/axcore-docker-state.timer",
}
SERVICES = {"nginx", "backend", "batch", "ai", "frontend", "prometheus", "alloy", "grafana", "loki"}
CDC_SERVICES = {"kafka", "kafka-connect", "console"}


def allowed(name):
    path = Path(name)
    return name in EXACT or (
        path.suffix in {".yaml", ".yml", ".json", ".conf"}
        and (name.startswith("nginx/conf.d/") or name.startswith("monitoring/grafana/"))
    )


def inside(root, name):
    root = root.resolve()
    path = root / name
    if not path.resolve().is_relative_to(root) or path.is_symlink():
        raise ValueError("관리 경로 밖 또는 심볼릭 링크 설정은 반영하지 않습니다.")
    return path


def prepare(source, root, transaction):
    if transaction.exists():
        raise ValueError("동일 배포의 설정 트랜잭션이 이미 있습니다.")
    for name in MANUAL:
        src, dst = inside(source, name), inside(root, name)
        if src.is_file() and (not dst.is_file() or src.read_bytes() != dst.read_bytes()):
            raise ValueError("systemd 단위 파일 변경은 호스트에서 별도 적용해야 합니다.")
    files = sorted(
        path.relative_to(source).as_posix() for path in source.rglob("*")
        if path.is_file() and allowed(path.relative_to(source).as_posix())
    )
    for required in ("compose.yaml", "cdc/compose.yaml"):
        if required not in files:
            raise ValueError("필수 Compose 파일이 없습니다.")
    previous_manifest = root / ".managed-config.json"
    previous = json.loads(previous_manifest.read_text()) if previous_manifest.exists() else []
    for name in previous:
        if not allowed(name):
            raise ValueError("기존 관리 목록의 경로를 확인해야 합니다.")
    deleted = sorted(set(previous) - set(files))
    changed = []
    for name in sorted(set(files) | set(deleted)):
        src, dst = inside(source, name), inside(root, name)
        if name in deleted or not dst.is_file() or src.read_bytes() != dst.read_bytes():
            changed.append(name)
        if src.suffix == ".json" and name not in deleted:
            json.loads(src.read_text(encoding="utf-8"))
        if src.suffix == ".py" and name not in deleted:
            ast.parse(src.read_text(encoding="utf-8"))
    transaction.mkdir(parents=True, mode=0o700)
    previous_settings = root / ".deployment-settings-before.json"
    if previous_settings.exists():
        shutil.copyfile(previous_settings, transaction / "settings-before.json")
    for name in files:
        target = inside(transaction / "stage", name)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(inside(source, name), target)
    for name in changed:
        old = inside(root, name)
        if old.is_file():
            backup = inside(transaction / "backup", name)
            backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(old, backup)
    atomic_write(transaction / "plan.json", json.dumps({
        "files": files, "changed": changed, "deleted": deleted,
        "services": [], "nginx_reload": False,
    }))


def read_plan(transaction):
    return json.loads((transaction / "plan.json").read_text())


def old_env(root, transaction):
    path = transaction / "settings-before.json"
    previous = json.loads(path.read_text()) if path.exists() else {}
    for project, directory, secrets_map, tags in (
        ("main", root, MAIN_SECRETS, TAGS), ("cdc", root / "cdc", CDC_SECRETS, set())
    ):
        current = read_env(directory / ".env")
        values = {**previous[project], **{key: value for key, value in current.items() if key in secrets_map or key in tags}} if project in previous else current
        atomic_write(transaction / f"{project}-old.env", render_env(values))


def compare(transaction):
    plan = read_plan(transaction)
    actions = set()
    for project in ("main", "cdc"):
        old = json.loads((transaction / f"{project}-old.json").read_text())
        new = json.loads((transaction / f"{project}-new.json").read_text())
        old_services, new_services = old.get("services", {}), new.get("services", {})
        # 데이터 볼륨·네트워크·프로젝트 변경이나 서비스 삭제는 자동 적용하지 않는다.
        if set(old_services) != set(new_services):
            raise ValueError("서비스 추가·삭제는 별도 전환 절차가 필요합니다.")
        if {key: value for key, value in old.items() if key != "services"} != {
            key: value for key, value in new.items() if key != "services"
        }:
            raise ValueError("Compose 프로젝트·네트워크·볼륨 변경은 수동 검토가 필요합니다.")
        for service, spec in new_services.items():
            if spec != old_services[service]:
                permitted = CDC_SERVICES if project == "cdc" else SERVICES
                if service not in permitted:
                    raise ValueError("Jenkins·DB·도구 서비스 변경은 자동 재생성하지 않습니다.")
                actions.add(f"{project}:{service}")
    reload_nginx = False
    for name in plan["changed"]:
        if name.startswith("nginx/conf.d/"):
            reload_nginx = True
        elif name.startswith("monitoring/grafana/"):
            actions.add("main:grafana")
        elif name in {"monitoring/config.alloy", "monitoring/container-metrics.alloy"}:
            actions.add("main:alloy")
        elif name == "monitoring/prometheus.yml":
            actions.add("main:prometheus")
        elif name == "monitoring/loki.yaml":
            actions.add("main:loki")
        elif name == "cdc/console.yaml":
            actions.add("cdc:console")
    if actions & {"main:backend", "main:batch"}:
        actions.update({"main:backend", "main:batch"})
    plan["services"] = sorted(actions)
    plan["nginx_reload"] = reload_nginx
    atomic_write(transaction / "plan.json", json.dumps(plan))
    atomic_write(transaction / "services.txt", "\n".join(plan["services"]) + "\n")
    print("설정 검사·변경 서비스 판정 완료 (비밀값 출력 없음)")


def copy_config(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    # 디렉터리 마운트는 교체된 파일을 읽고, 단일 파일 bind mount 서비스는 재생성한다.
    atomic_write(target, source.read_text(encoding="utf-8"))
    # nginx worker(uid nginx) 등 컨테이너 사용자가 읽을 수 있어야 한다. 관리 설정에는 비밀값이 없다.
    target.chmod(0o755 if target.suffix == ".sh" else 0o644)


def promote(root, transaction):
    plan = read_plan(transaction)
    for name in plan["changed"]:
        target = inside(root, name)
        if name in plan["deleted"]:
            target.unlink(missing_ok=True)
        else:
            copy_config(inside(transaction / "stage", name), target)


def restore(root, transaction):
    for name in read_plan(transaction)["changed"]:
        target, backup = inside(root, name), inside(transaction / "backup", name)
        if backup.is_file():
            copy_config(backup, target)
        else:
            target.unlink(missing_ok=True)
    restore_settings(root, transaction)
    print("이전 설정 파일 복원 완료. 서비스 복구 검사는 별도로 수행합니다.")


def restore_settings(root, transaction):
    path = transaction / "settings-before.json"
    if not path.exists():
        path = root / ".deployment-settings-before.json"
    if path.exists():
        previous = json.loads(path.read_text())
        for project, directory, secrets_map, tags in (
            ("main", root, MAIN_SECRETS, TAGS), ("cdc", root / "cdc", CDC_SECRETS, set())
        ):
            if project not in previous:
                continue
            current = read_env(directory / ".env")
            values = {**previous[project], **{key: value for key, value in current.items() if key in secrets_map or key in tags}}
            atomic_write(directory / ".env", render_env(values))
            atomic_write(directory / ".env.config", render_env(previous[project], generated=False))
        (root / ".deployment-settings-before.json").unlink(missing_ok=True)


def finish(root, transaction):
    atomic_write(root / ".managed-config.json", json.dumps(read_plan(transaction)["files"]))
    (root / ".deployment-settings-before.json").unlink(missing_ok=True)
    print("관리 대상 설정 반영·상태 확인 완료")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["prepare", "old-env", "compare", "promote", "restore", "restore-settings", "finish"])
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--transaction", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.mode == "prepare":
            prepare(args.source, args.root, args.transaction)
        elif args.mode == "compare":
            compare(args.transaction)
        elif args.mode == "old-env":
            old_env(args.root, args.transaction)
        elif args.mode == "restore-settings":
            restore_settings(args.root, args.transaction)
        else:
            globals()[args.mode](args.root, args.transaction)
    except (OSError, ValueError, SyntaxError, ConfigurationError):
        # JSON/설정 오류 메시지는 비밀값 일부를 포함할 수 있어 원문을 출력하지 않는다.
        print("설정 자동 반영 중단: 경로·형식·자동 적용 제외 변경을 확인하세요. 값은 출력하지 않았습니다.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
