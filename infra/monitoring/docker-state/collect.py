"""Docker의 필요한 필드만 조회해 Alloy textfile 지표를 원자적으로 갱신한다."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


STATES = ("created", "running", "paused", "restarting", "removing", "exited", "dead")
# 전체 inspect 응답(환경변수 포함)을 읽거나 파일에 남기지 않는다.
INSPECT_FORMAT = (
    '{"id":{{json .Id}},"name":{{json .Name}},'
    '"project":{{json (index .Config.Labels "com.docker.compose.project")}},'
    '"service":{{json (index .Config.Labels "com.docker.compose.service")}},'
    '"oneoff":{{json (index .Config.Labels "com.docker.compose.oneoff")}},'
    '"status":{{json .State.Status}},"restarts":{{json .RestartCount}},'
    '"nano_cpus":{{json .HostConfig.NanoCpus}},'
    '"cpu_quota":{{json .HostConfig.CpuQuota}},'
    '"cpu_period":{{json .HostConfig.CpuPeriod}},'
    '"cpuset":{{json .HostConfig.CpusetCpus}}}'
)


def docker(*args: str) -> str:
    result = subprocess.run(
        ["docker", *args], capture_output=True, text=True, encoding="utf-8",
        check=True, timeout=10,
    )
    return result.stdout


def label(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def collect(project: str) -> list[dict]:
    ids = docker("ps", "--all", "--quiet", "--no-trunc", "--filter",
                 f"label=com.docker.compose.project={project}").split()
    if not ids:
        raise ValueError("대상 Compose 컨테이너가 없습니다")
    # 한 번에 조회하며 중간에 삭제된 컨테이너가 있으면 전체 수집을 실패 처리한다.
    rows = [json.loads(line) for line in
            docker("inspect", "--format", INSPECT_FORMAT, *ids).splitlines() if line]
    return rows


def cpu_limit(row: dict) -> float:
    """명시적으로 설정한 CPU 제약 중 가장 작은 코어 수. 무제한은 0."""
    limits = []
    if row["nano_cpus"] > 0:
        limits.append(row["nano_cpus"] / 1_000_000_000)
    if row["cpu_quota"] > 0:
        limits.append(row["cpu_quota"] / (row["cpu_period"] or 100_000))
    cpus = set()
    for entry in filter(None, row["cpuset"].split(",")):
        bounds = entry.split("-")
        first, last = int(bounds[0]), int(bounds[-1])
        if len(bounds) > 2 or first < 0 or last < first or last > 65535:
            raise ValueError("CPU 집합 범위가 잘못되었습니다")
        cpus.update(range(first, last + 1))
    if cpus:
        limits.append(len(cpus))
    return min(limits) if limits else 0


def render_network(content: str, now: float) -> str:
    """호스트 프로세스가 읽은 /proc/net/dev를 전용 지표로 변환한다."""
    lines = [
        "# TYPE axcore_host_network_receive_bytes_total counter",
        "# TYPE axcore_host_network_transmit_bytes_total counter",
        "# TYPE axcore_host_network_collection_success gauge",
        "axcore_host_network_collection_success 1",
        "# TYPE axcore_host_network_collection_timestamp_seconds gauge",
        f"axcore_host_network_collection_timestamp_seconds {now:.3f}",
    ]
    count = 0
    for line in content.splitlines():
        if ":" not in line:
            continue
        device, values = line.rsplit(":", 1)
        fields = values.split()
        if len(fields) != 16:
            raise ValueError("네트워크 지표 형식이 잘못되었습니다")
        receive, transmit = int(fields[0]), int(fields[8])
        if receive < 0 or transmit < 0:
            raise ValueError("네트워크 바이트가 음수입니다")
        device = label(device.strip())
        lines.extend([
            f'axcore_host_network_receive_bytes_total{{device="{device}"}} {receive}',
            f'axcore_host_network_transmit_bytes_total{{device="{device}"}} {transmit}',
        ])
        count += 1
    if count == 0:
        raise ValueError("네트워크 인터페이스가 없습니다")
    return "\n".join(lines) + "\n"


def render(rows: list[dict], project: str, now: float) -> str:
    lines = [
        "# HELP axcore_docker_state_collection_success Whether Docker inspection succeeded.",
        "# TYPE axcore_docker_state_collection_success gauge",
        "axcore_docker_state_collection_success 1",
        "# HELP axcore_docker_state_collection_timestamp_seconds Last inspection time.",
        "# TYPE axcore_docker_state_collection_timestamp_seconds gauge",
        f"axcore_docker_state_collection_timestamp_seconds {now:.3f}",
        "# HELP axcore_docker_container_restart_count Docker RestartCount; resets on recreation.",
        "# TYPE axcore_docker_container_restart_count gauge",
        "# HELP axcore_docker_container_state Current Docker state; one active state per container.",
        "# TYPE axcore_docker_container_state gauge",
        "# HELP axcore_docker_container_cpu_limit_cores Explicit Docker CPU cap; zero means unlimited.",
        "# TYPE axcore_docker_container_cpu_limit_cores gauge",
    ]
    count = 0
    for row in rows:
        if row["project"] != project or str(row.get("oneoff", "")).lower() == "true":
            continue
        if not row.get("service"):
            continue
        if row["status"] not in STATES:
            raise ValueError("알 수 없는 컨테이너 상태입니다")
        restarts = row["restarts"]
        if type(restarts) is not int or restarts < 0:
            raise ValueError("재시작 횟수가 잘못되었습니다")
        labels = ",".join(f'{key}="{label(value)}"' for key, value in (
            ("project", project), ("service", row["service"]),
            ("name", row["name"].lstrip("/")), ("container_id", row["id"]),
        ))
        lines.append(f"axcore_docker_container_restart_count{{{labels}}} {restarts}")
        lines.append(f"axcore_docker_container_cpu_limit_cores{{{labels}}} {cpu_limit(row)}")
        for state in STATES:
            lines.append(f'axcore_docker_container_state{{{labels},state="{state}"}} '
                         f'{int(row["status"] == state)}')
        count += 1
    if count == 0:
        raise ValueError("수집 대상이 없습니다")
    return "\n".join(lines) + "\n"


def write_atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # .prom 확장자가 아닌 임시 파일에 쓴 뒤 교체해 미완성 내용을 노출하지 않는다.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", newline="\n", dir=path.parent,
            prefix=".docker-state-", suffix=".tmp", delete=False,
        ) as stream:
            temporary = Path(stream.name)
            stream.write(content)
        temporary.chmod(0o644)
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--host-network", action="store_true",
                        help="Linux 호스트에서만 /proc/net/dev를 읽습니다")
    parser.add_argument("--output", type=Path,
                        default=Path(__file__).resolve().parent.parent / "textfile/docker-state.prom")
    args = parser.parse_args()
    network_failed = False
    if args.host_network:
        network_output = args.output.with_name("host-network.prom")
        try:
            if sys.platform != "linux":
                raise ValueError("Linux 호스트 전용 옵션입니다")
            content = Path("/proc/net/dev").read_text(encoding="utf-8")
            write_atomic(network_output, render_network(content, time.time()))
        except (OSError, ValueError):
            write_atomic(network_output, "# TYPE axcore_host_network_collection_success gauge\n"
                         "axcore_host_network_collection_success 0\n")
            print("호스트 네트워크 수집 실패", file=sys.stderr)
            network_failed = True
    try:
        output = render(collect(args.project), args.project, time.time())
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError):
        # 이전 상태를 남기지 않는다. 비밀값 노출을 막기 위해 Docker 오류 원문은 출력하지 않는다.
        write_atomic(args.output, "# TYPE axcore_docker_state_collection_success gauge\n"
                     "axcore_docker_state_collection_success 0\n")
        print("Docker 상태 수집 실패: Docker 접근 권한·프로젝트·컨테이너 변경을 확인하세요.",
              file=sys.stderr)
        return 1
    write_atomic(args.output, output)
    print("Docker 상태·재시작 횟수 지표 파일을 갱신했습니다.")
    return int(network_failed)


if __name__ == "__main__":
    sys.exit(main())
