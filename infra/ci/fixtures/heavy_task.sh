
        set +x
        set -eu
        umask 077
        LOCK_DIR="${JENKINS_HOME:-/var/jenkins_home}/ci-locks"
        mkdir -p "$LOCK_DIR"
        exec 9>"$LOCK_DIR/heavy.lock"
        echo 'CI 실행 슬롯 대기 (최대 35분)' >&2
        flock -w 2100 9 || { echo 'CI 실행 슬롯 대기 35분 초과' >&2; exit 1; }
        ACTIVE_PID=''
        cleanup_heavy() {
            status=$?
            trap - EXIT
            if [ -n "$ACTIVE_PID" ]; then
                kill -TERM "$ACTIVE_PID" 2>/dev/null || true
                wait "$ACTIVE_PID" 2>/dev/null || true
            fi
            exit "$status"
        }
        trap cleanup_heavy EXIT
        trap 'exit 130' INT
        trap 'exit 143' TERM
        timeout -k 30s 30m sh -eu -c "$HEAVY_COMMAND" <&0 &
        ACTIVE_PID=$!
        if wait "$ACTIVE_PID"; then result=0; else result=$?; fi
        ACTIVE_PID=''
        exit "$result"
