// Jenkins 파이프라인 정의. Job 1(axcore-mr-check)과 Job 2(axcore-deploy)가 같은 파일을 쓴다.
// - Job 1: MR이 열리거나 MR 브랜치에 push되면 실행. gitlabSourceBranch = MR 원본 브랜치. 검사만 한다.
// - Job 2: develop에 push(머지)되면 실행. 바뀐 서비스를 빌드해 서버에 배포한다 (5-7).
// 이 파일(지시서)은 항상 develop에서 읽고, 검사할 코드는 Checkout 단계에서 받는다.
// 결과는 GitLab(MR 화면 ✅/❌)과 Mattermost DM으로 알린다. DM은 배포 결과(성공·실패)와 MR 검사 실패에만 보낸다.
pipeline {
    agent any

    options {
        // 빌드 기록은 최근 10개만 보관해 디스크를 아낀다.
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // GitLab에 빌드 상태(성공/실패)를 써 줄 연결. Jenkins 관리 → System → GitLab의 Connection name.
        gitLabConnection('ssafy-gitlab')
    }

    parameters {
        // gitlabSourceBranch·gitlabTargetBranch를 파라미터로 선언하지 않는다 (2026-10-30 제거).
        // 선언하면 기본값 'develop'이 GitLab 플러그인이 넣어 주는 환경변수를 덮어써서 MR 검사가 develop을 받았다.
        // backend가 안 바뀐 머지여도 강제로 배포하고 싶을 때 (수동 실행 전용)
        booleanParam(name: 'FORCE_DEPLOY', defaultValue: false, description: 'backend 변경이 없어도 배포')
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

                    // MR 검사에서 backend 검사를 돌릴지 정한다. develop과 갈라진 뒤 backend/ 가 바뀐 MR만 돌린다 (문서만 고친 MR에 gradle 몇 분을 쓰지 않는다).
                    // 수동 빌드는 항상 돌린다 (develop 전체를 다시 검사하는 용도).
                    if (env.IS_DEPLOY_JOB != 'true') {
                        if (!env.gitlabActionType) {
                            env.CHECK_BACKEND = 'true'
                        } else {
                            def backendChanged = sh(returnStatus: true, script: 'git diff --quiet origin/develop...HEAD -- backend/') != 0
                            env.CHECK_BACKEND = backendChanged ? 'true' : 'false'
                            echo "backend/ 변경: ${backendChanged} → Backend check ${backendChanged ? '실행' : '건너뜀'}"
                        }
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

        stage('Detect changes') {
            // 서버에 지금 배포된 backend(.env의 BACKEND_TAG = 커밋 번호)와 지금 develop 사이에 backend/ 가 바뀌었는지 본다.
            // 직전 커밋(HEAD~1)과 비교하면 머지가 연달아 쌓여 빌드가 한 번만 돌 때 앞 머지의 backend 변경을 놓친다 (2026-10-30 발견).
            // 바뀐 서비스만 배포한다 (개발 가이드 13.1). FORCE_DEPLOY면 무조건 배포.
            when { expression { env.IS_DEPLOY_JOB == 'true' } }
            steps {
                script {
                    def deployed = sh(returnStdout: true, script: "grep '^BACKEND_TAG=' ${env.DEPLOY_DIR}/.env | cut -d= -f2").trim()
                    def changed
                    if (!deployed) {
                        echo "배포된 backend 태그가 없다 → 배포한다 (첫 배포)"
                        changed = true
                    } else if (sh(returnStatus: true, script: "git cat-file -e '${deployed}^{commit}'") != 0) {
                        echo "배포된 태그 ${deployed}가 저장소의 커밋이 아니다 → 비교할 수 없어 배포한다"
                        changed = true
                    } else {
                        changed = sh(returnStatus: true, script: "git diff --quiet ${deployed} HEAD -- backend/") != 0
                    }
                    env.DEPLOY_BACKEND = (changed || params.FORCE_DEPLOY) ? 'true' : 'false'
                    echo "배포된 backend: ${deployed ?: '없음'}, 지금 develop: ${env.GIT_SHA}. backend 변경: ${changed}, FORCE_DEPLOY: ${params.FORCE_DEPLOY} → 배포: ${env.DEPLOY_BACKEND}"
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
                script {
                    // 되돌릴 때 쓸 직전 태그를 기억한다.
                    env.PREV_TAG = sh(returnStdout: true, script: "grep '^BACKEND_TAG=' ${env.DEPLOY_DIR}/.env | cut -d= -f2").trim()
                    echo "backend ${env.PREV_TAG} → ${env.GIT_SHA}"
                    try {
                        // .env의 BACKEND_TAG를 새 번호로 바꾸고 backend만 새로 띄운다. (수동 배포 절차 4·5번)
                        sh """
                            sed -i 's/^BACKEND_TAG=.*/BACKEND_TAG=${env.GIT_SHA}/' ${env.DEPLOY_DIR}/.env
                            docker compose -f ${env.DEPLOY_DIR}/compose.yaml up -d backend
                        """
                        // 최대 90초(5초 × 18번) 동안 (healthy)가 될 때까지 기다린다. 5-3 헬스체크를 그대로 쓴다.
                        sh '''
                            for i in $(seq 1 18); do
                                s=$(docker inspect --format '{{.State.Health.Status}}' backend 2>/dev/null || echo none)
                                echo "health: $s ($i/18)"
                                [ "$s" = healthy ] && exit 0
                                sleep 5
                            done
                            echo "헬스체크 실패: 90초 안에 healthy가 되지 않음"
                            docker compose -f "$DEPLOY_DIR/compose.yaml" logs --tail 50 backend
                            exit 1
                        '''
                    } catch (e) {
                        // 롤백: 직전 태그로 되돌리고 다시 띄운다. 그 뒤 빌드를 실패로 표시해 DM이 가게 한다.
                        echo "배포 실패 → ${env.PREV_TAG} 로 롤백"
                        sh """
                            sed -i 's/^BACKEND_TAG=.*/BACKEND_TAG=${env.PREV_TAG}/' ${env.DEPLOY_DIR}/.env
                            docker compose -f ${env.DEPLOY_DIR}/compose.yaml up -d backend
                        """
                        env.ROLLED_BACK = 'true'
                        throw e
                    }
                }
            }
        }

        stage('Cleanup images') {
            when { expression { env.IS_DEPLOY_JOB == 'true' } }
            steps {
                // backend 이미지는 최근 3개 태그만 남긴다 (지금 것 + 롤백용 2개). 쓰는 중인 이미지는 지워지지 않는다.
                // 빌드 캐시는 3일 지난 것만 지운다.
                sh '''
                    docker images axcore-backend --format '{{.Tag}} {{.CreatedAt}}' | sort -k2 -r | awk 'NR>3 {print $1}' \
                        | xargs -r -I{} docker rmi axcore-backend:{} || true
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
    // 배포가 있었으면 어떤 버전으로 바뀌었는지(또는 롤백됐는지) 한 줄 더 붙인다.
    def deploy = ''
    if (env.DEPLOY_BACKEND == 'true') {
        deploy = env.ROLLED_BACK == 'true'
            ? "\nbackend 배포 실패 → ${env.PREV_TAG} 로 롤백됨"
            : "\nbackend 배포 ${env.PREV_TAG} → ${env.GIT_SHA}"
    }
    def text = "${result} ${env.JOB_NAME} #${env.BUILD_NUMBER} · ${what}${deploy}\n로그: ${env.BUILD_URL}console"
    def payload = groovy.json.JsonOutput.toJson([channel: "@${who}", text: text])
    echo "Mattermost DM → @${who}"
    // 비밀값(MM_WEBHOOK)은 Groovy 문자열에 섞지 않고 셸 환경변수로만 읽는다. 알림 실패가 빌드 결과를 바꾸면 안 되므로 || echo.
    withEnv(["MM_PAYLOAD=${payload}"]) {
        sh 'curl -sS -f -X POST -H "Content-Type: application/json" -d "$MM_PAYLOAD" "$MM_WEBHOOK" > /dev/null || echo "Mattermost 알림 실패"'
    }
}