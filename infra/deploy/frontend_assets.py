"""프론트 이미지의 공개 assets만 누적하고 보관 기간이 지난 관리 파일만 정리한다."""

import argparse
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import time


STATE_FILE = "releases.json"
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}\Z")


def inside(root: Path, name: str) -> Path:
    if not isinstance(name, str) or not name or "\\" in name:
        raise ValueError("잘못된 assets 경로입니다.")
    relative = PurePosixPath(name)
    if relative.is_absolute() or relative.as_posix() != name or any(
        part.startswith(".") for part in relative.parts
    ):
        raise ValueError("숨김 파일 또는 상위 경로를 assets에 넣을 수 없습니다.")
    target = root
    for part in relative.parts:
        target = target / part
        if target.is_symlink():
            raise ValueError("assets의 심볼릭 링크는 허용하지 않습니다.")
    if not target.resolve().is_relative_to(root.resolve()):
        raise ValueError("assets 경로 밖에는 접근할 수 없습니다.")
    return target


def load_releases(root: Path) -> dict:
    state = inside(root, STATE_FILE)
    if not state.exists():
        return {}
    value = json.loads(state.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("version") != 1 or not isinstance(value.get("releases"), dict):
        raise ValueError("assets 보관 목록의 형식을 확인해야 합니다.")
    for image, release in value["releases"].items():
        if not IMAGE_ID.fullmatch(image) or not isinstance(release, dict):
            raise ValueError("assets 이미지 식별자가 올바르지 않습니다.")
        seen, files = release.get("last_seen"), release.get("files")
        if type(seen) is not int or seen < 0 or not isinstance(files, list) or not files:
            raise ValueError("assets 보관 시각·파일 목록이 올바르지 않습니다.")
        for name in files:
            inside(inside(root, "assets"), name)
    return value["releases"]


def digest(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def retain(root: Path, snapshots: dict[str, Path], days: int, now: int | None = None) -> dict:
    if type(days) is not int or days < 1:
        raise ValueError("보관 일수는 1 이상의 정수여야 합니다.")
    if root.is_symlink() or not root.is_dir() or not snapshots:
        raise ValueError("실제 보관 디렉터리와 이미지 snapshot이 필요합니다.")
    now = int(time.time()) if now is None else now
    previous = load_releases(root)
    assets = inside(root, "assets")
    sources, releases, hashes = {}, dict(previous), {}
    for image, source in snapshots.items():
        if not IMAGE_ID.fullmatch(image) or source.is_symlink() or not source.is_dir():
            raise ValueError("이미지 snapshot을 확인해야 합니다.")
        files = []
        for path in sorted(source.rglob("*")):
            name = path.relative_to(source).as_posix()
            inside(source, name)
            if path.is_dir():
                continue
            if not path.is_file():
                raise ValueError("일반 assets 파일만 보관할 수 있습니다.")
            destination = inside(assets, name)
            checksum = digest(path)
            if name in hashes and hashes[name] != checksum:
                raise ValueError("같은 assets 이름에 서로 다른 내용이 있습니다.")
            if destination.exists() and (not destination.is_file() or digest(destination) != checksum):
                raise ValueError("기존 assets 내용을 덮어쓸 수 없습니다.")
            hashes[name], sources[name] = checksum, path
            files.append(name)
        if not files:
            raise ValueError("비어 있는 assets snapshot으로 배포할 수 없습니다.")
        releases[image] = {"last_seen": now, "files": files}

    # 현재 컨테이너와 새 이미지의 snapshot은 지금 시각으로 갱신하므로 항상 남는다.
    kept = {image: release for image, release in releases.items() if release["last_seen"] >= now - days * 86400}
    live = {name for release in kept.values() for name in release["files"]}
    expired = {name for release in previous.values() for name in release["files"]} - live
    assets.mkdir(mode=0o755, exist_ok=True)
    for name, source in sources.items():
        destination = inside(assets, name)
        if destination.exists():
            continue
        destination.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=root, prefix=".asset-", delete=False) as handle:
            temporary = Path(handle.name)
        try:
            shutil.copyfile(source, temporary)
            temporary.chmod(0o644)
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    for name in expired:
        # 목록에 없던 수동 파일이나 보관 디렉터리 전체는 삭제하지 않는다.
        inside(assets, name).unlink(missing_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=root, prefix=".state-", delete=False) as handle:
        temporary = Path(handle.name)
        json.dump({"version": 1, "releases": kept}, handle, sort_keys=True)
    try:
        temporary.replace(inside(root, STATE_FILE))
    finally:
        temporary.unlink(missing_ok=True)
    return {"releases": len(kept), "assets": len(live), "expired_assets": len(expired)}


@contextmanager
def archive_lock(root: Path):
    # Linux 컨테이너에서 실행하며, 프로세스가 끝나면 OS가 잠금도 해제한다.
    import fcntl

    if root.is_symlink() or not root.is_dir():
        raise ValueError("실제 보관 디렉터리가 필요합니다.")
    lock = inside(root, "archive.lock")
    with lock.open("a") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--days", type=int, default=7)
    parser.add_argument("--snapshot", action="append", nargs=2, required=True)
    args = parser.parse_args()
    snapshots = {image: Path(source) for image, source in args.snapshot}
    with archive_lock(args.root):
        print(json.dumps(retain(args.root, snapshots, args.days)))


if __name__ == "__main__":
    main()
