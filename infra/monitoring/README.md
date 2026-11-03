# 지표 수집

Prometheus는 지표를 저장하고 Alloy는 30초마다 수집·전송한다. Grafana는 저장된 지표를 조회한다. Prometheus·Alloy는 호스트 포트를 열지 않고, Grafana는 `127.0.0.1:13000`에만 연결한다. 메모리 상한(Prometheus 512MiB, Alloy 256MiB, Grafana 512MiB)과 보관 설정(7일, TSDB 2GB)은 초기값이며 전체 수집 구성 후 실측해 조정한다. TSDB 보관 크기는 WAL 등을 포함한 전체 디스크 사용량의 엄격한 상한이 아니다.

## 파일과 실행 범위

- `prometheus.yml`: Prometheus 자체 지표 수집.
- `config.alloy`: Alloy 자체 지표와 호스트 CPU·메모리·디스크·시스템 부하, Docker 서비스 로그 수집.
- `config.alloy`의 `backend` 수집: 내부 `backend:8080/actuator/prometheus`에서 JVM·HTTP 등 앱 지표를 수집한다. `job="backend"`, `service="backend"`로 전송하며 cAdvisor의 컨테이너 자원 지표와 구분한다.
- `container-metrics.alloy`: Linux 서버의 Docker 컨테이너 CPU·메모리 수집. `job="cadvisor"`, `instance="axcore-main"`로 전송한다. Compose 서비스·프로젝트 라벨만 허용하고 환경변수는 지표 라벨로 수집하지 않는다.
- `compose.local.yaml`: 노트북용 네트워크·경로·권한 재정의. `config.alloy`만 실행하며 cAdvisor 실제 수집은 실행하지 않는다. Compose 2.24.4 이상이 필요하다.

서버에서는 Alloy가 `/etc/alloy` 디렉터리의 `.alloy` 파일을 함께 읽는다. 서버 반영 시 `compose.yaml`뿐 아니라 위 두 `.alloy` 파일과 `prometheus.yml`을 `~/axcore/monitoring/`에 복사해야 한다. 저장소 머지만으로 서버 설정이 복사되지는 않는다.

## Loki 로그 저장소

`grafana/loki:3.7.9`를 단일 프로세스·단일 테넌트로 실행한다. `loki.yaml`은 TSDB v13·filesystem 저장소를 사용하며 WAL·인덱스·로그 청크·Compactor 작업 파일을 전용 `axcore-loki-data` 볼륨의 `/loki` 아래에 저장한다. 외부 포트는 열지 않고 `axcore-net` 내부의 `loki:3100`으로 접근한다. `auth_enabled: false`는 테넌트 인증 없이 내부에서 접근하는 설정이므로 외부 nginx 경로를 추가하지 않는다.

메모리 상한 512MiB, 조회 동시성 2, 결과 캐시 32MiB, 삭제 worker 2는 초기 설정이다. 실제 로그 수집·조회·재시작 부하를 측정한 뒤 조정한다. 공식 Loki 이미지는 셸·wget이 없는 distroless이므로 컨테이너 내부 명령을 쓰는 Compose 헬스체크는 추가하지 않는다. 기동 확인 시 다른 내부망 컨테이너에서 `http://loki:3100/ready`를 조회하고 로그·재시작·OOM 여부를 함께 확인한다. `Up`만으로 준비 완료로 판단하지 않는다.

보존 기간은 `168h`(7일)이고 Compactor retention을 활성화한다. 인덱스 주기는 24시간, 삭제 지연은 2시간이다. 7일이 되는 순간 모든 디스크 파일이 즉시 삭제되는 것은 아니며, 보존 정책은 디스크 사용량의 상한도 아니다. 이미 삭제된 로그는 보존 기간을 늘려도 복구되지 않는다. 기동·쓰기·조회·재시작 후 보존 검증과 실제 오래된 로그 정리 확인은 별도 검증 항목이며 설정 작성만으로 완료 처리하지 않는다.

### Docker 로그 수집과 Grafana 조회

`config.alloy`의 `discovery.docker` → `discovery.relabel` → `loki.source.docker` → `loki.write`로 Docker stdout·stderr 로그를 내부 `http://loki:3100/loki/api/v1/push`에 전송한다. Docker 소켓으로 읽으므로 호스트의 로그 파일 경로를 별도로 마운트하지 않는다. 읽은 위치는 기존 `alloy-data`의 storage path 아래에 보관한다. 재기동 시 위치 추적을 위해 이 볼륨을 유지한다.

`DOCKER_LOG_NETWORK`는 서버에서 `axcore-net`, 노트북에서 `axcore-monitoring-check-net`이다. 해당 네트워크에 연결되고 Compose 서비스가 `backend`, `ai`, `frontend`, `batch`, `nginx`, `alloy`, `prometheus`, `loki`, `grafana` 중 하나인 컨테이너만 수집한다. DB·Jenkins·다른 네트워크의 컨테이너는 제외한다. 서비스가 추가되면 허용 목록을 검토한다. 라벨은 `job="docker"`, `service`(Compose 서비스명), `instance`(기존 호스트 식별자)로 조회한다. 컨테이너 환경변수를 로그 라벨로 복사하지 않는다. 앱이 stdout·stderr에 남기는 비밀값까지 자동으로 제거하는 설정은 아니므로 앱의 로그 보안 규칙을 함께 지킨다.

노트북 설정은 기존 `config.alloy` 실행을 유지하면서 Docker 소켓을 추가한다. cAdvisor는 실행하지 않는다. 소켓의 `read_only`는 Docker API 쓰기 요청을 차단하는 접근 제어가 아니므로 호스트 Docker 접근 권한이 추가된다는 점을 검토한다.

Grafana는 `grafana/provisioning/datasources/loki.yaml`을 읽기 전용으로 연결해 내부 Loki를 자동 등록한다. UID는 `axcore-loki`이며 기본 데이터 소스는 기존 Prometheus다. [로컬 Grafana](http://127.0.0.1:13000)의 Explore에서 Loki를 선택하고 최근 15분 범위에 아래 쿼리를 실행한다.

```logql
{job="docker"}
```

서비스별 조회 예: `{job="docker", service="backend"}`. 현재 노트북에서 backend를 실행하지 않았다면 해당 서비스의 로그는 없으며, `{job="docker", service="alloy"}`로 수집 경로를 확인할 수 있다.

2026-11-02 노트북 검증: 전체 Alloy 파일 `alloy validate /etc/alloy`, 서버·로컬 Compose `config -q` 통과. 로컬 Alloy·Grafana만 재생성한 뒤 자동 수집 로그를 Loki에서 조회했고, Grafana 데이터 소스 health API의 `status="OK"`와 데이터 소스 프록시를 통한 로그 조회 `status="success"`를 확인했다. 두 컨테이너 모두 `running`, `OOMKilled=false`, `RestartCount=0`이었다. 브라우저 Explore에서 전체 로그와 `service="alloy"` 필터 조회를 확인했고, `docker logs` 원본의 시각·메시지와 대조했다. Loki 설정 검사 `-verify-config=true`도 통과했다. 실제 앱 서비스 로그, Linux 서버 반영과 장기 보존·부하 검증은 별도 잔여 항목이다.

### 서버 반영 시 확인

저장소 변경은 서버에 자동 복사되지 않는다. 승인된 서버 반영 시 `compose.yaml`, `monitoring/config.alloy`, `monitoring/loki.yaml`, `monitoring/grafana/provisioning/datasources/loki.yaml`을 함께 반영한다. 기존 `container-metrics.alloy` 등 다른 설정은 유지한다. 서버 Alloy의 `DOCKER_LOG_NETWORK=axcore-net`과 Docker 소켓 마운트를 확인하고, Alloy validate와 Compose config 검사 후 Loki·Alloy·Grafana만 적용한다. 노트북용 `compose.local.yaml`은 서버에서 사용하지 않는다.

반영 후 Loki `/ready`, Grafana Loki 데이터 소스 연결 상태, Explore의 실제 `backend`·`ai` 등 서비스 로그를 확인한다. 로그 수집 성공과 기존 Prometheus 호스트·컨테이너·backend 지표 유지, 재시작·OOM 여부를 함께 확인한다. 로그 조회 캡처와 산출물 링크, 검토 승인까지 확보한 뒤 66의 완료 기준을 판단한다. 이 저장소 수정과 로컬 검증만으로 서버 검증 완료를 의미하지 않는다.

## Backend 애플리케이션 지표

backend는 Spring Boot가 관리하는 버전의 `micrometer-registry-prometheus` 런타임 의존성을 추가하고, `api` 프로필에서 `health,prometheus`만 웹에 노출한다. 수집용 정확한 `GET /actuator/prometheus`는 로그인 없이 허용하지만 다른 관리 경로·업무 API 인증·CSRF 규칙은 그대로 유지한다. 새 관리 포트나 nginx 프록시는 추가하지 않는다. 현재 구성의 접근 경계는 nginx와 호스트 loopback 바인딩이며, Docker 내부망 또는 SSH 터널 등으로 backend에 직접 접근 가능한 사용자는 지표를 조회할 수 있다.

backend 담당자는 MR에서 익명 수집 접근 범위와 인증 회귀 테스트를 리뷰한다. 업무 API 계약·DB 변경은 없으며, 관리 경로의 접근 정책은 저장소 개발 가이드와 함께 Notion 사본에도 반영해야 한다.

반영 순서는 backend MR 검사·리뷰 후 머지 및 자동 배포 → 내부 지표 응답 확인 → 서버 Alloy 설정 백업·복사 및 검증 → Alloy 재시작 → `up{job="backend"}=1`과 JVM·HTTP 지표 확인이다. Alloy를 먼저 반영하면 backend 배포 전에는 404·401 등으로 `up=0`이 될 수 있다. 노트북에서 backend를 띄우지 않은 경우에도 해당 대상은 `up=0`이 예상되며 호스트 지표 검증과 구분한다.

`ApiProfileTests`에 익명 GET의 JVM 지표 응답, 다른 관리 경로의 인증 요구, POST의 CSRF 요구 검증을 추가했다. 테스트는 작성만으로 통과 처리하지 않으며 Docker Desktop 실행 상태에서 backend 포맷과 전체 빌드·테스트를 실행해야 한다. 서버 적용 후에는 HTTP 요청을 발생시켜 `http_server_requests_seconds_count`의 기록과 외부 HTTPS `/actuator/prometheus`가 backend 지표를 반환하지 않는지도 확인한다.

## Grafana

Grafana 이미지는 `grafana/grafana:13.3.0`으로 고정한다. `grafana-data` 볼륨에 계정·대시보드 등을 저장하고 `grafana/provisioning/datasources/prometheus.yaml`로 내부 Prometheus를 자동 등록한다. 기본 provisioning 하위 폴더가 가려지지 않도록 이 파일만 읽기 전용으로 연결한다. 데이터 소스 UID는 `axcore-prometheus`다. Grafana 자체가 지표를 보관하는 것은 아니며 지표 보존 정책은 Prometheus에서 관리한다.

서버에서는 새 Compose를 적용하기 전에 `.env`에 `GRAFANA_ADMIN_USER`·`GRAFANA_ADMIN_PASSWORD`를 지정한다. 두 값이 비어 있으면 Compose 변수 해석이 실패하므로 다른 서비스의 Compose 명령에도 영향을 준다. 실제 값을 저장소나 채팅에 남기지 않는다. 이 값은 최초 관리자 생성용이며, 기존 Grafana 데이터 볼륨이 있으면 환경변수 변경만으로 기존 비밀번호가 바뀌지는 않는다. 익명 접근과 사용자 직접 가입은 비활성화한다.

### nginx /grafana 연결 (67)

외부 주소는 `https://k15c106.p.ssafy.io/grafana/`다. Grafana의 `GF_SERVER_ROOT_URL`에 이 주소를 지정하고 `GF_SERVER_SERVE_FROM_SUB_PATH=true`로 하위 경로를 처리한다. nginx는 `/grafana`를 `/grafana/`로 이동시키고 하위 경로를 유지한 채 내부 `grafana:3000`으로 전달한다. 외부 TLS는 nginx가 처리하고 내부 연결은 HTTP다. 기존 loopback 바인딩 `127.0.0.1:13000:3000`은 유지하며 새 외부 포트를 열지 않는다.

nginx는 Docker DNS resolver와 변수 upstream을 사용해 Grafana 주소를 다시 조회한다. Upgrade·Connection 헤더로 Grafana Live WebSocket 연결도 전달한다. Grafana 헬스체크는 `/grafana/api/health`를 조회한다. 서버 설정은 `GF_SECURITY_COOKIE_SECURE=true`로 로그인 쿠키를 HTTPS 연결에서만 전송하도록 한다.

적용 후 SSH 터널로 health를 조회할 때도 `/grafana/api/health` 경로를 사용한다. 예를 들어 로컬 13001을 서버 13000에 연결했다면 `http://127.0.0.1:13001/grafana/api/health`다. 서버 설정의 브라우저 로그인은 외부 HTTPS 주소를 사용한다. HTTP 터널은 Secure 쿠키를 사용하는 로그인 검증을 대신하지 않는다. 노트북의 별도 HTTP 검사 컨테이너에서만 `GF_SECURITY_COOKIE_SECURE=false`로 덮어쓰고, root URL은 브라우저가 접속하는 nginx 주소와 일치시킨다. 이 검사에서는 `http://127.0.0.1:13003/grafana/`를 사용했다.

2026-11-02 로컬 검증(사용자 실행 결과): Compose config 종료 코드 0, nginx 1.30 문법 검사 통과. 서버 인증서를 복사하지 않고 임시 nginx 설정에서 TLS 인증서·SSL listen만 제외했다. 별도 Grafana 13.3.0과 nginx를 연결해 `/grafana` 301, `/grafana/` 302 및 `/grafana/login` 이동, 로그인 화면 200, `/grafana/api/health` 200과 `database=ok`를 확인했다. 비로그인 `/grafana/api/user`와 `/grafana/api/search`는 모두 401이었다. 기존 IP를 임시 컨테이너로 예약하고 Grafana를 172.20.0.2에서 172.20.0.4로 재생성한 후 nginx 재시작 없이 health 200을 확인했다. 별도 HTTP 검사에서 로그인·새로고침 후 세션 유지, nginx 경유 화면 접속을 확인했다. Grafana Live는 초기 root URL(13002)과 브라우저 Origin(13003) 불일치로 403이 발생했고, root URL을 nginx 주소(13003)로 맞춘 뒤 브라우저에서 WebSocket 101을 확인했다. 로그아웃 후 로그인 화면으로 돌아가는 것도 확인했다. 임시 검사 컨테이너와 전용 네트워크는 정리했다. 실제 HTTPS·인증서·Secure 쿠키·서버 계정 권한과 개별 정적 파일 응답 검증은 남아 있다.

2026-11-02 서버 계정 사전 점검(사용자 확인): 기존 관리자 비밀번호가 16자 이상임을 확인했다. 비밀번호 값은 이 점검에서 수집하거나 기록하지 않았다. 팀원용 계정을 생성하고 조직 역할이 Viewer인 것을 확인했다. 기존 SSH 터널로 시크릿 창에서 Viewer 로그인 후 자원 대시보드 조회와 편집·저장·삭제 및 관리자 설정 변경 기능의 제한을 확인했다. 이는 사용자가 보고한 UI 동작 확인이며, 수정 API를 직접 호출하는 권한 검증이나 외부 HTTPS 경로의 최종 검증은 아니다. 기본 비밀번호 로그인 불가 여부도 별도로 확인해야 한다.

서버 반영 전에는 위 계정 정책을 유지한다. 기존 데이터 볼륨의 비밀번호는 `.env` 변경만으로 갱신되지 않는다. 비밀번호·쿠키·토큰을 저장소나 채팅에 남기지 않는다. 서버 기존 설정을 백업·비교하고 실제 인증서를 사용한 `nginx -t`를 통과한 뒤 Grafana 재생성과 nginx reload를 수행한다. 배포 후 HTTPS 로그인 화면·정적 파일·로그인 및 로그아웃·Viewer 권한·Grafana Live, Prometheus·Loki 조회 유지와 포트 접근을 확인한다. 로그인 쿠키의 Secure 속성은 실제 HTTPS 응답 또는 브라우저 쿠키 속성에서 확인하며 쿠키 값은 공유하지 않는다. 기본 비밀번호 인증 실패는 반복 요청 없이 확인한다. 서버 반영은 리뷰·병합 이후에 진행하며, 사전 계정 점검과 로컬 검증만으로 67 완료로 처리하지 않는다.

### 자원 대시보드 자동 등록

`grafana/provisioning/dashboards/axcore.yaml`은 `/etc/grafana/dashboards`의 JSON 파일을 시작 시와 30초 주기로 읽는다. `grafana/dashboards/axcore-resources.json`은 UI에서 만든 대시보드를 Classic JSON으로 내보낸 파일이며, UID `axjnjgr`와 데이터 소스 UID `axcore-prometheus`를 유지한다. 동일 UID의 로컬 대시보드가 있으면 파일 내용이 해당 대시보드에 반영된다. 다른 UID의 대시보드는 관리 대상이 아니다.

UI 편집·저장은 허용하지만 UI 변경이 저장소 JSON에 자동으로 기록되지는 않는다. JSON 파일이 갱신되면 UI 변경을 덮어쓸 수 있으므로, 수정한 화면은 다시 Classic JSON으로 내보내 저장소 파일에도 반영한다. `disableDeletion: true`로 설정해 원본 JSON 파일이 없어졌다는 이유만으로 DB의 대시보드를 자동 삭제하지 않는다.

패널은 호스트 메모리·CPU·루트 디스크 사용률과 서비스별 메모리·CPU 사용률 5개다. 로컬에서는 루트 디스크·컨테이너 지표가 없어 해당 3개 패널은 No data가 예상된다. 서버 반영 후 실제 그래프와 변환된 패널 배치·단위, 새로고침 30초·기본 시간 범위를 확인한다. Classic 내보내기에서 V2 전용 레이아웃 기능은 유지되지 않을 수 있다.

잔여: Grafana `search-manifest-watcher`의 `zero manifests` 경고는 내부 검색용 앱 설명 정보 조회 결과가 비어 발생한다. 로컬 Prometheus 연결·지표 조회는 확인했으며, 검색 동작과 경고 반복 여부는 추가 확인한다. 로그를 숨기는 설정은 추가하지 않았다.

## Linux 서버 접근 권한

cAdvisor의 Docker 수집을 위해 Alloy에 root 사용자·`privileged: true`·호스트 cgroup namespace와 시스템 경로를 설정한다. 운영 반영 전에 이 권한 변경을 검토한다. Docker 소켓을 읽기 전용으로 마운트해도 Docker API의 변경 요청을 차단하지는 않는다.

현재 확인한 서버는 Docker 29.9.2, cgroup v2, `overlayfs`, Docker 저장 경로 `/var/lib/docker`다. containerd 소켓은 `/run/containerd/containerd.sock`을 사용한다. 소켓이 없으면 자동으로 디렉터리를 만들지 않고 기동에 실패하도록 설정한다.

서버에서 8개 컨테이너의 CPU·메모리 수집은 확인했지만, `containerd_namespace="moby"` 설정에서는 이름이 ID로 나오고 Compose 라벨이 누락됐다. containerd 수집기의 namespace를 기본값 `k8s.io`로 분리해 Docker 컨테이너는 Docker 처리기가 조회하도록 조정한다. 이는 Kubernetes를 추가하는 설정이 아니며 Docker가 실제 사용하는 `moby` namespace도 변경하지 않는다. Compose 서비스 라벨은 추가로 `service` 라벨에 복사한다. 이 수정으로 이름·라벨이 복구되는지는 서버 반영 후 확인해야 한다.

## 검증 범위

노트북에서는 `validate /etc/alloy`로 두 Alloy 파일의 문법·참조 관계를 검사하고, Compose 기본 설정과 로컬 설정을 각각 검사한다. Docker Desktop의 로컬 기동은 호스트 수집 경로 확인용이며 서버 컨테이너 수집 성공을 의미하지 않는다.

Linux 서버 반영 후에는 다음을 확인한다.

- 기존 호스트 수집 7개 항목의 성공 값이 모두 1이고 실제 메모리·루트 디스크 용량과 일치하는지.
- `up{job="cadvisor"}`가 1이고 실제 실행 중인 서비스마다 `container_cpu_usage_seconds_total`, `container_memory_working_set_bytes`가 있는지. `up=1`만으로 개별 컨테이너 수집을 완료 처리하지 않는다.
- 위 두 지표에 `service="backend"`, `service="ai"` 등 실제 Compose 서비스 라벨이 붙는지. Compose로 생성되지 않은 임시 컨테이너에는 서비스 라벨이 없을 수 있으며 임의로 이름을 붙이지 않는다.
- Alloy 로그의 런타임 연결·권한 오류, Prometheus 상태와 기존 서비스 상태.
- 수집 추가 후 Alloy 메모리 사용량과 재시작·OOM 여부. 실제 배포·분석 부하 실측과 상한 확정은 별도 잔여 작업이다.
