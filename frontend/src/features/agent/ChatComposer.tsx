import {
  useLayoutEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from 'react'
import AgentIconButton from './AgentIconButton'
import { useAuth } from '../auth/auth-context'
import SavedQuestions from './SavedQuestions'
import { MAX_SAVED_QUESTIONS, useSavedQuestions } from './savedQuestionStore'
import SuggestedQuestions from './SuggestedQuestions'
import { suggestedQuestions } from './suggestions'
import type { ReferenceObject } from './types'

type ChatComposerProps = {
  disabled: boolean
  showSuggestions?: boolean
  initialDraft?: string
  draftValue?: string
  onDraftChange?: (draft: string) => void
  focusRequest?: number
  // 첫 질문 전까지의 참고 객체. 추천 질문을 그 객체에 맞춘다. 없으면 null(150).
  reference?: ReferenceObject | null
  onSend: (content: string) => Promise<void>
}

function resizeInput(input: HTMLTextAreaElement) {
  input.style.height = 'auto'
  input.style.height = `${Math.min(160, Math.max(40, input.scrollHeight))}px`
  input.style.overflowY = input.scrollHeight > 160 ? 'auto' : 'hidden'
}

function ChatComposer({
  disabled,
  showSuggestions = true,
  initialDraft = '',
  draftValue,
  onDraftChange,
  focusRequest = 0,
  reference = null,
  onSend,
}: ChatComposerProps) {
  const { account } = useAuth()
  const [localDraft, setLocalDraft] = useState(initialDraft)
  const draft = draftValue ?? localDraft
  function setDraft(next: string) {
    setLocalDraft(next)
    onDraftChange?.(next)
  }
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const content = draft.trim()
  // 자주 쓰는 질문(257). 이 브라우저에 계정별로 보관한다.
  const saved = useSavedQuestions(account?.id ?? null)
  const alreadySaved = saved.questions.includes(content)
  useLayoutEffect(() => {
    onDraftChange?.(draft)
    const input = inputRef.current
    if (input) resizeInput(input)
  }, [draft, onDraftChange])
  useLayoutEffect(() => {
    const input = inputRef.current
    if (!input) return
    let previousWidth = input.clientWidth
    const observer = new ResizeObserver(() => {
      if (input.clientWidth === previousWidth) return
      previousWidth = input.clientWidth
      resizeInput(input)
    })
    observer.observe(input)
    return () => observer.disconnect()
  }, [])
  useLayoutEffect(() => {
    if (focusRequest > 0) inputRef.current?.focus()
  }, [focusRequest])

  async function send() {
    if (!content || disabled) return
    try {
      await onSend(content)
      setLocalDraft('')
    } catch {
      // 실패하면 쓴 질문을 그대로 두고, 오류는 대화 화면에서 보여 준다.
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void send()
  }

  // Enter는 보내기, Shift+Enter는 줄바꿈이다. 한글을 조합하는 중에 누른 Enter는 글자를 끝내는 키라 보내지 않는다.
  // (조합 중이면 isComposing이 참이고, 일부 브라우저는 keyCode 229로만 알려 준다.)
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (
      event.key !== 'Enter' ||
      event.shiftKey ||
      event.nativeEvent.isComposing ||
      event.keyCode === 229
    ) {
      return
    }
    event.preventDefault()
    void send()
  }

  // 추천 질문을 고르면 입력란에 채우고 바로 고쳐 쓸 수 있게 입력란으로 옮긴다.
  function handlePick(text: string) {
    setDraft(text)
    inputRef.current?.focus()
  }

  return (
    <form onSubmit={handleSubmit} className="agent-composer">
      <div className="agent-composer-inner">
        {showSuggestions && (
          <SuggestedQuestions
            questions={suggestedQuestions(reference, account?.roleCode ?? null)}
            disabled={disabled}
            onPick={handlePick}
          />
        )}
        <SavedQuestions
          questions={saved.questions}
          disabled={disabled}
          onPick={handlePick}
          onRemove={saved.remove}
        />
        {reference && (
          <p className="agent-reference">
            {reference.typeName} · {reference.name}
          </p>
        )}
        <div className="agent-composer-label">
          <label htmlFor="agent-input">업무 질문</label>
        </div>
        <div className="agent-input-box">
          <textarea
            id="agent-input"
            aria-label="업무 질문"
            aria-describedby="agent-input-help"
            ref={inputRef}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={handleKeyDown}
            disabled={disabled}
            maxLength={2000}
            rows={1}
            placeholder="업무에 대해 질문해 주세요."
            className="agent-question-input"
          />
          <div className="agent-composer-footer">
            <div className="agent-composer-actions">
              {/* 지금 입력한 문장을 내 질문으로 저장한다. 로그인 전이거나, 비었거나, 이미 저장했거나, 가득 찼으면 누를 수 없다.
            저장하면 버튼이 막히므로 다시 입력란으로 돌아가 이어서 쓸 수 있게 한다. 별 기호는 화면 읽기 프로그램이 읽지 않는다. */}
              <AgentIconButton
                icon="star"
                className="agent-save-question"
                aria-pressed={alreadySaved}
                onClick={() => {
                  saved.save(content)
                  inputRef.current?.focus()
                }}
                disabled={
                  !saved.canSave || !content || alreadySaved || saved.isFull
                }
                label={
                  alreadySaved
                    ? saved.storageFailed
                      ? '임시 보관 중'
                      : '저장됨'
                    : '내 질문 저장'
                }
                title={
                  saved.isFull
                    ? `내 질문은 ${MAX_SAVED_QUESTIONS}개까지 저장할 수 있습니다. 하나를 지운 뒤 저장하세요.`
                    : '지금 입력한 질문을 이 브라우저에 계정별로 저장합니다.'
                }
              />
              <AgentIconButton
                icon="send"
                label="보내기"
                className="agent-send-question"
                type="submit"
                pending={disabled}
                disabled={!content}
              />
            </div>
          </div>
        </div>
        <p id="agent-input-help">Enter로 보내기 · Shift+Enter로 줄바꿈</p>
        {saved.storageFailed && (
          <p role="status" className="agent-storage-notice">
            {saved.capacityConflict
              ? `다른 탭의 내 질문과 합치면 ${MAX_SAVED_QUESTIONS}개를 넘습니다. 기존 질문을 더 지운 뒤 저장해 주세요. `
              : '브라우저에 저장하지 못했습니다. '}
            내 질문의 변경 내용은 현재 화면에서만 유지되며, 새로고침하면
            사라지거나 이전 내용으로 돌아갑니다.
          </p>
        )}
      </div>
    </form>
  )
}

export default ChatComposer
