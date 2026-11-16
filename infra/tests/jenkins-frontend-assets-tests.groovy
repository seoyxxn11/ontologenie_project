// 실제 배포 함수의 실행 순서를 검사한다. Docker·운영 서버에는 접근하지 않는다.
def source = new File('Jenkinsfile').getText('UTF-8')
def start = source.indexOf('def deployBackendAndBatch()')
assert start >= 0

def harness = { boolean failArchive = false, boolean failHealth = false ->
    def events = []
    def environment = [DEPLOY_DIR: '/test/axcore', GIT_SHA: 'abcdef12']
    def script = new GroovyShell().parse(source.substring(start))
    script.binding = new Binding([
        env: environment,
        params: [FRONTEND_ASSET_RETENTION_DAYS: '7'],
        echo: { Object ignored -> },
        withEnv: { List variables, Closure body ->
            events << variables
            body()
        },
        sh: { Object input ->
            def command = input instanceof Map ? input.script.toString() : input.toString()
            events << command
            if (command.contains('retain-frontend-assets.sh') && failArchive) {
                throw new IllegalStateException('archive failed')
            }
            if (input instanceof Map && input.returnStdout) return 'old1234\n'
        },
    ])
    int checks = 0
    script.metaClass.waitForHealthyServices = { List services ->
        events << "health:${services.join(',')}"
        if (failHealth && ++checks == 1) throw new IllegalStateException('health failed')
    }
    [script: script, events: events, env: environment]
}

def success = harness()
success.script.deployService('frontend', 'FRONTEND_TAG')
def archiveIndex = success.events.findIndexOf { it instanceof String && it.contains('retain-frontend-assets.sh') }
def switchIndex = success.events.findIndexOf { it instanceof String && it.contains('up -d frontend') }
assert archiveIndex >= 0 && archiveIndex < switchIndex
assert success.events.any { it instanceof List && it.any { variable -> variable.toString() == 'NEXT_FRONTEND_IMAGE=axcore-frontend:abcdef12' } }
println 'PASS: 보관 완료 후 frontend 태그·컨테이너 교체'

def failedArchive = harness(true)
try {
    failedArchive.script.deployService('frontend', 'FRONTEND_TAG')
    assert false : '보관 실패가 성공으로 처리됨'
} catch (IllegalStateException expected) {
    assert !failedArchive.events.any { it instanceof String && it.contains('up -d') }
    assert !failedArchive.events.any { it instanceof String && it.contains('sed -i') }
}
println 'PASS: 보관 실패 시 태그·컨테이너 변경 및 불필요한 롤백 없음'

def rollback = harness(false, true)
try {
    rollback.script.deployService('frontend', 'FRONTEND_TAG')
    assert false : '배포 실패가 성공으로 처리됨'
} catch (IllegalStateException expected) {
    assert rollback.events.count { it instanceof String && it.contains('up -d frontend') } == 2
    assert rollback.events.count { it instanceof String && it.contains('retain-frontend-assets.sh') } == 1
    assert rollback.env.FRONTEND_ROLLED_BACK == 'true'
}
println 'PASS: 배포 실패 시 보관 파일을 삭제하지 않고 기존 frontend 롤백'

def otherService = harness()
otherService.script.deployService('ai', 'AI_TAG')
assert !otherService.events.any { it instanceof String && it.contains('retain-frontend-assets.sh') }
println 'PASS: AI 배포는 프론트 보관 절차를 실행하지 않음'
