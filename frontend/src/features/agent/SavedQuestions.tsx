import { savedQuestionLabel } from './savedQuestionStore'

type SavedQuestionsProps = {
  questions: string[]
  disabled: boolean
  onPick: (text: string) => void
  onRemove: (text: string) => void
}

// 사용자가 저장한 자주 쓰는 질문(257). 입력란 위에 보이고, 누르면 문장이 입력란에 채워진다.
// 지우기(×)는 답변을 기다리는 중에도 할 수 있다.
function SavedQuestions({
  questions,
  disabled,
  onPick,
  onRemove,
}: SavedQuestionsProps) {
  if (questions.length === 0) return null
  return (
    <div className="agent-question-shortcuts">
      <span className="agent-shortcut-label">내 질문</span>
      <ul aria-label="내 질문">
        {questions.map((question) => (
          <li key={question} className="agent-saved-question">
            {/* 화면에는 줄인 이름을 보이고, 화면 읽기 프로그램에는 앞이 같은 질문끼리 헷갈리지 않게 문장 전체를 읽힌다. */}
            <button
              type="button"
              title={question}
              aria-label={question}
              disabled={disabled}
              onClick={() => onPick(question)}
              className="agent-saved-question-pick"
            >
              {savedQuestionLabel(question)}
            </button>
            <button
              type="button"
              aria-label={`'${question}' 내 질문에서 지우기`}
              title="내 질문에서 지우기"
              onClick={() => onRemove(question)}
              className="agent-saved-question-remove"
            >
              ×
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

export default SavedQuestions
