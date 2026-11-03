# Docker 상태·재시작 횟수

Alloy의 `discovery.docker`는 수집 대상을 찾고 cAdvisor는 자원 지표를 수집한다. 현재 구성에서 필요한 Docker `State.Status`와 `RestartCount`는 `collect.py`가 Docker CLI로 조회한다. Python 표준 라이브러리만 사용하며 새 컨테이너나 패키지는 추가하지 않는다.

`collect.py → monitoring/textfile/docker-state.prom → Alloy textfile → Prometheus → Grafana` 순서로 전달한다. `.prom` 파일에는 상태·재시작 횟수·식별 라벨만 저장한다. 환경변수나 전체 inspect 응답은 읽거나 저장하지 않는다. 실제 수집 파일과 임시 파일은 Git에서 제외한다.

## 지표 해석

- `axcore_docker_container_cpu_limit_cores`: Docker에 명시한 NanoCpus·CFS quota/period·cpuset 코어 수 중 가장 작은 값이다. 명시적 한도가 없으면 0이며 CPU 한도 대비 사용률 계산에서 제외한다. 부모 cgroup의 추가 제한이나 호스트 경합을 반영한 실효 한도는 아니다. Windows 로컬에서도 설정 한도 표는 확인할 수 있지만 CPU 사용률은 서버 cAdvisor 지표가 필요하다.
- `axcore_host_network_receive_bytes_total`, `axcore_host_network_transmit_bytes_total`: `--host-network` 옵션으로 Linux 호스트에서 직접 읽은 `/proc/net/dev`의 인터페이스별 누적 바이트다. 기존 컨테이너 namespace에서 실행하는 netdev 수집은 비활성화했다. 서버 서비스 파일에 해당 옵션을 지정했으며 네트워크 파일은 `host-network.prom`에 따로 기록한다. 네트워크 수집 실패 시 성공 지표를 0으로 바꾸고 기존 네트워크 값을 제거한다. Docker 조회 실패와는 별도로 갱신하며, 대시보드에서도 네트워크 성공·90초 신선도를 검사한다. 이 옵션은 컨테이너 안에서 실행하지 않는다. Windows 로컬에서 옵션을 생략하면 서버 네트워크 패널은 데이터 없음이다.

- `axcore_docker_container_restart_count`: Docker `RestartCount`의 현재 값이다. 게이지로 기록하며 컨테이너 재생성 시 초기화된다. 수동 재시작·배포를 포함한 모든 재기동의 누적 횟수가 아니므로 `rate()`로 계산하지 않는다.
- `axcore_docker_container_state`: 상태별 0/1 값이다. `== 1`인 라벨이 현재 상태다. 중지된 컨테이너도 조회하며 일회성 `docker compose run` 컨테이너는 제외한다. `running`은 애플리케이션 준비 완료나 건강 상태를 의미하지 않는다.
- `axcore_docker_state_collection_success`: 이번 수집 성공 1, 실패 0이다. Docker 접근 오류·시간 초과·조회 도중 컨테이너 삭제·알 수 없는 상태·대상 없음은 실패 처리한다. 다음 실행에서 다시 조회한다.
- `axcore_docker_state_collection_timestamp_seconds`: 성공한 스냅샷의 수집 시각이다. 대시보드는 마지막 수집 후 90초 이상이면 컨테이너 값을 숨기고 수집 지연을 표시한다. 실패 시에도 기존 상태 스냅샷을 제거한다. 파일이 없거나 Prometheus 시계열이 사라지면 데이터 없음으로 표시한다.

기존 `job="node"`, `instance` 라벨을 사용한다. Compose 프로젝트·서비스·이름·컨테이너 ID를 추가해 재생성 전후를 구분한다. 주기 수집 사이의 짧은 상태 변화는 놓칠 수 있다. Docker가 `RestartCount`를 초기화하는 경우에도 대시보드가 누적 이력을 복구하지 않는다.

## 로컬 검사 — 사용자가 실행

저장소 루트의 PowerShell에서:

```powershell
python -m unittest discover -s infra/monitoring/docker-state -p "test_*.py" -v
docker exec alloy alloy validate /etc/alloy
$info = docker inspect alloy | ConvertFrom-Json
$project = $info.Config.Labels.'com.docker.compose.project'
if (-not $project) { throw 'Alloy의 Compose 프로젝트 라벨이 없습니다.' }
python infra/monitoring/docker-state/collect.py --project $project
```

마지막 명령은 Docker 상태를 읽고 로컬 지표 파일을 생성한다. 파일을 Alloy가 읽도록 로컬 Alloy 재시작 또는 설정 재로드가 필요하다. 기존 `./monitoring:/etc/alloy:ro` 마운트를 사용한다. 별도 마운트·포트·권한 변경은 없다. 수집 후 호스트 scrape가 실행되면 Prometheus에서 `axcore_docker_container_state`와 `axcore_docker_container_restart_count`를 확인한다.

로컬 단발 실행 값도 90초 후에는 대시보드에서 숨긴다. 검증 중 반복 수집이 필요하면 별도 PowerShell에서 아래를 실행하며 `Ctrl+C`로 종료한다. 루프 종료 후 패널이 수집 지연으로 바뀌는지도 확인한다.

```powershell
while ($true) {
    python infra/monitoring/docker-state/collect.py --project $project
    Start-Sleep -Seconds 30
}
```

자동 검사 8개는 기존 6개에 CPU 한도 계산과 호스트 네트워크 파싱·잘못된 입력 검사를 추가했다. Docker나 서버를 실제로 제어하는 검사는 아니다. 실제 Docker 조회·Alloy 파싱·Prometheus 전달·Grafana 표시·수집 중단 시 지연 표시는 별도로 확인한다.

## 서버 적용 — 리뷰·병합 후 별도 승인 아래 실행

서버에는 `monitoring/docker-state/`와 `monitoring/textfile/` 디렉터리, 수정한 Alloy 설정 및 대시보드를 함께 반영한다. 지표 디렉터리는 Alloy가 이미 읽기 전용으로 마운트하는 `monitoring/` 아래에 있다. 기존 백업·비교·설정 검사 절차를 먼저 수행한다.

주기 실행은 동봉한 systemd 서비스·타이머를 사용한다. 경로는 `/home/ubuntu/axcore`, 실행 사용자는 `ubuntu`, Compose 프로젝트는 `axcore`로 지정했다. 실제 서버 라벨과 경로가 같은지 확인하고, `ubuntu`가 기존 Docker CLI를 실행할 수 있는지 점검한다. 자격 증명이나 그룹·소켓 권한은 변경하지 않는다.

```bash
cd ~/axcore
mkdir -p monitoring/textfile
python3 monitoring/docker-state/collect.py --project axcore --host-network
sudo systemd-analyze verify monitoring/docker-state/axcore-docker-state.service monitoring/docker-state/axcore-docker-state.timer
sudo install -m 0644 monitoring/docker-state/axcore-docker-state.service /etc/systemd/system/axcore-docker-state.service
sudo install -m 0644 monitoring/docker-state/axcore-docker-state.timer /etc/systemd/system/axcore-docker-state.timer
sudo systemctl daemon-reload
sudo systemctl enable --now axcore-docker-state.timer
```

서비스는 실행이 끝난 뒤 30초 후 다시 수집하며 25초 제한이 있다. Python과 Docker CLI가 이미 설치되어 있어야 한다. 서비스의 비활성 상태는 단발 실행 완료 시 정상이며, 타이머 활성 상태와 최근 실행 결과를 확인한다. 테스트를 위해 운영 컨테이너를 강제 실패시키지 않는다. 실제 Docker inspect 값과 패널 값을 비교하고 안전한 임시 컨테이너로 중지·재시작 상황을 확인한다.

적용 취소 시 `sudo systemctl disable --now axcore-docker-state.timer`로 주기 실행을 멈추고, 실행 중인 서비스가 끝났는지 확인한다. 중단만으로 지표 파일이 삭제되지는 않지만 대시보드의 90초 제한으로 오래된 상태는 숨겨진다. Alloy 설정과 JSON은 승인된 백업으로 복구한다.

## 근거

- [Alloy Docker 검색](https://grafana.com/docs/alloy/latest/reference/components/discovery/discovery.docker/)
- [Alloy cAdvisor](https://grafana.com/docs/alloy/latest/reference/components/prometheus/prometheus.exporter.cadvisor/)
- [Alloy Unix exporter의 textfile 설정](https://grafana.com/docs/alloy/latest/reference/components/prometheus/prometheus.exporter.unix/#textfile)

## 검증 기록

2026-11-03 사용자 실행 결과:

- Python 자동 검사 6개 통과, Alloy 설정 검사 오류 없음.
- 실제 로컬 Docker 조회와 파일 생성 성공. Alloy 재시작 후 반복 수집·Prometheus 전달·Grafana 표시 확인.
- prometheus·grafana·alloy·loki 상태가 running으로 표시됨. 재시작 횟수를 Docker inspect 원본과 비교해 일치 확인.
- 반복 수집 중단 2분 후 오래된 상태·재시작 값 숨김 및 수집 지연/실패 표시 확인.
- 반복 수집 재개 후 상태 목록과 정상 표시 복구 확인.

자동 검사는 가짜 입력으로 실패·재생성 경로를 검사했다. 실제 컨테이너 중지·자동 재시작 상황과 Linux 서버 systemd 주기 실행은 아직 검증하지 않았다. 서버 적용 전 서비스 파일 문법·실행 권한을 검사하고 적용 후 실제 Docker 값·주기 갱신·Alloy 전달을 확인한다.

이후 리뷰에서 CPU 설정 한도와 호스트 네트워크 수집을 추가했다. 사용자 실행으로 자동 검사 8개·Alloy 설정 검사·git diff 공백 검사를 다시 통과했다. CPU 설정 한도 표와 수집 정상 표시를 로컬에서 확인했다. CPU 사용률 쿼리의 `group_left` 구문 오류를 `group_left()`로 수정한 뒤 개별 쿼리 성공과 Grafana 오류 해소를 확인했다. 이어 대시보드의 모든 Prometheus 쿼리에서 `$__rate_interval`을 `5m`으로 치환해 로컬 Prometheus API에 요청했으며 전부 성공했다. 이는 문법·현재 로컬 실행 검사이며, 빈 결과를 서버 데이터 정확성 검증으로 취급하지 않는다.

서버에서는 CPU 제한을 둔 batch의 1코어 설정과 한도 대비 사용률을 확인하고, 호스트 `/proc/net/dev`의 바이트·인터페이스가 패널 지표와 일치하는지 비교한다. Linux 호스트 네트워크 파일 수집과 systemd 서비스·타이머는 아직 실제 서버에서 실행하지 않았다.
