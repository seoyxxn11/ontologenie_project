import copy
import http.client
import json
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Protocol

from app.config import Settings

# GMS 모델별 토큰당 크레딧 (입력, 출력). luna·astra는 GMS 안내 값, sol은 등급 비율(luna 1 · sol 20)로 낸 추정값이다.
# 기본 모델 sol: astra는 결과가 같고 크레딧만 5~8배였다(docs/evaluation.md 모델 선정).
MODEL_CREDITS: dict[str, tuple[float, float]] = {
    "gpt-6-luna": (0.001, 0.005),
    "gpt-6.1-sol": (0.02, 0.1),
    "gpt-6-astra": (0.1, 0.5),
}
INPUT_CREDIT, OUTPUT_CREDIT = MODEL_CREDITS["gpt-6-luna"]


def token_credits(
    model: str | None, input_tokens: float, output_tokens: float
) -> float:
    """토큰 수를 크레딧으로 바꾼다. 표에 없는 모델은 아는 모델 중 가장 비싼 값으로 넉넉히 센다."""
    rate_in, rate_out = MODEL_CREDITS.get(
        model or "", max(MODEL_CREDITS.values(), key=lambda rate: rate[1])
    )
    return input_tokens * rate_in + output_tokens * rate_out


# (url, headers, body, timeout) -> 응답 JSON. 테스트에서는 가짜 함수를 넣어 실제 호출 없이 확인한다.
Sender = Callable[[str, dict, dict, float], dict]

INSTRUCTIONS = "반드시 JSON 객체 하나로만 답한다."


class LLMClient(Protocol):
    def complete_json(self, prompt: str) -> dict: ...


class LLMError(RuntimeError):
    pass


class MockLLMClient:
    """실제 API를 호출하지 않고 미리 정한 응답을 돌려준다. 비용 없이 파이프라인을 테스트하기 위한 용도."""

    # 사용량 계산을 확인하는 테스트는 luna 단가로 센다(가짜 응답이라 실제 비용은 없음)
    model = "gpt-6-luna"

    def __init__(self, response=None):
        # response: 고정 응답(dict) 또는 프롬프트를 받아 응답을 만드는 함수
        self._response = response if response is not None else {"candidates": []}
        self.prompts: list[str] = []

    def complete_json(self, prompt: str) -> dict:
        self.prompts.append(prompt)
        if callable(self._response):
            return copy.deepcopy(self._response(prompt))
        return copy.deepcopy(self._response)


class _HttpLLMClient:
    """OpenAI 호환 API 공통 부분. 제공사마다 주소 뒤 경로, 요청 본문, 응답 읽는 법만 다르다."""

    path = ""

    def __init__(
        self,
        api_key: str | None,
        base_url: str,
        model: str,
        timeout_sec: float = 60.0,
        send: Sender | None = None,
        name: str = "LLM",
    ):
        self._api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout_sec = timeout_sec
        self.name = name
        self._send = send or _post_json
        # {"input_tokens", "output_tokens", "reasoning_tokens"} — 제공사와 상관없이 같은 이름으로 남긴다(사용량 기록용)
        self.last_usage: dict | None = None

    def complete_json(self, prompt: str) -> dict:
        headers = {"Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        try:
            data = self._send(
                f"{self.base_url}{self.path}",
                headers,
                self._body(prompt),
                self.timeout_sec,
            )
        except LLMError as error:
            raise LLMError(f"{self.name} {error}") from error
        if not isinstance(data, dict) or data.get("error"):
            raise LLMError(f"{self.name} 응답 오류: {str(data)[:300]}")
        self.last_usage = self._usage(data.get("usage") or {})
        text = self._text(data)
        try:
            return json.loads(_strip_fence(text))
        except json.JSONDecodeError as error:
            raise LLMError(
                f"{self.name}가 JSON이 아닌 답을 보냈습니다: {text[:300]}"
            ) from error

    def _body(self, prompt: str) -> dict:
        raise NotImplementedError

    def _usage(self, usage: dict) -> dict:
        raise NotImplementedError

    def _text(self, data: dict) -> str:
        raise NotImplementedError


class ResponsesLLMClient(_HttpLLMClient):
    """OpenAI Responses API(/responses). GMS, OpenAI, Azure OpenAI(v1 주소)가 이 형식이다."""

    path = "/responses"

    def _body(self, prompt: str) -> dict:
        return {
            "model": self.model,
            "instructions": INSTRUCTIONS,
            "input": prompt,
            "text": {"format": {"type": "json_object"}},
        }

    def _usage(self, usage: dict) -> dict:
        return {
            # 값이 null로 오는 제공사가 있어 0으로 센다
            "input_tokens": usage.get("input_tokens") or 0,
            "output_tokens": usage.get("output_tokens") or 0,
            "reasoning_tokens": (usage.get("output_tokens_details") or {}).get(
                "reasoning_tokens"
            ),
        }

    def _text(self, data: dict) -> str:
        parts = [
            content.get("text", "")
            for item in data.get("output", [])
            if item.get("type") == "message"
            for content in item.get("content", [])
            if content.get("type") == "output_text"
        ]
        if not parts:
            raise LLMError(
                f"{self.name} 응답에 글자가 없습니다 (status={data.get('status')}): {str(data)[:300]}"
            )
        return "".join(parts)


class ChatLLMClient(_HttpLLMClient):
    """OpenAI Chat Completions(/chat/completions). 사내 LLM 서버(vLLM·Ollama 등)와 다른 호환 API가 이 형식이다."""

    path = "/chat/completions"

    def _body(self, prompt: str) -> dict:
        return {
            "model": self.model,
            "messages": [
                {"role": "system", "content": INSTRUCTIONS},
                {"role": "user", "content": prompt},
            ],
            "response_format": {"type": "json_object"},
        }

    def _usage(self, usage: dict) -> dict:
        return {
            "input_tokens": usage.get("prompt_tokens") or 0,
            "output_tokens": usage.get("completion_tokens") or 0,
            "reasoning_tokens": (usage.get("completion_tokens_details") or {}).get(
                "reasoning_tokens"
            ),
        }

    def _text(self, data: dict) -> str:
        choices = data.get("choices") or []
        text = (choices[0].get("message") or {}).get("content") if choices else None
        if not text:
            raise LLMError(f"{self.name} 응답에 글자가 없습니다: {str(data)[:300]}")
        return text


def _strip_fence(text: str) -> str:
    """JSON 형식 지정을 무시하는 모델이 ```json ... ``` 로 감싸 보내는 경우를 벗긴다."""
    stripped = text.strip()
    if len(stripped) < 6 or not (
        stripped.startswith("```") and stripped.endswith("```")
    ):
        return text
    return stripped[3:-3].removeprefix("json").strip()


def _post_json(url: str, headers: dict, body: dict, timeout: float) -> dict:
    request = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raise LLMError(
            f"요청 실패 (HTTP {error.code}): {_error_detail(error)}"
        ) from error
    except urllib.error.URLError as error:
        raise LLMError(f"접속하지 못했습니다: {error.reason}") from error
    except (TimeoutError, OSError, http.client.HTTPException) as error:
        # 응답을 기다리다 시간이 지나면 URLError가 아니라 TimeoutError가, 응답이 중간에 끊기면
        # IncompleteRead 등이 난다. 표 하나만 건너뛰게 LLMError로 바꾼다
        raise LLMError(f"응답이 없습니다: {type(error).__name__}") from error
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise LLMError("JSON이 아닌 응답을 받았습니다") from error


def _error_detail(error: urllib.error.HTTPError) -> str:
    """오류 응답 본문 앞부분. 본문을 읽다 시간 초과 등으로 실패해도 HTTP 상태는 남긴다."""
    try:
        return error.read().decode("utf-8", errors="replace")[:300]
    except (OSError, http.client.HTTPException) as read_error:
        return f"본문을 읽지 못함({type(read_error).__name__})"


# provider: (클라이언트, 오류 메시지에 쓸 이름). 기본 주소·모델과 키 필수 여부는 config.py에 있다.
PROVIDERS: dict[str, tuple[type[_HttpLLMClient], str]] = {
    "gms": (ResponsesLLMClient, "GMS"),
    "openai": (ResponsesLLMClient, "OpenAI"),
    "openai-chat": (ChatLLMClient, "LLM 서버"),
}


def create_llm_client(settings: Settings) -> LLMClient:
    if settings.llm_provider == "mock":
        return MockLLMClient()
    if settings.llm_provider == "none":
        raise LLMError("LLM을 쓰지 않는 설정입니다 (AI_ONTOLOGY_LLM_PROVIDER=none)")
    if settings.llm_provider not in PROVIDERS:
        raise ValueError(
            f"지원하지 않는 LLM provider입니다: {settings.llm_provider} "
            f"({', '.join([*PROVIDERS, 'mock', 'none'])})"
        )
    client_class, name = PROVIDERS[settings.llm_provider]
    missing = settings.llm_missing
    if missing:
        raise LLMError(f"{name} 설정이 없습니다: {missing}")
    return client_class(
        settings.llm_api_key,
        settings.llm_base_url,
        settings.llm_model,
        settings.llm_timeout_sec,
        name=name,
    )
