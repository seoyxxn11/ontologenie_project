import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import Pagination from './Pagination'

describe('페이지네이션', () => {
  it('현재 주변 5개 번호와 처음·이전·다음·마지막을 0 기반 콜백으로 연결한다', async () => {
    const user = userEvent.setup()
    const change = vi.fn()
    render(
      <Pagination page={4} size={20} totalElements={200} onChange={change} />,
    )
    const nav = within(screen.getByRole('navigation', { name: '페이지 이동' }))
    expect(
      nav.getAllByRole('button').map((button) => button.textContent),
    ).toEqual(['처음', '이전', '3', '4', '5', '6', '7', '다음', '마지막'])
    const current = nav.getByRole('button', { name: '5페이지로 이동' })
    expect(current).toHaveAttribute('aria-current', 'page')
    expect(current).toBeDisabled()
    await user.click(current)
    expect(change).not.toHaveBeenCalled()
    await user.click(nav.getByRole('button', { name: '첫 페이지' }))
    await user.click(nav.getByRole('button', { name: '이전 페이지' }))
    await user.click(nav.getByRole('button', { name: '3페이지로 이동' }))
    await user.click(nav.getByRole('button', { name: '다음 페이지' }))
    await user.click(nav.getByRole('button', { name: '마지막 페이지' }))
    expect(change.mock.calls.map(([page]) => page)).toEqual([0, 3, 2, 5, 9])
  })

  it.each([0, 9])(
    '경계 페이지 %i에서 번호 범위를 채우고 범위 밖 이동을 막는다',
    (page) => {
      render(
        <Pagination
          page={page}
          size={20}
          totalElements={200}
          onChange={vi.fn()}
        />,
      )
      const nav = within(screen.getByRole('navigation'))
      const numbers = nav
        .getAllByRole('button')
        .filter(
          (button) =>
            button.hasAttribute('aria-current') ||
            /페이지로 이동/.test(button.getAttribute('aria-label') ?? ''),
        )
      expect(numbers.map((button) => button.textContent)).toEqual(
        page === 0 ? ['1', '2', '3', '4', '5'] : ['6', '7', '8', '9', '10'],
      )
      expect(
        nav.getByRole('button', {
          name: page === 0 ? '첫 페이지' : '마지막 페이지',
        }),
      ).toBeDisabled()
      expect(
        nav.getByRole('button', {
          name: page === 0 ? '이전 페이지' : '다음 페이지',
        }),
      ).toBeDisabled()
    },
  )

  it('빈 결과와 잘못된 페이지 크기에는 이동 버튼을 만들지 않는다', () => {
    const { rerender } = render(
      <Pagination page={0} size={20} totalElements={0} onChange={vi.fn()} />,
    )
    expect(screen.getByText('0건')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    rerender(
      <Pagination page={0} size={0} totalElements={100} onChange={vi.fn()} />,
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it.each([1, 3, 20])(
    '한 페이지인 %i건이면 건수만 표시한다',
    (totalElements) => {
      render(
        <Pagination
          page={0}
          size={20}
          totalElements={totalElements}
          onChange={vi.fn()}
        />,
      )
      expect(screen.getByText(`총 ${totalElements}건`)).toBeInTheDocument()
      expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
    },
  )

  it.each([0, 3, 20])(
    '요약을 생략한 %i건의 한 페이지 이하 목록은 아무것도 표시하지 않는다',
    (totalElements) => {
      const { container } = render(
        <Pagination
          page={0}
          size={20}
          totalElements={totalElements}
          showSummary={false}
          compact
          onChange={vi.fn()}
        />,
      )
      expect(container).toBeEmptyDOMElement()
    },
  )

  it('한 페이지에서 두 페이지가 되면 이동 버튼을 표시한다', () => {
    const { rerender } = render(
      <Pagination page={0} size={20} totalElements={20} onChange={vi.fn()} />,
    )
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    rerender(
      <Pagination page={0} size={20} totalElements={21} onChange={vi.fn()} />,
    )
    expect(
      screen.getByRole('navigation', { name: '페이지 이동' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeEnabled()
  })

  it('조회 중에는 번호와 모든 이동 버튼을 눌러도 재조회하지 않는다', async () => {
    const change = vi.fn()
    const user = userEvent.setup()
    render(
      <Pagination
        page={4}
        size={20}
        totalElements={200}
        pending
        onChange={change}
      />,
    )
    expect(screen.getByRole('navigation')).toHaveAttribute('aria-busy', 'true')
    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeDisabled()
      await user.click(button)
    }
    expect(change).not.toHaveBeenCalled()
  })

  it('결과 수가 줄면 실제 조회 페이지도 유효한 마지막 페이지로 이동한다', async () => {
    const change = vi.fn()
    const user = userEvent.setup()
    const { rerender } = render(
      <Pagination page={9} size={20} totalElements={200} onChange={change} />,
    )
    rerender(
      <Pagination page={9} size={20} totalElements={25} onChange={change} />,
    )
    expect(screen.getByText('2 / 2쪽 · 25건')).toBeInTheDocument()
    expect(change).toHaveBeenCalledWith(1)
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '이전 페이지' }))
    expect(change).toHaveBeenCalledWith(0)
  })

  it('마지막 페이지가 사라지거나 결과가 0건이 되면 첫 페이지로 복구한다', () => {
    const change = vi.fn()
    const { rerender } = render(
      <Pagination page={1} size={20} totalElements={21} onChange={change} />,
    )
    rerender(
      <Pagination page={1} size={20} totalElements={20} onChange={change} />,
    )
    expect(change).toHaveBeenCalledWith(0)
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    change.mockClear()
    rerender(
      <Pagination page={1} size={20} totalElements={0} onChange={change} />,
    )
    expect(change).toHaveBeenCalledWith(0)
    expect(screen.getByText('0건')).toBeInTheDocument()
  })

  it('조회 중에는 페이지 보정을 미루고 완료된 결과로 이동한다', () => {
    const change = vi.fn()
    const { rerender } = render(
      <Pagination
        page={1}
        size={20}
        totalElements={20}
        pending
        onChange={change}
      />,
    )
    expect(change).not.toHaveBeenCalled()
    rerender(
      <Pagination page={1} size={20} totalElements={20} onChange={change} />,
    )
    expect(change).toHaveBeenCalledWith(0)
  })
})
