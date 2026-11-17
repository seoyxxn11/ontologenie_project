import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState, type FormEvent } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/errors'
import Button from './Button'
import DataTable from './DataTable'
import Dialog from './Dialog'
import { EmptyState, ErrorState, LoadingState } from './Feedback'
import StatusBadge from './StatusBadge'

describe('공통 화면 상태', () => {
  it('로딩과 빈 결과를 서로 다른 안내로 표시한다', () => {
    const { rerender } = render(<LoadingState message="목록을 불러오는 중…" />)
    expect(screen.getByRole('status')).toHaveTextContent('목록을 불러오는 중…')
    rerender(<EmptyState message="등록된 원천이 없습니다." />)
    expect(screen.getByRole('status')).toHaveTextContent(
      '등록된 원천이 없습니다.',
    )
    expect(screen.queryByText('목록을 불러오는 중…')).not.toBeInTheDocument()
  })

  it('조회 실패의 명시적인 재시도만 실행한다', async () => {
    const retry = vi.fn()
    render(
      <ErrorState
        error={new ApiError('연결 실패', { kind: 'network' })}
        onRetry={retry}
      />,
    )
    expect(retry).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
    expect(retry).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['unauthenticated', 401, '로그인이 필요합니다.'],
    ['forbidden', 403, '요청이 허용되지 않았습니다.'],
    ['conflict', 409, '최신 상태를 확인해 주세요.'],
  ] as const)('%s는 안내하고 자동 재요청하지 않는다', (kind, status, title) => {
    const retry = vi.fn()
    render(
      <ErrorState
        error={new ApiError('서버 안내', { kind, status })}
        onRetry={retry}
      />,
    )
    expect(screen.getByRole('alert')).toHaveTextContent(title)
    expect(screen.getByRole('alert')).toHaveTextContent('서버 안내')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(retry).not.toHaveBeenCalled()
  })

  it('입력 오류와 요청 식별자를 표시하고 상세 내부 데이터는 출력하지 않는다', () => {
    render(
      <ErrorState
        error={
          new ApiError('입력 확인', {
            kind: 'validation',
            status: 400,
            details: {
              errors: [{ field: 'name', reason: '필수입니다.' }],
              internal: '출력 금지',
            },
            requestId: 'test-request',
          })
        }
      />,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('name: 필수입니다.')
    expect(screen.getByRole('alert')).toHaveTextContent('요청 ID: test-request')
    expect(screen.queryByText('출력 금지')).not.toBeInTheDocument()
  })

  it('처리 중인 버튼은 중복 클릭과 폼 제출을 막는다', async () => {
    const submit = vi.fn((event: FormEvent) => event.preventDefault())
    const click = vi.fn()
    render(
      <form onSubmit={submit}>
        <Button type="submit" pending onClick={click}>
          저장 중…
        </Button>
      </form>,
    )
    const button = screen.getByRole('button', { name: '저장 중…' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    await userEvent.click(button)
    expect(click).not.toHaveBeenCalled()
    expect(submit).not.toHaveBeenCalled()
  })

  it('공통 버튼의 기본 동작은 폼 제출이 아니다', async () => {
    const submit = vi.fn((event: FormEvent) => event.preventDefault())
    render(
      <form onSubmit={submit}>
        <Button>취소</Button>
      </form>,
    )
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(submit).not.toHaveBeenCalled()
  })

  it('표의 접근 가능한 이름과 상태의 텍스트를 유지한다', () => {
    render(
      <DataTable label="실행 목록">
        <thead>
          <tr>
            <th scope="col">결과</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>
              <StatusBadge tone="success">성공</StatusBadge>
            </td>
          </tr>
        </tbody>
      </DataTable>,
    )
    expect(
      within(screen.getByRole('table', { name: '실행 목록' })).getByRole(
        'cell',
      ),
    ).toHaveTextContent('성공')
  })
})

function DialogExample() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button onClick={() => setOpen(true)}>근거 보기</Button>
      {open && (
        <Dialog title="연결 근거" onClose={() => setOpen(false)}>
          <input aria-label="메모" />
          <Button onClick={() => setOpen(false)}>확인</Button>
        </Dialog>
      )}
    </>
  )
}

describe('공통 모달', () => {
  it('제목 옆 표시는 창 이름에 넣지 않고 제목 줄에 함께 보여준다', () => {
    render(
      <Dialog
        title="데이터 수집"
        titleAddon={<span>성공</span>}
        onClose={() => {}}
      >
        내용
      </Dialog>,
    )
    const dialog = screen.getByRole('dialog', { name: '데이터 수집' })
    expect(dialog.querySelector('h2')?.parentElement).toHaveTextContent(
      '데이터 수집성공',
    )
  })

  it('포커스를 모달 안에 두고 탭 순환·Esc 종료·호출 버튼 복귀를 지원한다', async () => {
    const user = userEvent.setup()
    const focus = vi.spyOn(HTMLElement.prototype, 'focus')
    render(<DialogExample />)
    const trigger = screen.getByRole('button', { name: '근거 보기' })
    await user.click(trigger)
    expect(screen.getByRole('dialog', { name: '연결 근거' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: '닫기' })).toHaveFocus()
    await user.tab({ shift: true })
    expect(screen.getByRole('button', { name: '확인' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: '닫기' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    expect(focus).toHaveBeenCalledWith({ preventScroll: true })
    focus.mockRestore()
  })

  it('내용 클릭으로 닫히지 않고 배경 클릭으로 닫히며 스크롤을 복구한다', async () => {
    const user = userEvent.setup()
    const overflow = document.body.style.overflow
    render(<DialogExample />)
    await user.click(screen.getByRole('button', { name: '근거 보기' }))
    const dialog = screen.getByRole('dialog')
    expect(document.body.style.overflow).toBe('hidden')
    await user.click(screen.getByRole('textbox', { name: '메모' }))
    expect(dialog).toBeInTheDocument()
    fireEvent.click(dialog.parentElement!)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(document.body.style.overflow).toBe(overflow)
  })
})
