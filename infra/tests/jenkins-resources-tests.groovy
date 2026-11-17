// Jenkins 단계만 대체해 실행 제한·실패 전파와 이미지 빌드의 공통 슬롯 사용을 확인한다.
def source = new File('Jenkinsfile').getText('UTF-8')
def pipeline = new GroovyShell().parse(source)
def calls = []
pipeline.binding = new Binding([
    timeout: { Map options, Closure body ->
        assert options == [time: 70, unit: 'MINUTES']
        calls << 'timeout'
        body()
    },
    withEnv: { List values, Closure body ->
        calls << values
        body()
    },
    sh: { Map options ->
        assert options.script == pipeline.heavyTaskScript()
        calls << options.returnStdout
        'ERROR'
    }
])
assert pipeline.runHeavyTask('scanner command', true) == 'ERROR'
assert calls == ['timeout', ['HEAVY_COMMAND=scanner command'], true]

pipeline.binding.sh = { Map options -> throw new IllegalStateException('execution timed out') }
boolean failed = false
try {
    pipeline.runHeavyTask('failed command')
} catch (IllegalStateException expected) {
    failed = true
}
assert failed : '실패·시간 초과를 성공으로 바꾸면 안 됩니다.'

def builds = []
pipeline.binding.env = [GIT_SHA: 'abcdef1']
pipeline.metaClass.runHeavyTask = { String command -> builds << command }
pipeline.buildOnce('axcore-backend', 'backend')
assert builds.size() == 1
assert builds[0].contains('docker image inspect axcore-backend:abcdef1')
assert builds[0].contains('docker build -t axcore-backend:abcdef1 backend')
println 'PASS: 무거운 실행의 Jenkins timeout·출력 전달·실패 전파와 이미지 빌드 슬롯 유지'
