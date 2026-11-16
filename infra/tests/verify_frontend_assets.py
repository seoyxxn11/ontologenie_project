"""로컬 nginx:1.30 두 개로 이전 assets·캐시·만료·프록시 fallback을 검증한다."""

import importlib.util
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


REPOSITORY = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("frontend_assets", REPOSITORY / "infra/deploy/frontend_assets.py")
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


def docker(*args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()


def put(root, name, content):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main():
    build = REPOSITORY / "build"
    build.mkdir(exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix="435-assets-", dir=build)).resolve()
    if not root.is_relative_to(build.resolve()):
        raise RuntimeError("검증 임시 경로가 작업 폴더 밖입니다.")
    network = "axcore435-" + uuid.uuid4().hex[:12]
    created, network_created = [], False
    try:
        archive, old, current = root / "archive", root / "old", root / "current"
        archive.mkdir()
        put(old, "old.js", 'export const version = "old";')
        put(old, "old.css", "body { color: purple; }")
        put(old, "shared.js", 'export const shared = true;')
        put(current, "assets/current.js", 'export const version = "current";')
        put(current, "assets/shared.js", 'export const shared = true;')
        put(current, "index.html", "CURRENT HTML")
        old_id, current_id = ("sha256:" + digit * 64 for digit in "ab")
        assets.retain(archive, {old_id: old}, 7, now=0)
        configuration = (REPOSITORY / "infra/nginx/conf.d/default.conf").read_text(encoding="utf-8")
        # TLS 값은 바꾸지 않는 작업이다. 인증서 없이 로컬 HTTP로 같은 라우팅을 검사한다.
        configuration = configuration.replace("listen 443 ssl;", "listen 8080;")
        configuration = configuration.replace("listen [::]:443 ssl;", "")
        configuration = re.sub(r"^\s*(?:http2 on;|ssl_certificate\s+.*|ssl_certificate_key\s+.*|ssl_protocols\s+.*)\n", "\n", configuration, flags=re.MULTILINE)
        put(root, "default.conf", configuration)
        docker("network", "create", network)
        network_created = True
        frontend = docker(
            "run", "-d", "--network", network, "--network-alias", "frontend",
            "--mount", f"type=bind,src={current.as_posix()},dst=/usr/share/nginx/html,readonly",
            "--mount", f"type=bind,src={(REPOSITORY / 'frontend/nginx.conf').as_posix()},dst=/etc/nginx/conf.d/default.conf,readonly",
            "nginx:1.30",
        )
        created.append(frontend)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        options = [
            "--network", network, "--add-host", "jenkins:127.0.0.1",
            "--mount", f"type=bind,src={(root / 'default.conf').as_posix()},dst=/etc/nginx/conf.d/default.conf,readonly",
            "--mount", f"type=bind,src={archive.as_posix()},dst=/srv/frontend-assets,readonly",
        ]
        docker("run", "--rm", *options, "nginx:1.30", "nginx", "-t")
        proxy = docker("run", "-d", "-p", f"127.0.0.1:{port}:8080", *options, "nginx:1.30")
        created.append(proxy)
        # OS 프록시 설정에 로컬 검증 요청을 보내지 않는다.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def get(path):
            try:
                response = opener.open(f"http://127.0.0.1:{port}{path}", timeout=5)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                return response.status, response.headers, response.read().decode()

        for attempt in range(30):
            try:
                if get("/")[0] == 200:
                    break
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.2)
        else:
            raise RuntimeError("로컬 nginx가 준비되지 않았습니다.")
        status, headers, body = get("/assets/old.js")
        assert status == 200 and '"old"' in body
        assert headers.get_content_type() == "application/javascript"
        cache = ", ".join(headers.get_all("Cache-Control", []))
        assert "immutable" in cache and "31536000" in cache
        for name in ["Strict-Transport-Security", "X-Content-Type-Options", "X-Frame-Options"]:
            assert len(headers.get_all(name, [])) == 1, name
        assert get("/assets/old.css")[1].get_content_type() == "text/css"
        assert get("/assets/current.js")[0] == 200  # 보관 전 현재 이미지 fallback
        assert get("/assets/missing.js")[0] == 404
        assert "immutable" not in ", ".join(get("/assets/missing.js")[1].get_all("Cache-Control", []))
        assert get("/")[1].get("Cache-Control") == "no-cache"
        assert get("/releases.json")[2] == "CURRENT HTML"
        assert get("/api/v1/internal")[0] == 403
        assert get("/v3/api-docs")[0] == 401
        assets.retain(archive, {old_id: old, current_id: current / "assets"}, 7, now=86400)
        assert get("/assets/old.js")[0] == 200
        assert get("/assets/current.js")[0] == 200
        assets.retain(archive, {current_id: current / "assets"}, 7, now=9 * 86400)
        assert get("/assets/old.js")[0] == 404
        assert get("/assets/shared.js")[0] == 200
        docker("stop", frontend)
        assert get("/assets/current.js")[0] == 200
        print("PASS: nginx syntax, old JS/CSS 200, MIME/cache/security headers, current-image fallback, missing 404, HTML no-cache, metadata isolation, access controls, expiration/shared assets, frontend-independent serving")
    finally:
        for container in reversed(created):
            subprocess.run(["docker", "rm", "-f", container], capture_output=True, timeout=30)
        if network_created:
            subprocess.run(["docker", "network", "rm", network], capture_output=True, timeout=30)
        # root는 위에서 workspace/build 안의 새 임시 디렉터리로 검증했다.
        shutil.rmtree(root)


if __name__ == "__main__":
    main()
