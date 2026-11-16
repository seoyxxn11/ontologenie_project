// Jenkins 단계 API와 health 결과만 대체한다. 실제 Docker·서버에는 접근하지 않는다.
def source = new File('Jenkinsfile').getText('UTF-8')
def start = source.indexOf('def deployBackendAndBatch()')
assert start >= 0 : '배포 함수가 없습니다.'

def harness = { Map options = [:] ->
    def commands = []
    def checks = []
    def environment = [DEPLOY_DIR: '/test/axcore', GIT_SHA: 'new1234']
    def script = new GroovyShell().parse(source.substring(start))
    script.binding = new Binding([
        env: environment,
        echo: { Object message -> },
        error: { Object message -> throw new IllegalStateException(message.toString()) },
        sh: { Object input ->
            def command = input instanceof Map ? input.script.toString() : input.toString()
            commands << command
            if (input instanceof Map && input.returnStdout) {
                if (command.contains('config --services')) {
                    if (options.configError) throw new IllegalStateException('config failed')
                    return options.noBatch ? 'backend\nai\n' : 'backend\nbatch\nai\n'
                }
                return options.firstDeployment ? '' : 'old1234\n'
            }
            if (input instanceof Map && input.returnStatus) {
                return options.noPreviousBatch ? 1 : 0
            }
            if (options.imageMismatch && command.contains('docker inspect batch') && command.contains('.Config.Image')) {
                throw new IllegalStateException('image mismatch')
            }
            return null
        }
    ])
    script.metaClass.waitForHealthyServices = { List services ->
        checks << new ArrayList(services)
        if (options.failedDeploy && checks.size() == 1) {
            throw new IllegalStateException('deploy health failed')
        }
        if (options.failedRollback && checks.size() == 2) {
            throw new IllegalStateException('rollback health failed')
        }
    }
    [script: script, commands: commands, checks: checks, env: environment]
}

def fails = { Closure operation ->
    boolean failed = false
    try { operation() } catch (IllegalStateException expected) { failed = true }
    assert failed : '실패가 성공으로 처리됐습니다.'
}

def success = harness()
success.script.deployBackendAndBatch()
assert success.commands.any { it.contains('up -d backend batch') }
assert success.checks == [['backend', 'batch']]
assert success.env.BACKEND_ROLLED_BACK == 'false'
println 'PASS: backend와 batch 동시 갱신'

def rollback = harness(failedDeploy: true)
fails { rollback.script.deployBackendAndBatch() }
assert rollback.commands.count { it.contains('up -d backend batch') } == 2
assert rollback.commands.findAll { it.contains('up -d backend batch') }.every {
    it.contains("env -u AI_ONTOLOGY_LLM_PROVIDER -u AI_ONTOLOGY_LLM_BASE_URL -u AI_ONTOLOGY_LLM_API_KEY -u AXCORE_AGENT_LLM docker compose --env-file '/test/axcore/.env'")
}
assert rollback.commands.any { it.contains('BACKEND_TAG=old1234') }
assert rollback.checks == [['backend', 'batch'], ['backend', 'batch']]
assert rollback.env.BACKEND_ROLLED_BACK == 'true'
println 'PASS: 실패 시 두 서비스 복구 및 재검증'

def firstBatch = harness(failedDeploy: true, noPreviousBatch: true)
fails { firstBatch.script.deployBackendAndBatch() }
assert firstBatch.commands.any { it.contains('stop batch') }
assert firstBatch.checks == [['backend', 'batch'], ['backend']]
assert firstBatch.env.BACKEND_ROLLED_BACK == 'true'
println 'PASS: 첫 batch 실패 시 신규 batch 정지 및 backend 복구'

def failedRollback = harness(failedDeploy: true, failedRollback: true)
fails { failedRollback.script.deployBackendAndBatch() }
assert failedRollback.env.BACKEND_ROLLED_BACK == 'false'
println 'PASS: 복구 검사 실패를 롤백 성공으로 표시하지 않음'

def missingBatch = harness(noBatch: true)
missingBatch.script.deployBackendAndBatch()
assert missingBatch.checks == [['backend']]
assert !missingBatch.commands.any { it.contains('up -d backend batch') }
println 'PASS: 서버 batch 설정 반영 전 backend 배포 유지'

def mismatch = harness(imageMismatch: true)
fails { mismatch.script.deployBackendAndBatch() }
assert !mismatch.commands.any { it.contains('up -d') }
println 'PASS: 기존 batch 이미지 불일치 시 변경 전 중단'

def badConfig = harness(configError: true)
fails { badConfig.script.deployBackendAndBatch() }
assert !badConfig.commands.any { it.contains('up -d') }
println 'PASS: Compose 해석 실패를 batch 부재로 처리하지 않음'

def firstDeploy = harness(firstDeployment: true, noPreviousBatch: true, failedDeploy: true)
fails { firstDeploy.script.deployBackendAndBatch() }
assert firstDeploy.commands.any { it.contains('stop backend batch') }
assert firstDeploy.env.BACKEND_ROLLED_BACK == 'false'
println 'PASS: 이전 태그가 없으면 실패한 신규 서비스 정지'

println '8 deployment orchestration checks passed (mocked Jenkins steps; no live deployment).'
