// Jenkins 파이프라인 정의. Job 1(axcore-mr-check)과 Job 2(axcore-deploy)가 같은 파일을 쓴다.
// - Job 1: MR이 열리거나 MR 브랜치에 push되면 실행. gitlabSourceBranch = MR 원본 브랜치. 검사만 한다.
// - Job 2: develop에 push(머지)되면 실행. 바뀐 서비스를 빌드해 서버에 배포한다 (5-7).
// 이 파일(지시서)은 항상 develop에서 읽고, 검사할 코드는 Checkout 단계에서 받는다.
// 결과는 GitLab(MR 화면 ✅/❌)과 Mattermost DM으로 알린다. DM은 배포 결과(성공·실패)와 MR 검사 실패에만 보낸다.
pipeline {
    agent any

    options {
        // 같은 서버의 이미지 태그·롤백 상태를 두 빌드가 동시에 바꾸지 않는다.
        disableConcurrentBuilds()
        // 빌드 기록은 최근 10개만 보관해 디스크를 아낀다.
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // GitLab에 빌드 상태(성공/실패)를 써 줄 연결. Jenkins 관리 → System → GitLab의 Connection name.
        gitLabConnection('ssafy-gitlab')
    }

    parameters {
        // gitlabSourceBranch·gitlabTargetBranch를 파라미터로 선언하지 않는다 (2026-10-30 제거).
        // 선언하면 기본값 'develop'이 GitLab 플러그인이 넣어 주는 환경변수를 덮어써서 MR 검사가 develop을 받았다.
        // 변경이 없어도 세 서비스를 모두 강제로 배포하고 싶을 때 (수동 실행 전용)
        booleanParam(name: 'FORCE_DEPLOY', defaultValue: false, description: '변경이 없어도 backend · AI · frontend 모두 배포')
    }

    environment {
        // Webhook으로 시작된 빌드는 GitLab 플러그인이 gitlabSourceBranch·gitlabMergeRequestLastCommit을 채워 준다.
        // 사람이 직접 돌린 빌드는 값이 없으므로 develop을 검사한다.
        CHECK_BRANCH = "${env.gitlabSourceBranch ?: 'develop'}"
        // 실제로 받을 대상.
        // - 배포 Job(axcore-deploy)은 무조건 origin/develop. GitLab이 MR 변수를 보내더라도(트리거 설정 실수 등) 머지 안 된 코드를 배포하지 않는다.
        // - MR 검사는 GitLab이 알려 준 MR 커밋 자체를 받는다 (브랜치 이름이 비거나 틀려도 정확한 코드). 수동 빌드는 MR 커밋이 없으므로 브랜치로.
        CHECK_REF = "${env.JOB_NAME == 'axcore-deploy' ? 'origin/develop' : (env.gitlabMergeRequestLastCommit ?: ('origin/' + (env.gitlabSourceBranch ?: 'develop')))}"
        // Mattermost Incoming Webhook 주소. Jenkins 관리 → Credentials의 Secret text (ID: mm-webhook).
        // 로그에는 ****로 가려진다. 없으면 빌드가 시작하자마자 실패하니 먼저 등록할 것.
        MM_WEBHOOK = credentials('mm-webhook')
        // 가게 폴더. Jenkins 컨테이너에 같은 주소로 연결돼 있다 (compose.yaml의 jenkins volumes).
        DEPLOY_DIR = '/home/ubuntu/axcore'
        // 배포 단계는 Job 2(axcore-deploy)에서만 켠다. MR 검증(Job 1)은 절대 배포하지 않는다.
        IS_DEPLOY_JOB = "${env.JOB_NAME == 'axcore-deploy' ? 'true' : 'false'}"
    }

    stages {
        stage('Checkout') {
            steps {
                // 진단: GitLab 플러그인이 이 빌드에 넣어 준 변수 목록. 검사 대상이 엉뚱하면 여기부터 본다. (이메일은 가린다)
                sh 'env | grep "^gitlab" | grep -vi email | sort || echo "(gitlab 변수 없음: 수동 빌드)"'
                // 검사할 코드를 받는다. MR 검증이면 MR 커밋, develop 머지면 develop. (CHECK_REF 설명 참고)
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: env.CHECK_REF]],
                    userRemoteConfigs: [[
                        url: 'https://lab.ssafy.com/s15-final/S15P31C106.git',
                        credentialsId: 'gitlab-clone'
                    ]]
                ])
                sh 'git log -1 --oneline'
                script {
                    // 이미지 태그로 쓸 커밋 번호 7자리 (개발 가이드 13.2: 이미지에 커밋 SHA)
                    env.GIT_SHA = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    echo "검사 대상: ${env.CHECK_REF} @ ${env.GIT_SHA} (브랜치=${env.CHECK_BRANCH}, 트리거=${env.gitlabActionType ?: '수동'})"

                    // 안전망 (5-0c): MR 검사인데 받은 코드가 MR 코드가 아니면 멈춘다. 그대로 두면 develop을 검사하고 MR에 거짓 ✅가 찍힌다.
                    // 2026-10-30 실제 발생: MR 검사 빌드가 전부 develop을 받아 13초 만에 "전부 최신"으로 ✅를 찍었다.
                    if (env.IS_DEPLOY_JOB != 'true' && env.gitlabActionType) {
                        def head = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        if (env.gitlabMergeRequestLastCommit) {
                            if (head != env.gitlabMergeRequestLastCommit) {
                                error "검사한 커밋이 MR 커밋과 다릅니다. HEAD=${head}, MR=${env.gitlabMergeRequestLastCommit}"
                            }
                        } else if (env.CHECK_BRANCH == 'develop') {
                            error "MR 검사인데 GitLab이 MR 커밋도 브랜치 이름도 주지 않아 develop을 받았습니다. 위의 gitlab 변수 목록을 확인하세요."
                        }
                    }

                    // MR 검사에서 어느 서비스 검사를 돌릴지 정한다. develop과 갈라진 뒤 그 폴더가 바뀐 MR만 돌린다 (문서만 고친 MR에 gradle 몇 분을 쓰지 않는다).
                    // 수동 빌드는 폴더가 있는 서비스를 전부 돌린다 (develop 전체를 다시 검사하는 용도).
                    if (env.IS_DEPLOY_JOB != 'true') {
                        if (!env.gitlabActionType) {
                            env.CHECK_BACKEND = 'true'
                            env.CHECK_FRONTEND = fileExists('frontend/package.json') ? 'true' : 'false'
                            env.CHECK_AI = fileExists('ai/requirements-dev.txt') ? 'true' : 'false'
                        } else {
                            def changed = { String dir -> sh(returnStatus: true, script: "git diff --quiet origin/develop...HEAD -- ${dir}/") != 0 }
                            env.CHECK_BACKEND = changed('backend') ? 'true' : 'false'
                            env.CHECK_FRONTEND = changed('frontend') ? 'true' : 'false'
                            env.CHECK_AI = changed('ai') ? 'true' : 'false'
                        }
                        echo "검사 대상 서비스: backend=${env.CHECK_BACKEND}, frontend=${env.CHECK_FRONTEND}, ai=${env.CHECK_AI} (true = 실행, false = 건너뜀)"
                    }

                    // Webhook 빌드일 때만 GitLab에 "검사 중" 상태를 올린다. (수동 빌드는 대상 커밋이 없음)
                    if (env.gitlabActionType) {
                        updateGitlabCommitStatus name: 'jenkins', state: 'running'
                    }
                }
            }
        }

        stage('Backend check') {
            // MR 검증(Job 1)에서만. 스타일 검사 → 컴파일 → 테스트를 한 번에 돌린다 (개발 가이드 13.1, 5-0c).
            // 테스트의 Testcontainers가 docker.sock으로 테스트용 PostgreSQL을 띄운다. 주소는 compose의 TESTCONTAINERS_HOST_OVERRIDE.
            // --no-daemon: 빌드가 끝나면 gradle JVM을 바로 내려 Jenkins 컨테이너 메모리를 돌려준다.
            // 주의: MR 브랜치의 build.gradle이 docker.sock이 있는 이 컨테이너에서 실행된다(팀원 계정을 신뢰). 분리는 보안 후속.
            // backend/ 가 안 바뀐 MR은 건너뛴다 (Checkout 단계의 CHECK_BACKEND).
            when { expression { env.IS_DEPLOY_JOB != 'true' && env.CHECK_BACKEND == 'true' } }
            steps {
                dir('backend') {
                    sh './gradlew --no-daemon spotlessCheck build'
                }
            }
            post {
                always {
                    // 테스트 결과를 Jenkins에 올려 어느 테스트가 깨졌는지 빌드 화면에서 보이게 한다. 결과 파일이 없으면(컴파일 실패 등) 비워 둔다.
                    junit allowEmptyResults: true, testResults: 'backend/build/test-results/test/*.xml'
                }
            }
        }

        // frontend · ai 검사는 Jenkins 이미지에 Node · Python이 없어 일회용 컨테이너에서 돌린다.
        // 이 컨테이너에는 docker.sock을 붙이지 않으므로 MR 코드가 서버 권한을 얻지 못한다 (backend와 달리 안전한 방식).
        // 작업 폴더는 호스트 경로로 마운트한다: Jenkins 컨테이너의 /var/jenkins_home = 호스트 /home/ubuntu/axcore/jenkins-data (compose volumes).
        // 컨테이너 안에서 만든 파일(node_modules · dist · .pytest_cache)은 root 소유로 남지만 다음 검사도 같은 방식이라 문제없다.
        stage('Frontend check') {
            when { expression { env.IS_DEPLOY_JOB != 'true' && env.CHECK_FRONTEND == 'true' } }
            steps {
                sh '''
                    HOST_WS=$(echo "$WORKSPACE" | sed 's#^/var/jenkins_home#/home/ubuntu/axcore/jenkins-data#')
                    docker run --rm --memory 2g \
                        -e CI=true \
                        -v "$HOST_WS/frontend:/app" -w /app \
                        -v axcore-npm-cache:/root/.npm \
                        node:24.18.0-alpine \
                        sh -c "npm ci --no-audit --no-fund && npm run lint && npx vitest run && npm run build"
                '''
            }
        }

        stage('AI check') {
            when { expression { env.IS_DEPLOY_JOB != 'true' && env.CHECK_AI == 'true' } }
            steps {
                sh '''
                    HOST_WS=$(echo "$WORKSPACE" | sed 's#^/var/jenkins_home#/home/ubuntu/axcore/jenkins-data#')
                    docker run --rm --memory 1g \
                        -e CI=true \
                        -v "$HOST_WS/ai:/app" -w /app \
                        -v axcore-pip-cache:/root/.cache/pip \
                        python:3.11-slim \
                        sh -c "pip install -q -r requirements-dev.txt && ruff check . && pytest -q --junitxml=test-results/pytest.xml"
                '''
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'ai/test-results/*.xml'
                }
            }
        }

        stage('Detect changes') {
            // 서비스마다, 서버에 지금 배포된 태그(.env의 <서비스>_TAG = 커밋 번호)와 지금 develop 사이에 그 폴더가 바뀌었는지 본다.
            // 직전 커밋(HEAD~1)과 비교하면 머지가 연달아 쌓여 빌드가 한 번만 돌 때 앞 머지의 변경을 놓친다 (2026-10-30 발견).
            // 바뀐 서비스만 배포한다 (개발 가이드 13.1). FORCE_DEPLOY면 전부 배포.
            when { expression { env.IS_DEPLOY_JOB == 'true' } }
            steps {
                script {
                    def detect = { String tagVar, String dir ->
                        def deployed = sh(returnStdout: true, script: "grep '^${tagVar}=' ${env.DEPLOY_DIR}/.env | cut -d= -f2").trim()
                        def changed
                        if (!deployed) {
                            echo "배포된 ${dir} 태그가 없다 → 배포한다 (첫 배포)"
                            changed = true
                        } else if (sh(returnStatus: true, script: "git cat-file -e '${deployed}^{commit}'") != 0) {
                            echo "배포된 ${dir} 태그 ${deployed}가 저장소의 커밋이 아니다 → 비교할 수 없어 배포한다"
                            changed = true
                        } else {
                            changed = sh(returnStatus: true, script: "git diff --quiet ${deployed} HEAD -- ${dir}/") != 0
                        }
                        echo "배포된 ${dir}: ${deployed ?: '없음'}, 지금 develop: ${env.GIT_SHA}, ${dir}/ 변경: ${changed}"
                        return changed
                    }
                    env.DEPLOY_BACKEND = (detect('BACKEND_TAG', 'backend') || params.FORCE_DEPLOY) ? 'true' : 'false'
                    env.DEPLOY_FRONTEND = (detect('FRONTEND_TAG', 'frontend') || params.FORCE_DEPLOY) ? 'true' : 'false'
                    env.DEPLOY_AI = (detect('AI_TAG', 'ai') || params.FORCE_DEPLOY) ? 'true' : 'false'
                    echo "FORCE_DEPLOY: ${params.FORCE_DEPLOY} → backend 배포: ${env.DEPLOY_BACKEND}, frontend 배포: ${env.DEPLOY_FRONTEND}, ai 배포: ${env.DEPLOY_AI}"
                }
            }
        }

        stage('Build backend image') {
            when { expression { env.DEPLOY_BACKEND == 'true' } }
            steps {
                // 작업 폴더의 backend/ 로 굽는다. 태그 = 커밋 번호. (수동 배포 절차 3번과 동일)
                sh 'docker build -t axcore-backend:${GIT_SHA} backend'
            }
        }

        stage('Backup DB') {
            when { expression { env.DEPLOY_BACKEND == 'true' } }
            steps {
                // 새 버전의 Flyway 마이그레이션이 DB를 바꿀 수 있으니 직전 상태를 남긴다. (4-3 스크립트 재사용)
                sh '${DEPLOY_DIR}/scripts/pg-backup.sh'
            }
        }

        stage('Deploy backend') {
            when { expression { env.DEPLOY_BACKEND == 'true' } }
            steps {
                // 배포 로직은 아래 deployService 함수 (태그 갱신 → up -d → 90초 헬스체크 → 실패 시 롤백).
                // catchError: 실패해도 파이프라인을 멈추지 않는다. 이 단계와 빌드는 실패로 표시하고(DM 감) 다음 서비스 배포는 계속한다.
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    script { deployBackendAndBatch() }
                }
            }
        }

        stage('Build AI image') {
            when { expression { env.DEPLOY_AI == 'true' } }
            steps {
                // backend 배포 뒤 AI 이미지를 빌드한다. 태그 = 커밋 번호.
                sh 'docker build -t axcore-ai:${GIT_SHA} ai'
            }
        }

        stage('Deploy AI') {
            when { expression { env.DEPLOY_AI == 'true' } }
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    script { deployService('ai', 'AI_TAG') }
                }
            }
        }

        stage('Build frontend image') {
            when { expression { env.DEPLOY_FRONTEND == 'true' } }
            steps {
                // 작업 폴더의 frontend/ 로 굽는다 (frontend/Dockerfile: node 빌드 → nginx 정적 서빙). 태그 = 커밋 번호.
                sh 'docker build -t axcore-frontend:${GIT_SHA} frontend'
            }
        }

        stage('Deploy frontend') {
            when { expression { env.DEPLOY_FRONTEND == 'true' } }
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    script { deployService('frontend', 'FRONTEND_TAG') }
                }
            }
        }

        stage('Cleanup images') {
            when { expression { env.IS_DEPLOY_JOB == 'true' } }
            steps {
                // 서비스 이미지는 최근 3개 태그만 남긴다 (지금 것 + 롤백용 2개). 쓰는 중인 이미지는 지워지지 않는다.
                // 빌드 캐시는 3일 지난 것만 지운다.
                sh '''
                    for img in axcore-backend axcore-ai axcore-frontend; do
                        docker images $img --format '{{.Tag}} {{.CreatedAt}}' | sort -k2 -r | awk 'NR>3 {print $1}' \
                            | xargs -r -I{} docker rmi $img:{} || true
                    done
                    docker builder prune -f --filter until=72h >/dev/null || true
                    docker system df
                '''
            }
        }
    }

    post {
        success {
            script {
                // GitLab MR 화면/커밋에 성공 표시
                if (env.gitlabActionType) {
                    updateGitlabCommitStatus name: 'jenkins', state: 'success'
                }
                // 성공 DM은 배포 Job만 보낸다. MR 검사 성공은 GitLab MR 화면의 ✅로 충분하다 (2026-10-30 결정). 실패는 둘 다 DM.
                if (env.IS_DEPLOY_JOB == 'true') {
                    notifyMattermost(':white_check_mark: 성공')
                }
            }
        }
        failure {
            script {
                // GitLab MR 화면/커밋에 실패 표시. 클릭하면 이 빌드 로그로 온다.
                if (env.gitlabActionType) {
                    updateGitlabCommitStatus name: 'jenkins', state: 'failed'
                }
                notifyMattermost(':x: 실패')
            }
        }
        aborted {
            script {
                if (env.gitlabActionType) {
                    updateGitlabCommitStatus name: 'jenkins', state: 'canceled'
                }
            }
        }
    }
}

// Mattermost DM 전송. 받는 사람 = 이 빌드를 일으킨 GitLab 사용자(MR 작성자, 또는 develop에 머지한 사람).
// SSAFY GitLab 아이디와 Mattermost 아이디가 같아서 "@아이디"로 바로 보낸다. 채널에는 올리지 않는다.
// 아이디 변수는 이벤트마다 다르다: push(배포)는 gitlabUserUsername, MR 이벤트(검사)는 gitlabMergedByUser (2026-10-30 빌드 #44 로그로 확인).
def notifyMattermost(String result) {
    def who = env.gitlabUserUsername ?: env.gitlabMergedByUser
    if (!who) {
        echo 'Mattermost 알림 생략: 수동 빌드라 받는 사람이 없음'
        return
    }
    def what = env.gitlabMergeRequestIid
        ? "MR !${env.gitlabMergeRequestIid} (${env.gitlabSourceBranch} → ${env.gitlabTargetBranch})"
        : 'develop 머지 반영'
    // 배포가 있었으면 서비스마다 어떤 버전으로 바뀌었는지(또는 롤백됐는지) 한 줄씩 붙인다. 값은 deployService가 env에 남긴다.
    def deploy = ''
    for (p in [['backend', 'BACKEND'], ['ai', 'AI'], ['frontend', 'FRONTEND']]) {
        def svc = p[0]
        def key = p[1]
        if (env."DEPLOY_${key}" != 'true') {
            continue
        }
        def prev = env."${key}_PREV_TAG" ?: '없음'
        deploy += env."${key}_ROLLED_BACK" == 'true'
            ? "\n${svc} 배포 실패 → ${prev} 로 롤백됨"
            : "\n${svc} 배포 ${prev} → ${env.GIT_SHA}"
    }
    if (env.IS_DEPLOY_JOB == 'true' && !deploy) {
        deploy = '\n배포: 변경 없음'
    }
    def text = "${result} ${env.JOB_NAME} #${env.BUILD_NUMBER} · ${what}${deploy}\n로그: ${env.BUILD_URL}console"
    def payload = groovy.json.JsonOutput.toJson([channel: "@${who}", text: text])
    echo "Mattermost DM → @${who}"
    // 비밀값(MM_WEBHOOK)은 Groovy 문자열에 섞지 않고 셸 환경변수로만 읽는다. 알림 실패가 빌드 결과를 바꾸면 안 되므로 || echo.
    withEnv(["MM_PAYLOAD=${payload}"]) {
        sh 'curl -sS -f -X POST -H "Content-Type: application/json" -d "$MM_PAYLOAD" "$MM_WEBHOOK" > /dev/null || echo "Mattermost 알림 실패"'
    }
}

// 서비스 하나를 새 태그(GIT_SHA)로 띄우고 healthy를 기다린다. 실패하면 직전 태그로 되돌리고 예외를 던진다.
// svc = compose 서비스 이름(= 컨테이너 이름), tagVar = .env의 태그 변수 이름 (BACKEND_TAG / AI_TAG / FRONTEND_TAG).
// 결과는 env.<KEY>_PREV_TAG · env.<KEY>_ROLLED_BACK 에 남겨 DM 문구에서 쓴다 (KEY = BACKEND / AI / FRONTEND).
// 손으로 하던 절차와 같다: .env 태그 갱신 → docker compose up -d <svc> → docker compose ps (healthy).
def deployBackendAndBatch() {
    // 서버 Compose는 수동 반영한다. 54 설정 반영 전에는 기존 backend 배포를 유지한다.
    def configuredServices = sh(returnStdout: true, script: "docker compose -f ${env.DEPLOY_DIR}/compose.yaml config --services").trim().tokenize()
    def hasBatch = configuredServices.contains('batch')
    if (!hasBatch) {
        echo '서버 Compose에 batch가 없어 backend만 배포합니다. 54 설정 반영 후 batch 자동 갱신이 활성화됩니다.'
        deployService('backend', 'BACKEND_TAG')
        return
    }
    deployService('backend', 'BACKEND_TAG', ['batch'])
}

def waitForHealthyServices(List services) {
    def httpServices = services.findAll { it != 'batch' }
    if (httpServices) {
        waitForHttpHealth(httpServices)
    }
    if (services.contains('batch')) {
        waitForBatchStartup()
        // batch 안정화 중 HTTP 서비스가 실패한 경우도 성공으로 판정하지 않는다.
        if (httpServices) {
            waitForHttpHealth(httpServices)
        }
    }
}

def waitForBatchStartup() {
    // 현재 프로세스의 시작 시각 이후 로그만 확인해 이전 기동 로그를 재사용하지 않는다.
    sh '''
        batch_id=$(docker inspect --format '{{.Id}}' batch)
        started=$(docker inspect --format '{{.State.StartedAt}}' batch)
        check_batch_process() {
            current_id=$(docker inspect --format '{{.Id}}' batch) || return 1
            current_start=$(docker inspect --format '{{.State.StartedAt}}' batch) || return 1
            state=$(docker inspect --format '{{.State.Running}} {{.RestartCount}} {{.State.OOMKilled}}' batch) || return 1
            [ "$current_id" = "$batch_id" ] && [ "$current_start" = "$started" ] && [ "$state" = 'true 0 false' ]
        }
        started_found=false
        for i in $(seq 1 18); do
            check_batch_process || { echo 'batch 종료·재시작·OOM 또는 컨테이너 교체 감지'; exit 1; }
            if docker logs --since "$started" "$batch_id" 2>&1 | grep -q 'Started PlatformApplication'; then
                started_found=true
                break
            fi
            sleep 5
        done
        [ "$started_found" = true ] || { echo 'batch 기동 로그 확인 실패: 90초 초과'; exit 1; }
        echo 'batch Started 확인. 60초간 동일 프로세스 유지 확인'
        for i in $(seq 1 12); do
            sleep 5
            check_batch_process || { echo 'batch 안정화 중 종료·재시작·OOM 또는 교체 감지'; exit 1; }
        done
        echo 'batch 기동 및 60초 안정화 확인 완료'
    '''
}

def waitForHttpHealth(List services) {
    def serviceNames = services.join(' ')
    sh """
        for i in \$(seq 1 18); do
            all_healthy=true
            for service in ${serviceNames}; do
                s=\$(docker inspect --format '{{.State.Health.Status}}' \$service 2>/dev/null || echo none)
                echo "\$service health: \$s (\$i/18)"
                [ "\$s" = healthy ] || all_healthy=false
            done
            [ "\$all_healthy" = true ] && exit 0
            sleep 5
        done
        echo "헬스체크 실패: 90초 안에 전체 서비스가 healthy가 되지 않음"
        docker compose -f ${env.DEPLOY_DIR}/compose.yaml logs --tail 50 ${serviceNames}
        exit 1
    """
}

def deployService(String svc, String tagVar, List companions = []) {
    def key = tagVar.replace('_TAG', '')
    def services = [svc] + companions
    def serviceNames = services.join(' ')
    // 되돌릴 때 쓸 직전 태그를 기억한다.
    def prev = sh(returnStdout: true, script: "grep '^${tagVar}=' ${env.DEPLOY_DIR}/.env | cut -d= -f2").trim()
    env."${key}_PREV_TAG" = prev
    env."${key}_ROLLED_BACK" = 'false'
    def rollbackServices = [svc]
    def stopOnRollback = []
    for (companion in companions) {
        def wasRunning = sh(returnStatus: true, script: "[ \"\$(docker inspect --format '{{.State.Running}}' ${companion} 2>/dev/null)\" = true ]") == 0
        if (wasRunning) {
            // 버전이 이미 다른 상태에서 공유 태그만으로 롤백하지 않는다.
            if (!prev) {
                error("${companion} 실행 중이지만 이전 ${tagVar}가 없어 배포를 중단합니다.")
            }
            sh """
                expected=\$(docker image inspect axcore-backend:${prev} --format '{{.Id}}')
                actual=\$(docker inspect ${companion} --format '{{.Image}}')
                [ "\$expected" = "\$actual" ] || { echo '${companion} 이미지와 ${tagVar} 불일치'; exit 1; }
            """
            rollbackServices.add(companion)
        } else {
            stopOnRollback.add(companion)
        }
    }
    echo "${serviceNames} ${prev ?: '없음'} → ${env.GIT_SHA}"
    try {
        // .env의 태그를 새 번호로 바꾸고(줄이 없으면 만들고) 그 서비스만 새로 띄운다.
        sh """
            grep -q '^${tagVar}=' ${env.DEPLOY_DIR}/.env || echo '${tagVar}=' >> ${env.DEPLOY_DIR}/.env
            sed -i 's/^${tagVar}=.*/${tagVar}=${env.GIT_SHA}/' ${env.DEPLOY_DIR}/.env
            docker compose -f ${env.DEPLOY_DIR}/compose.yaml up -d ${serviceNames}
        """
        // HTTP 서비스 health 확인 후 batch는 기동 로그와 60초 안정화를 별도 확인한다.
        waitForHealthyServices(services)
    } catch (e) {
        // 롤백 후에도 health를 확인한다. 이전 태그가 없는 신규 서비스는 정지한다.
        echo "${serviceNames} 배포 실패 → ${prev ?: '(직전 태그 없음)'} 로 롤백"
        if (prev) {
            if (stopOnRollback) {
                // 첫 batch 배포 실패 시 이전에 없던 작업 소비자를 실행 상태로 남기지 않는다.
                sh "docker compose -f ${env.DEPLOY_DIR}/compose.yaml stop ${stopOnRollback.join(' ')}"
            }
            sh """
                sed -i 's/^${tagVar}=.*/${tagVar}=${prev}/' ${env.DEPLOY_DIR}/.env
                docker compose -f ${env.DEPLOY_DIR}/compose.yaml up -d ${rollbackServices.join(' ')}
            """
            waitForHealthyServices(rollbackServices)
            env."${key}_ROLLED_BACK" = 'true'
        } else {
            sh "docker compose -f ${env.DEPLOY_DIR}/compose.yaml stop ${serviceNames}"
            echo '직전 태그가 없어 실패한 신규 서비스를 정지했습니다. 수동 복구가 필요합니다.'
        }
        throw e
    }
}
