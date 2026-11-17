// 실제 Jenkinsfile의 단계 조건·비동기 전달 함수를 실행한다. Jenkins와 Docker에는 접근하지 않는다.
def source = new File('Jenkinsfile').getText('UTF-8')
def stages = [:]
def environment = [JOB_NAME: 'axcore-deploy', IS_DEPLOY_JOB: 'true', IS_SONAR_JOB: 'false',
    ENABLE_SONARQUBE: 'true', SONARQUBE_READY: 'true', DEPLOY_BACKEND: 'true',
    DEPLOY_FRONTEND: 'false', DEPLOY_AI: 'false', CHECK_REF: 'origin/develop']
def parameters = [RUN_SONARQUBE: false, ANALYSIS_RECIPIENT: 'onteam']
def invoked = []
def script = new GroovyShell().parse(source)
script.binding = new Binding([
    env: environment, params: parameters, currentBuild: [currentResult: 'SUCCESS'],
    pipeline: { Closure body ->
        body.delegate = new Object() {
            def agent(Object ignored) {}
            def options(Closure ignored) {}
            def parameters(Closure ignored) {}
            def environment(Closure ignored) {}
            def post(Closure ignored) {}
            def stages(Closure list) { list() }
            def stage(String name, Closure stageBody) { stages[name] = stageBody }
            def getAny() { 'any' }
        }
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
    },
    sh: { Map args -> 'a' * 40 },
    string: { Map args -> args }, booleanParam: { Map args -> args },
    build: { Map args -> invoked << args },
    echo: { Object ignored -> }
])
script.run()

def allowed = { String name ->
    def result = false
    def body = stages[name].rehydrate(new Object() {
        def when(Closure condition) {
            condition.delegate = new Object() {
                def expression(Closure predicate) { result = predicate() }
            }
            condition.resolveStrategy = Closure.DELEGATE_FIRST
            condition()
        }
        def steps(Closure ignored) {}
        def post(Closure ignored) {}
        def options(Closure ignored) {}
    }, script, script)
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    result
}

assert !allowed('SonarQube analysis')
assert allowed('Queue SonarQube analysis')
script.binding.currentBuild.currentResult = 'FAILURE'
assert !allowed('Queue SonarQube analysis')
script.binding.currentBuild.currentResult = 'SUCCESS'
println 'PASS: 배포 Job에서는 분석을 실행하지 않고 성공한 배포만 분석을 예약'

environment.gitlabActionType = 'PUSH'
environment.gitlabSourceBranch = 'develop'
environment.gitlabUserUsername = 'onteam'
environment.gitlabAfter = 'a' * 40
script.queueSonarAnalysis()
assert invoked.size() == 1
assert invoked[0].job == 'axcore-sonarqube'
assert !invoked[0].wait && !invoked[0].propagate
def forwarded = invoked[0].parameters.collectEntries { [(it.name): it.value] }
assert forwarded.ANALYSIS_COMMIT == 'a' * 40
assert forwarded.ANALYSIS_RECIPIENT == 'onteam'
assert forwarded.ANALYZE_BACKEND && !forwarded.ANALYZE_FRONTEND && !forwarded.ANALYZE_AI
parameters.RUN_SONARQUBE = true
environment.gitlabAfter = 'b' * 40
script.queueSonarAnalysis()
def allServices = invoked[1].parameters.collectEntries { [(it.name): it.value] }
assert allServices.ANALYZE_BACKEND && allServices.ANALYZE_FRONTEND && allServices.ANALYZE_AI
assert allServices.ANALYSIS_COMMIT == 'a' * 40
assert allServices.ANALYSIS_RECIPIENT == ''
parameters.RUN_SONARQUBE = false
println 'PASS: 전체 커밋·대상 서비스·알림 수신자를 비동기 Job에 고정 전달'

environment.JOB_NAME = 'axcore-sonarqube'
environment.IS_DEPLOY_JOB = 'false'
environment.IS_SONAR_JOB = 'true'
environment.CHECK_REF = 'a' * 40
environment.CHECK_BACKEND = 'true'
assert allowed('SonarQube analysis')
assert !allowed('Backend check')
assert !allowed('Prepare deployment credentials')
assert !allowed('Deploy configuration')
assert !allowed('Queue SonarQube analysis')
assert script.sonarGateRecipient() == 'onteam'
parameters.ANALYSIS_RECIPIENT = 'invalid/recipient'
assert script.sonarGateRecipient() == null
println 'PASS: 분석 Job은 MR 검사·운영 설정 반영·재귀 트리거 없이 실행하고 수신자 검증'

environment.JOB_NAME = 'axcore-mr-check'
environment.IS_SONAR_JOB = 'false'
environment.CHECK_REF = 'origin/develop'
environment.gitlabActionType = null
parameters.RUN_SONARQUBE = true
assert allowed('SonarQube analysis')
environment.gitlabActionType = 'MERGE'
assert !allowed('SonarQube analysis')
println 'PASS: MR Job의 develop 수동 전체 분석만 유지하고 일반 MR 분석은 실행하지 않음'

def checked = []
script.metaClass.runComponentChecks = { String component -> checked << component }
environment.IS_SONAR_JOB = 'false'
['backend', 'frontend', 'ai'].each { script.prepareSonarResults(it) }
assert checked.isEmpty()
environment.IS_SONAR_JOB = 'true'
['backend', 'frontend', 'ai'].each { script.prepareSonarResults(it) }
assert checked == ['backend', 'frontend', 'ai']
println 'PASS: SonarQube만 공통 전체 검사·결과 재사용 경로를 호출'

// 실제 공통 함수가 검사 대상과 검사 절차를 쉘에 그대로 전달하는지 확인한다.
def runner = new GroovyShell().parse(source)
def bindings = []
def commands = []
runner.binding = new Binding([
    timeout: { Map options, Closure body -> assert options == [time: 70, unit: 'MINUTES']; body() },
    withEnv: { List values, Closure body -> bindings << values; body() },
    sh: { String command -> commands << command },
    error: { Object message -> throw new IllegalStateException(message.toString()) }
])
['backend', 'frontend', 'ai'].each { runner.runComponentChecks(it) }
assert bindings.collect { it[0] } == ['CHECK_COMPONENT=backend', 'CHECK_COMPONENT=frontend', 'CHECK_COMPONENT=ai']
assert bindings.every { it[1] == "CHECK_POLICY_BODY=${runner.componentCheckScript()}" }
assert commands.every { it == runner.componentCheckScript() }
assert commands[0].contains('flock -w 2100 9')
assert commands[0].contains('spotlessCheck test --rerun build jacocoTestReport')
assert commands[0].contains('npm run format:check && npm run lint && npm run test:coverage && npm run build')
assert commands[0].contains('ruff check . && pytest -q --junitxml=test-results/pytest.xml --cov --cov-report=xml')
println 'PASS: 세 서비스의 기존 전체 검사와 실패 판정을 유지'

checked.clear()
// 최신 SHA 보호는 jenkins-mr-head-tests.groovy에서 별도로 검증한다.
script.metaClass.ensureLatestMrCommit = { -> }
environment.IS_SONAR_JOB = 'false'
environment.IS_DEPLOY_JOB = 'false'
environment.CHECK_FRONTEND = 'true'
environment.CHECK_AI = 'true'
script.binding.setVariable('script', { Closure body -> body() })
['Backend check', 'Frontend check', 'AI check'].each { stageName ->
    def enabled = false
    def stageBody = stages[stageName].rehydrate(new Object() {
        def when(Closure condition) {
            condition.delegate = new Object() {
                def expression(Closure predicate) { enabled = predicate() }
            }
            condition.resolveStrategy = Closure.DELEGATE_FIRST
            condition()
        }
        def steps(Closure action) { if (enabled) action() }
        def post(Closure ignored) {}
    }, script, script)
    stageBody.resolveStrategy = Closure.DELEGATE_FIRST
    stageBody()
}
assert checked == ['backend', 'frontend', 'ai']
println 'PASS: 실제 MR 세 서비스 단계도 같은 전체 검사·결과 재사용 함수를 호출'
