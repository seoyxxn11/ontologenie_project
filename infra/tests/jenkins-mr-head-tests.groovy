// 실제 Jenkinsfile의 MR 보호 함수와 단계 호출을 실행한다. 네트워크·Jenkins·Docker는 대체한다.
def source = new File('Jenkinsfile').getText('UTF-8')
def sha = 'a' * 40
def newerSha = 'b' * 40

def harness = { Map options = [:] ->
    def stages = [:]
    def posts = [:]
    def commands = []
    def statuses = []
    def bindings = []
    def environment = [JOB_NAME: 'axcore-mr-check', gitlabActionType: 'MERGE',
        gitlabMergeRequestIid: '299', gitlabMergeRequestLastCommit: sha,
        IS_DEPLOY_JOB: 'false', IS_SONAR_JOB: 'false', CHECK_BRANCH: 'develop',
        CHECK_BACKEND: 'true', CHECK_FRONTEND: 'true', CHECK_AI: 'true', CHECK_INFRA: 'true']
    environment.putAll(options.env ?: [:])
    def build = [:]
    def pipelineScript = new GroovyShell().parse(source)
    pipelineScript.binding = new Binding([
        env: environment, params: [RUN_SONARQUBE: false], currentBuild: build,
        pipeline: { Closure body ->
            body.delegate = new Object() {
                def getAny() { 'any' }
                def agent(Object ignored) {}
                def options(Closure ignored) {}
                def parameters(Closure ignored) {}
                def environment(Closure ignored) {}
                def stages(Closure list) { list() }
                def stage(String name, Closure stageBody) { stages[name] = stageBody }
                def post(Closure postBody) {
                    postBody.delegate = new Object() {
                        def success(Closure action) { posts.success = action }
                        def failure(Closure action) { posts.failure = action }
                        def aborted(Closure action) { posts.aborted = action }
                    }
                    postBody.resolveStrategy = Closure.DELEGATE_FIRST
                    postBody()
                }
            }
            body.resolveStrategy = Closure.DELEGATE_FIRST
            body()
        },
        script: { Closure body -> body() },
        dir: { String path, Closure body -> body() },
        checkout: { Map ignored -> }, fileExists: { String path -> true },
        echo: { Object ignored -> },
        error: { Object message -> throw new IllegalStateException(message.toString()) },
        withEnv: { List values, Closure body ->
            def previous = new LinkedHashMap(environment)
            values.each { String value ->
                def parts = value.split('=', 2)
                environment[parts[0]] = parts[1]
            }
            try { body() } finally { environment.clear(); environment.putAll(previous) }
        },
        timeout: { Map args, Closure body ->
            assert args == [time: 30, unit: 'SECONDS']
            body()
        },
        gitUsernamePassword: { Map args ->
            bindings << args
            args
        },
        withCredentials: { List ignored, Closure body -> body() },
        updateGitlabCommitStatus: { Map args -> statuses << args.state },
        sh: { Object input ->
            def command = input instanceof Map ? input.script.toString() : input.toString()
            commands << command
            if (command == 'git rev-parse HEAD') return options.head ?: sha
            if (command == 'git rev-parse --short HEAD') return sha.take(7)
            if (command.contains('ls-remote')) {
                assert environment.MR_HEAD_REF == 'refs/merge-requests/299/head'
                if (options.remoteError) throw new IllegalStateException('remote unavailable')
                return options.containsKey('response') ? options.response : "${sha}\t${environment.MR_HEAD_REF}\n"
            }
            if (command.startsWith('git diff --quiet ') && options.containsKey('changedPaths')) {
                return options.changedPaths.any { command.endsWith(" -- ${it}") } ? 1 : 0
            }
            if (input instanceof Map && input.returnStatus) return 1
            return ''
        }
    ])
    pipelineScript.metaClass.runComponentChecks = { String component -> commands << "component-check:${component}".toString() }
    pipelineScript.metaClass.notifyMattermost = { String ignored -> }
    pipelineScript.run()
    [script: pipelineScript, env: environment, build: build, commands: commands,
        stages: stages, posts: posts, statuses: statuses, bindings: bindings]
}

def fails = { Closure operation ->
    try { operation() } catch (IllegalStateException expected) { return expected.message }
    assert false : '중단해야 하는 검사가 계속됐습니다.'
}

def runStage = { Map test, String name ->
    def allowed = true
    def body = test.stages[name].rehydrate(new Object() {
        def when(Closure condition) {
            condition.delegate = new Object() {
                def expression(Closure predicate) { allowed = predicate() as boolean }
            }
            condition.resolveStrategy = Closure.DELEGATE_FIRST
            condition()
        }
        def steps(Closure action) { if (allowed) action() }
        def post(Closure ignored) {}
        def options(Closure ignored) {}
    }, test.script, test.script)
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
}

def latest = harness()
latest.script.ensureLatestMrCommit()
assert !latest.build.result
assert latest.bindings == [[credentialsId: 'gitlab-clone', gitToolName: 'Default']]
assert latest.commands.count { it.contains('ls-remote') } == 1
println 'PASS: MR 최신 SHA를 원격 조회하고 기존 읽기 자격 증명으로 검사 계속'

def stale = harness(response: "${newerSha}\trefs/merge-requests/299/head\n")
assert fails { stale.script.ensureLatestMrCommit() }.contains('검사를 취소')
assert stale.build.result == 'ABORTED'
stale.posts.aborted()
assert stale.statuses == ['canceled']
println 'PASS: 오래된 MR SHA는 ABORTED로 중단하고 GitLab 취소 상태 전달'

[
    [JOB_NAME: 'axcore-deploy', IS_DEPLOY_JOB: 'true'],
    [JOB_NAME: 'axcore-sonarqube', IS_SONAR_JOB: 'true'],
    [gitlabActionType: null, gitlabMergeRequestIid: null]
].each { Map excluded ->
    def test = harness(env: excluded, remoteError: true)
    test.script.ensureLatestMrCommit()
    runStage(test, 'Verify MR head')
    assert test.commands.isEmpty() && test.bindings.isEmpty()
}
println 'PASS: 배포·SonarQube·수동 검사는 조회와 취소 대상에서 제외'

[null, '', '0', '299; echo invalid'].each { String iid ->
    def test = harness(env: [gitlabMergeRequestIid: iid])
    assert fails { test.script.ensureLatestMrCommit() }.contains('MR 번호')
    assert test.commands.isEmpty() && !test.build.result
}
println 'PASS: MR 번호 누락·잘못된 값은 원격 호출 전에 실패'

['', "${sha}\trefs/heads/develop", 'not-a-sha refs/merge-requests/299/head',
 "${sha}\trefs/merge-requests/299/head\n${newerSha}\trefs/merge-requests/300/head"].each { String response ->
    def test = harness(response: response)
    assert fails { test.script.ensureLatestMrCommit() }.contains('원격 MR head 응답')
    assert !test.build.result
}
def unavailable = harness(remoteError: true)
assert fails { unavailable.script.ensureLatestMrCommit() } == 'remote unavailable'
assert !unavailable.build.result
def invalidHead = harness(head: 'invalid-sha')
assert fails { invalidHead.script.ensureLatestMrCommit() }.contains('검사 중인 커밋 SHA')
assert invalidHead.bindings.isEmpty()
println 'PASS: 빈 응답·다른 ref·잘못된 SHA·통신 오류를 최신으로 오인하지 않음'

['Checkout', 'Backend check', 'Frontend check', 'AI check', 'Infra check'].each { String stage ->
    def test = harness(response: "${newerSha}\trefs/merge-requests/299/head\n")
    fails { runStage(test, stage) }
    assert test.build.result == 'ABORTED'
    assert !test.commands.any { it.startsWith('component-check:') || it.contains('./gradlew') || it.contains('npm run') ||
        it.contains('ruff check') || it.contains('bash -n') }
    assert !test.statuses.contains('success')
}
println 'PASS: Checkout 및 각 검사 시작에서 오래된 MR의 검사 명령 실행 차단'

def changedDuringCheck = harness()
runStage(changedDuringCheck, 'Frontend check')
assert changedDuringCheck.commands.contains('component-check:frontend')
// 이벤트 값은 그대로인 상태에서 원격 응답만 바꿔 검사 중 새 Push를 재현한다.
changedDuringCheck.script.binding.setVariable('sh', { Map input ->
    input.script == 'git rev-parse HEAD' ? sha : "${newerSha}\trefs/merge-requests/299/head\n"
})
fails { runStage(changedDuringCheck, 'Verify MR head') }
assert changedDuringCheck.build.result == 'ABORTED'
changedDuringCheck.posts.aborted()
assert changedDuringCheck.statuses == ['canceled']
println 'PASS: 검사 도중 새 커밋이 생기면 마지막 단계에서 성공 표시 차단'

runStage(latest, 'Verify MR head')
latest.posts.success()
assert latest.statuses == ['success']
println 'PASS: 마지막에도 최신 SHA이면 기존 GitLab 성공 표시 유지'

println '8 MR head guard scenarios passed (mocked Jenkins steps; no live pipeline).'

def sharedCases = harness(changedPaths: ['ai/', 'ai/tests/fixtures/transform_cases.json'])
runStage(sharedCases, 'Checkout')
assert sharedCases.env.CHECK_BACKEND == 'true'
assert sharedCases.env.CHECK_AI == 'true'
assert sharedCases.env.CHECK_FRONTEND == 'false'
def aiOnly = harness(changedPaths: ['ai/'])
runStage(aiOnly, 'Checkout')
assert aiOnly.env.CHECK_BACKEND == 'false'
assert aiOnly.env.CHECK_AI == 'true'
println 'PASS: 공유 변환 사례 변경은 backend 검사도 선택하고 일반 AI 변경은 기존 선택 유지'
