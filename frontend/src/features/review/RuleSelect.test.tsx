import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import RuleSelect from './RuleSelect'
const options = [
  { value: '1', name: '설비', code: 'EQUIPMENT' },
  { value: '2', name: '작업지시', code: 'WORK_ORDER' },
]
test('전체 후보를 보여주고 검색 후보를 클릭해 선택한다', async () => {
  const user = userEvent.setup(),
    onChange = vi.fn()
  render(
    <RuleSelect
      label="관계 유형"
      value=""
      options={options}
      onChange={onChange}
    />,
  )
  const input = screen.getByRole('combobox', { name: '관계 유형 검색' })
  await user.click(input)
  expect(
    within(screen.getByRole('listbox')).getAllByRole('option'),
  ).toHaveLength(2)
  await user.type(input, 'work')
  expect(
    within(screen.getByRole('listbox')).getAllByRole('option'),
  ).toHaveLength(1)
  await user.click(
    within(screen.getByRole('listbox')).getByRole('option', {
      name: /작업지시/,
    }),
  )
  expect(onChange).toHaveBeenCalledWith('2')
  expect(input).toHaveValue('')
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
})
test('키보드로 선택하고 Escape와 포커스 이동으로 닫는다', async () => {
  const user = userEvent.setup(),
    onChange = vi.fn()
  render(
    <RuleSelect
      label="출발 속성"
      value=""
      options={options}
      onChange={onChange}
    />,
  )
  const input = screen.getByRole('combobox', { name: '출발 속성 검색' })
  await user.click(input)
  await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
  expect(onChange).toHaveBeenCalledWith('2')
  await user.type(input, '설비')
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  await user.keyboard('{ArrowDown}')
  expect(screen.getByRole('listbox')).toBeInTheDocument()
  await user.tab()
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
})
test('일치하지 않는 검색어로 기존 선택을 바꾸지 않는다', async () => {
  const user = userEvent.setup(),
    onChange = vi.fn()
  render(
    <RuleSelect
      label="도착 속성"
      value="1"
      options={options}
      onChange={onChange}
    />,
  )
  const input = screen.getByRole('combobox', { name: '도착 속성 검색' })
  await user.click(input)
  await user.type(input, '없는값')
  expect(screen.getByRole('status')).toHaveTextContent('검색 결과가 없습니다')
  await user.keyboard('{Enter}')
  expect(onChange).not.toHaveBeenCalled()
  expect(screen.getByLabelText('도착 속성', { exact: true })).toHaveValue('1')
})
