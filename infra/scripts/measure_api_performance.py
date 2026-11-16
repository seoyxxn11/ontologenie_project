"""71번: 인증된 조회 API의 단일·동시 5개 요청 시간을 제한된 횟수로 측정한다.

비밀번호는 getpass 또는 로컬 로그인 창으로만 입력한다. 인증 정보는 메모리에만
두며 결과에는 응답 본문·쿠키·인증 헤더·로그인 ID를 기록하지 않는다.
업무 API는 아래 고정된 GET 경로만 호출한다. 새 LLM·Action 실행은 없다.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import getpass
import http.cookiejar
import json
import math
from pathlib import Path
import queue
import statistics
import threading
import time
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.parse import unquote
from urllib.request import (
    HTTPCookieProcessor,
    HTTPRedirectHandler,
    Request,
    build_opener,
)


ORIGIN = "https://ontologenie.com"
SAMPLES = 20
CONCURRENCIES = (1, 5)
MAX_BODY = 2 * 1024 * 1024
TARGETS = (
    ("object_search", "/api/v1/objects?q=P103&page=0&size=20", "list"),
    ("object_detail", "/api/v1/objects/3", "object"),
    ("object_links", "/api/v1/objects/3/links?page=0&size=20", "list"),
    ("ai_proposals", "/api/v1/proposals?origin=AI&page=0&size=20", "list"),
    ("issues", "/api/v1/issues?page=0&size=20", "list"),
    ("analysis_status", "/api/v1/analyses/25", "object"),
    (
        "agent_messages",
        "/api/v1/agent/conversations/146/messages?page=0&size=20",
        "list",
    ),
)


class NoRedirect(HTTPRedirectHandler):
    """인증 정보가 다른 목적지로 전달되지 않도록 redirect를 허용하지 않는다."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def p95(values: list[float]) -> float | None:
    """보간하지 않는 nearest-rank p95. 표본 수를 결과에 함께 기록한다."""
    if not values:
        return None
    if any(not math.isfinite(value) or value < 0 for value in values):
        raise ValueError("Invalid elapsed time")
    return sorted(values)[math.ceil(len(values) * 0.95) - 1]


def validate_body(body: bytes, shape: str) -> int:
    payload = json.loads(body)
    if not isinstance(payload, dict) or "error" in payload:
        raise ValueError("Not a successful API envelope")
    data = payload.get("data")
    if shape == "list" and isinstance(data, list) and data:
        return len(data)
    if shape == "object" and isinstance(data, dict) and data.get("id"):
        return 1
    raise ValueError("Missing demo data or unexpected API response")


class Session:
    def __init__(self) -> None:
        self.jar = http.cookiejar.CookieJar()
        self.opener = build_opener(NoRedirect(), HTTPCookieProcessor(self.jar))

    def login(self, login_id: str, password: str) -> None:
        body = json.dumps({"loginId": login_id, "password": password}).encode()
        request = Request(
            ORIGIN + "/api/v1/auth/login",
            data=body,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        try:
            with self.opener.open(request, timeout=15) as response:
                payload = json.loads(response.read(MAX_BODY + 1))
                if response.status != 200:
                    raise ValueError("Unexpected login status")
            if payload.get("data", {}).get("roleCode") != "ADMIN":
                raise ValueError("This profile requires the demo ADMIN account")
            if not any(cookie.name in {"SESSION", "JSESSIONID"} for cookie in self.jar):
                raise ValueError("Login did not create a session")
        except HTTPError as error:
            status = error.code
            error.close()
            raise ValueError(f"Login rejected (HTTP {status})") from None
        except (URLError, TimeoutError, OSError):
            raise ValueError("Login connection failed") from None

    def measure(self, path: str, shape: str) -> dict:
        if (path, shape) not in {(row[1], row[2]) for row in TARGETS}:
            raise ValueError("Path outside the read-only profile")
        # CookieJar를 여러 스레드에서 갱신하지 않는다. 발급받은 세션만 복사해 쓴다.
        request = Request(ORIGIN + path, headers={"Accept": "application/json"})
        self.jar.add_cookie_header(request)
        opener = build_opener(NoRedirect())
        start = time.perf_counter()
        result = {"started_at": utc_now(), "ok": False, "http_status": None}
        try:
            with opener.open(request, timeout=15) as response:
                body = response.read(MAX_BODY + 1)
                result["http_status"] = response.status
            result["elapsed_ms"] = (time.perf_counter() - start) * 1000
            result["bytes"] = len(body)
            if len(body) > MAX_BODY:
                raise ValueError("Response too large")
            result["rows"] = validate_body(body, shape)
            result["ok"] = result["http_status"] == 200
        except HTTPError as error:
            result["http_status"] = error.code
            result["error"] = "http_error"
            error.close()
        except (URLError, TimeoutError, OSError):
            result["error"] = "transport_error"
        except (ValueError, TypeError):
            result["error"] = "invalid_response"
        if "elapsed_ms" not in result:
            result["elapsed_ms"] = (time.perf_counter() - start) * 1000
        return result

    def logout(self) -> bool:
        token = next(
            (cookie.value for cookie in self.jar if cookie.name == "XSRF-TOKEN"),
            None,
        )
        try:
            if not token:
                return False
            request = Request(
                ORIGIN + "/api/v1/auth/logout",
                data=b"",
                headers={"X-XSRF-TOKEN": unquote(token)},
                method="POST",
            )
            with self.opener.open(request, timeout=15) as response:
                return response.status == 204
        except (HTTPError, URLError, TimeoutError, OSError):
            return False
        finally:
            self.jar.clear()


def summarize(records: list[dict], expected: int) -> dict:
    values = [row["elapsed_ms"] for row in records if row["ok"]]
    return {
        "expected": expected,
        "attempts": len(records),
        "successes": len(values),
        "failures": len(records) - len(values),
        "complete": len(records) == len(values) == expected,
        "success_mean_ms": statistics.mean(values) if values else None,
        "success_p95_ms": p95(values),
        "nfr_target_ms": None,
        "nfr_result": "UNDEFINED_TARGET",
    }


def run_case(
    measure: Callable[[str, str], dict],
    path: str,
    shape: str,
    concurrency: int,
    deadline: float | None = None,
) -> dict:
    if concurrency not in CONCURRENCIES:
        raise ValueError("Concurrency must be 1 or 5")
    records = []
    # 정해진 개수씩 동시 시작하며 실패하면 다음 묶음은 보내지 않는다.
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        for wave in range(SAMPLES // concurrency):
            if deadline is not None and time.monotonic() >= deadline:
                break
            gate = threading.Barrier(concurrency)

            def one(index: int) -> dict:
                gate.wait(timeout=15)
                return {"index": index, "wave": wave, **measure(path, shape)}

            futures = [
                executor.submit(one, len(records) + offset)
                for offset in range(concurrency)
            ]
            records.extend(future.result() for future in futures)
            if any(not row["ok"] for row in records[-concurrency:]):
                break
    return {"concurrency": concurrency, "samples": records, **summarize(records, SAMPLES)}


def save_report(output: Path, report: dict) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    temporary.replace(output)


def run_profile(
    session: Session, output: Path, progress: Callable[[str], None]
) -> dict:
    report = {
        "issue": "S15P31C106-71",
        "origin": ORIGIN,
        "started_at": utc_now(),
        "status": "RUNNING",
        "scope": "authenticated GET; includes DNS/TCP/TLS/body; new connection per request",
        "samples_per_case": SAMPLES,
        "concurrencies": CONCURRENCIES,
        "percentile_method": "nearest-rank; successful responses only; errors separate",
        "nfr_source": "Notion plan v1.0 draft, section 13: numeric target undefined",
        "probes": [],
        "cases": [],
    }
    try:
        # 전 경로의 정상 응답·비어 있지 않은 데이터를 확인한 뒤 본 측정을 시작한다.
        for name, path, shape in TARGETS:
            probe = {"target": name, **session.measure(path, shape)}
            report["probes"].append(probe)
            save_report(output, report)
            if not probe["ok"]:
                report["status"] = "ABORTED_PREFLIGHT"
                return report
        deadline = time.monotonic() + 300
        for name, path, shape in TARGETS:
            for concurrency in CONCURRENCIES:
                if time.monotonic() >= deadline:
                    report["status"] = "ABORTED_DEADLINE"
                    return report
                progress(f"{name}: concurrency {concurrency}, {SAMPLES} samples")
                case = {
                    "target": name,
                    "method": "GET",
                    "path": path,
                    **run_case(session.measure, path, shape, concurrency, deadline),
                }
                report["cases"].append(case)
                save_report(output, report)
                if not case["complete"]:
                    report["status"] = (
                        "ABORTED_REQUEST_FAILURE" if case["failures"]
                        else "ABORTED_DEADLINE"
                    )
                    return report
        report["status"] = "COMPLETED"
        return report
    except Exception as error:
        report["status"] = "ABORTED_EXCEPTION"
        report["error_type"] = type(error).__name__
        raise
    finally:
        report["finished_at"] = utc_now()
        report["logout_succeeded"] = session.logout()
        save_report(output, report)


def login_window(output: Path) -> None:
    import tkinter as tk
    from tkinter import ttk

    window = tk.Tk()
    window.title("Jira 71 - OntoloGenie API 측정 로그인")
    frame = ttk.Frame(window, padding=20)
    frame.grid()
    ttk.Label(
        frame,
        text=(
            "대상: https://ontologenie.com\n"
            "기존 시연 데이터 조회 API 7개 · 단일/동시 5개 · 각 20회\n"
            "사전 확인 7회 + 본 측정 최대 280회. 새 LLM/업무 변경 없음.\n"
            "비밀번호·세션은 저장하지 않습니다. 완료 후 측정 세션을 로그아웃합니다."
        ),
    ).grid(row=0, column=0, columnspan=2, pady=10)
    ttk.Label(frame, text="시연 관리자 ID").grid(row=1, column=0, sticky="w")
    login_id = ttk.Entry(frame, width=35)
    login_id.grid(row=1, column=1, pady=5)
    ttk.Label(frame, text="비밀번호").grid(row=2, column=0, sticky="w")
    password = ttk.Entry(frame, width=35, show="*")
    password.grid(row=2, column=1, pady=5)
    status = tk.StringVar(value="직접 로그인 정보를 입력하고 시작을 눌러주세요.")
    ttk.Label(frame, textvariable=status, wraplength=540).grid(
        row=4, column=0, columnspan=2, pady=10
    )
    events = queue.Queue()

    def start() -> None:
        identity, secret = login_id.get().strip(), password.get()
        if not identity or not secret:
            status.set("ID와 비밀번호를 입력해주세요.")
            return
        password.delete(0, tk.END)
        button.configure(state="disabled")
        login_id.configure(state="disabled")
        status.set("인증 및 데이터 확인 중입니다.")

        def work() -> None:
            session = Session()
            try:
                session.login(identity, secret)
                report = run_profile(session, output, lambda text: events.put(text))
                events.put(f"{report['status']} · 결과: {output.resolve()}")
            except Exception as error:
                session.logout()
                # 예외 원문에는 요청 객체·비밀값이 들어갈 수 있어 표시하지 않는다.
                events.put(f"실행 중단 ({type(error).__name__}). 인증·연결을 확인해주세요.")
            finally:
                events.put(None)

        threading.Thread(target=work, daemon=False).start()

    button = ttk.Button(frame, text="로그인 및 조회 측정 시작", command=start)
    button.grid(row=3, column=0, columnspan=2, pady=10)

    def update() -> None:
        try:
            while True:
                event = events.get_nowait()
                if event is None:
                    if not output.exists():
                        button.configure(state="normal")
                        login_id.configure(state="normal")
                else:
                    status.set(event)
        except queue.Empty:
            pass
        window.after(200, update)

    window.after(200, update)
    login_id.focus_set()
    window.mainloop()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--login-window", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output exists; choose a new file to preserve prior measurements")
    if args.login_window:
        login_window(args.output)
        return
    session = Session()
    try:
        session.login(input("Demo ADMIN login ID: "), getpass.getpass("Password: "))
        report = run_profile(session, args.output, print)
        print(report["status"])
    finally:
        session.logout()


if __name__ == "__main__":
    main()
