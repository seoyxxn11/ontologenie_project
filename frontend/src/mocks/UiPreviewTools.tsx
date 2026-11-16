import { useState } from 'react'
import EvidenceDialog from '../features/explorer/EvidenceDialog'
import { setMockAccount } from './auth'
import {
  uiPreviewAccount,
  uiPreviewCases,
  uiPreviewObject,
  type UiPreviewCase,
} from './uiPreviewData'
import './uiPreview.css'

export default function UiPreviewTools({
  selected,
}: {
  selected: UiPreviewCase
}) {
  const params = new URLSearchParams(location.search)
  const [motion, setMotion] = useState(params.get('qaMotion') === 'on')
  const [largeText, setLargeText] = useState(params.get('qaText') === 'large')
  const [evidence, setEvidence] = useState(false)
  function changeMotion(next: boolean) {
    document.documentElement.dataset.uiMotionPreview = next ? 'on' : 'off'
    const url = new URL(location.href)
    url.searchParams.set('uiQa', selected)
    url.searchParams.set('qaMotion', next ? 'on' : 'off')
    history.replaceState(history.state, '', url)
    setMotion(next)
  }
  return (
    <>
      {evidence && (
        <EvidenceDialog
          object={uiPreviewObject(selected)}
          onClose={() => setEvidence(false)}
        />
      )}
      <aside className="ui-preview-tools" aria-label="로컬 UI 비교 도구">
        <label htmlFor="ui-preview-data">데이터</label>
        <select
          id="ui-preview-data"
          value={selected}
          onChange={(event) => {
            const url = new URL(location.href)
            url.searchParams.set('uiQa', event.target.value)
            url.searchParams.set('qaMotion', motion ? 'on' : 'off')
            location.href = url.href
          }}
        >
          {Object.entries(uiPreviewCases).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <button
          type="button"
          aria-pressed={!motion}
          onClick={() => changeMotion(false)}
        >
          원본
        </button>
        <button
          type="button"
          aria-pressed={motion}
          onClick={() => changeMotion(true)}
        >
          적용 모션
        </button>
        <button type="button" onClick={() => setEvidence(true)}>
          근거 팝업
        </button>
        <button
          type="button"
          aria-pressed={largeText}
          title="기본 글자 크기를 2배로 설정하는 접근성 검사입니다."
          onClick={() => {
            const next = !largeText
            document.documentElement.style.fontSize = next ? '200%' : ''
            const url = new URL(location.href)
            url.searchParams.set('uiQa', selected)
            url.searchParams.set('qaText', next ? 'large' : 'normal')
            history.replaceState(history.state, '', url)
            setLargeText(next)
          }}
        >
          글자 2배
        </button>
        <span title="팝업·메뉴를 닫았다가 마우스로 다시 열어 비교하세요. 닫힘은 기존 동작입니다.">
          열림 효과만 · 저장 차단
        </span>
        <a
          href="/audit"
          onClick={() => setMockAccount(uiPreviewAccount('demo'))}
        >
          비교 종료
        </a>
      </aside>
    </>
  )
}
