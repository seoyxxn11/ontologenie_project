import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider, Link } from 'react-router'
import { beforeEach, expect, test, onTestFinished } from 'vitest'
import { resetMappingMocks } from '../../mocks/mappingHandlers'
import { server } from '../../mocks/node'
import SourcesPage from '../sources/SourcesPage'
import type { ObjectMapping } from './api'

const objectPath = '/api/v1/ontology/object-mappings'

beforeEach(resetMappingMocks)

function openSources(path = '/admin/sources?source=1&tab=mapping') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider
        router={createMemoryRouter(
          [
            {
              path: '/admin/sources',
              element: (
                <>
                  <Link to="/explorer">업무 화면으로</Link>
                  <SourcesPage />
                </>
              ),
            },
            {
              path: '/explorer',
              element: (
                <Link to="/admin/sources?source=1&tab=mapping">
                  매핑으로 돌아가기
                </Link>
              ),
            },
          ],
          { initialEntries: ['/explorer', path], initialIndex: 1 },
        )}
      />
    </QueryClientProvider>,
  )
  return userEvent.setup()
}

test('원천 상세에서 객체·속성 매핑을 열고 자원을 바꾸면 이전 상세를 닫는다', async () => {
  const user = openSources('/admin/sources')
  await user.click(await screen.findByRole('button', { name: '원천 매핑' }))
  const objects = await screen.findByRole('table', { name: '객체 매핑 목록' })
  expect(within(objects).getByText('item_code')).toBeInTheDocument()
  expect(within(objects).getByText('초기 설정')).toBeInTheDocument()
  await user.click(within(objects).getByRole('button', { name: '품목 #701' }))
  const properties = await screen.findByRole('table', {
    name: '속성 매핑 목록',
  })
  expect(await within(properties).findByText('품목명')).toBeInTheDocument()
  expect(await within(properties).findByText('item_name')).toBeInTheDocument()
  expect(within(properties).getByText('앞뒤 공백 제거')).toBeInTheDocument()

  await user.selectOptions(
    screen.getByRole('combobox', { name: '원천 자원' }),
    '12',
  )
  expect(
    await screen.findByText('이 원천 자원에 등록된 객체 매핑이 없습니다.'),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('region', { name: '객체 매핑 상세' }),
  ).not.toBeInTheDocument()
})

test('객체 등록에 유형과 키 컬럼이 필요하고 키 컬럼은 선택 순서로 저장한다', async () => {
  const user = openSources()
  await screen.findByRole('table', { name: '객체 매핑 목록' })
  await user.selectOptions(
    screen.getByRole('combobox', { name: '원천 자원' }),
    '12',
  )
  await user.click(screen.getByRole('button', { name: '+ 객체 매핑 등록' }))
  const dialog = within(screen.getByRole('dialog', { name: '객체 매핑 등록' }))
  const code = await dialog.findByRole('checkbox', { name: 'vendor_code' })
  await user.click(dialog.getByRole('button', { name: '저장' }))
  expect(dialog.getByText('객체 유형을 선택해 주세요.')).toBeInTheDocument()
  expect(dialog.getByRole('combobox', { name: '객체 유형' })).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  expect(
    dialog.getByRole('combobox', { name: '객체 유형' }),
  ).toHaveAccessibleDescription('객체 유형을 선택해 주세요.')
  await user.selectOptions(
    dialog.getByRole('combobox', { name: '객체 유형' }),
    '502',
  )
  await user.click(dialog.getByRole('button', { name: '저장' }))
  expect(
    dialog.getByText('현재 존재하는 키 컬럼을 한 개 이상 선택해 주세요.'),
  ).toBeInTheDocument()
  await user.click(dialog.getByRole('checkbox', { name: 'biz_no' }))
  await user.click(code)
  expect(
    dialog.getByText('키 조합(선택 순서): biz_no + vendor_code'),
  ).toBeVisible()
  await user.click(dialog.getByRole('button', { name: '저장' }))
  expect(
    await screen.findByText(/다음 수집에서 원천을 처음부터 다시 읽습니다/),
  ).toBeInTheDocument()
  const table = await screen.findByRole('table', { name: '객체 매핑 목록' })
  expect(within(table).getByText('biz_no + vendor_code')).toBeInTheDocument()
  await user.click(within(table).getByRole('button', { name: /^거래처 #\d+$/ }))
  expect(await screen.findByText(/키 컬럼 biz_no \+ vendor_code/)).toBeVisible()
})

test('속성 등록은 변환 인자를 검증하고 단계 순서대로 저장한다', async () => {
  const user = openSources()
  await user.click(await screen.findByRole('button', { name: '품목 #701' }))
  await screen.findByText('item_name')
  await user.click(screen.getByRole('button', { name: '+ 속성 매핑 등록' }))
  const dialog = within(screen.getByRole('dialog', { name: '속성 매핑 등록' }))
  await user.selectOptions(
    dialog.getByRole('combobox', { name: '원천 컬럼' }),
    '1003',
  )
  await user.selectOptions(
    dialog.getByRole('combobox', { name: '대상 속성' }),
    '602',
  )
  const add = await dialog.findByRole('button', { name: '변환 단계 추가' })
  await user.click(add)
  await user.click(add)
  await user.selectOptions(
    dialog.getByRole('combobox', { name: '변환 단계 2 함수' }),
    'PAD_ZEROS',
  )
  await user.type(dialog.getByRole('textbox', { name: '자릿수' }), '0')
  await user.click(dialog.getByRole('button', { name: '저장' }))
  expect(
    dialog.getByText('0 채우기 자릿수는 1 이상의 정수여야 합니다.'),
  ).toBeInTheDocument()
  const width = dialog.getByRole('textbox', { name: '자릿수' })
  await user.clear(width)
  await user.type(width, '4')
  const secondUp = dialog.getAllByRole('button', { name: '위로' })[1]
  await user.click(secondUp)
  await user.click(dialog.getByRole('button', { name: '저장' }))
  expect(
    await screen.findByText(/다음 수집에서 원천을 처음부터 다시 읽습니다/),
  ).toBeInTheDocument()
  expect(
    await screen.findByText('앞에 0 채우기 (4) → 앞뒤 공백 제거'),
  ).toBeInTheDocument()
})

test('속성 편집은 기존 변환과 대상 값을 복원하고 변경 내용을 저장한다', async () => {
  const user = openSources()
  await user.click(await screen.findByRole('button', { name: '품목 #701' }))
  const edit = await screen.findByRole('button', {
    name: '품목명 속성 매핑 편집',
  })
  await screen.findByText('item_name')
  await user.click(edit)
  const dialog = within(screen.getByRole('dialog', { name: '속성 매핑 편집' }))
  expect(dialog.getByRole('combobox', { name: '원천 컬럼' })).toHaveValue(
    '1002',
  )
  expect(dialog.getByRole('combobox', { name: '대상 속성' })).toHaveValue('601')
  expect(
    await dialog.findByRole('combobox', { name: '변환 단계 1 함수' }),
  ).toHaveValue('TRIM')
  await user.click(dialog.getByRole('button', { name: '변환 삭제' }))
  await user.click(dialog.getByRole('button', { name: '저장' }))
  expect(await screen.findByText('그대로 연결')).toBeInTheDocument()
})

test('이미 매핑된 속성을 등록하면 서버의 중복 오류를 표시하고 입력을 유지한다', async () => {
  const user = openSources()
  await user.click(await screen.findByRole('button', { name: '품목 #701' }))
  await screen.findByText('item_name')
  await user.click(screen.getByRole('button', { name: '+ 속성 매핑 등록' }))
  const dialog = within(screen.getByRole('dialog', { name: '속성 매핑 등록' }))
  await dialog.findByRole('button', { name: '변환 단계 추가' })
  const column = dialog.getByRole('combobox', { name: '원천 컬럼' })
  const property = dialog.getByRole('combobox', { name: '대상 속성' })
  await user.selectOptions(column, '1003')
  await user.selectOptions(property, '601')
  await user.click(dialog.getByRole('button', { name: '저장' }))
  expect(
    await dialog.findByText('이 속성은 이미 매핑돼 있습니다.'),
  ).toBeInTheDocument()
  expect(column).toHaveValue('1003')
  expect(property).toHaveValue('601')
})

test('버전 충돌 뒤 최신 객체 매핑을 다시 열면 최신 rowVersion으로 저장한다', async () => {
  let version = 2
  let active = true
  const mapping = (): ObjectMapping => ({
    id: 701,
    sourceResourceId: 11,
    objectTypeId: 501,
    keyColumns: ['item_code'],
    isActive: active,
    origin: 'MANUAL',
    proposalItemId: null,
    rowFilter: {},
    rowVersion: version,
  })
  const sentVersions: number[] = []
  server.use(
    http.get(objectPath, () => HttpResponse.json({ data: [mapping()] })),
    http.patch(`${objectPath}/701`, async ({ request }) => {
      const body = (await request.json()) as {
        rowVersion: number
        isActive: boolean
      }
      sentVersions.push(body.rowVersion)
      if (version === 2) {
        version = 5
        return HttpResponse.json(
          {
            error: {
              code: 'VERSION_CONFLICT',
              message: '다른 사용자가 매핑을 변경했습니다.',
              details: {},
              requestId: 'mapping-conflict',
            },
          },
          { status: 409 },
        )
      }
      active = body.isActive
      return HttpResponse.json({
        data: { ...mapping(), reloadRequired: false, reloadReason: null },
      })
    }),
  )
  const user = openSources()
  const edit = await screen.findByRole('button', {
    name: '품목 #701 객체 매핑 편집',
  })
  await user.click(edit)
  let dialog = within(screen.getByRole('dialog', { name: '객체 매핑 편집' }))
  await dialog.findByRole('checkbox', { name: 'item_code' })
  expect(dialog.getByRole('combobox', { name: '원천 자원' })).toBeDisabled()
  expect(dialog.getByRole('combobox', { name: '객체 유형' })).toBeDisabled()
  await user.click(dialog.getByRole('button', { name: '저장' }))
  expect(
    await dialog.findByText('다른 사용자가 매핑을 변경했습니다.'),
  ).toBeInTheDocument()
  expect(dialog.getByRole('button', { name: '저장' })).toBeDisabled()
  await user.click(
    dialog.getByRole('button', { name: '최신 매핑 다시 불러오기' }),
  )
  await user.click(
    await screen.findByRole('button', { name: '품목 #701 객체 매핑 편집' }),
  )
  dialog = within(screen.getByRole('dialog', { name: '객체 매핑 편집' }))
  await dialog.findByRole('checkbox', { name: 'item_code' })
  await user.click(dialog.getByRole('checkbox', { name: '활성 매핑' }))
  await user.click(dialog.getByRole('button', { name: '저장' }))
  expect(await screen.findByText('비활성')).toBeInTheDocument()
  expect(sentVersions).toEqual([2, 5])
})

test('객체 목록의 권한 오류를 빈 목록으로 표시하지 않는다', async () => {
  server.use(
    http.get(objectPath, () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '관리자 권한이 필요합니다.',
            details: {},
            requestId: 'mapping-forbidden',
          },
        },
        { status: 403 },
      ),
    ),
  )
  openSources()
  const error = await screen.findByRole('alert')
  expect(error).toHaveTextContent('관리자 권한이 필요합니다.')
  expect(
    within(error).queryByRole('button', { name: '다시 불러오기' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('table', { name: '객체 매핑 목록' }),
  ).not.toBeInTheDocument()
})

test('미저장 객체 매핑은 배경 닫기를 막고 Escape·메뉴 이동의 폐기를 확인한다', async () => {
  const user = openSources()
  await user.click(
    await screen.findByRole('button', { name: '품목 #701 객체 매핑 편집' }),
  )
  let dialog = screen.getByRole('dialog', { name: '객체 매핑 편집' })
  await user.click(within(dialog).getByRole('checkbox', { name: '활성 매핑' }))
  await user.click(dialog.closest('.ui-dialog-overlay')!)
  expect(dialog).toBeVisible()
  await user.keyboard('{Escape}')
  expect(within(dialog).getByText('변경사항을 버릴까요?')).toBeVisible()
  await user.click(within(dialog).getByRole('button', { name: '계속 편집' }))
  expect(
    within(dialog).getByRole('checkbox', { name: '활성 매핑' }),
  ).not.toBeChecked()
  await user.click(screen.getByRole('link', { name: '업무 화면으로' }))
  expect(within(dialog).getByText('변경사항을 버릴까요?')).toBeVisible()
  await user.keyboard('{Escape}')
  dialog = screen.getByRole('dialog', { name: '객체 매핑 편집' })
  expect(
    within(dialog).getByRole('checkbox', { name: '활성 매핑' }),
  ).not.toBeChecked()
  await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  await user.click(
    within(dialog).getByRole('button', { name: '변경사항 버리기' }),
  )
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

test('속성 매핑의 미저장 선택·변환을 유지하며 오류를 필드와 연결한다', async () => {
  const user = openSources()
  await user.click(await screen.findByRole('button', { name: '품목 #701' }))
  await screen.findByText('item_name')
  await user.click(
    await screen.findByRole('button', { name: '+ 속성 매핑 등록' }),
  )
  const dialog = screen.getByRole('dialog', { name: '속성 매핑 등록' })
  await within(dialog).findByRole('button', { name: '변환 단계 추가' })
  await user.click(within(dialog).getByRole('button', { name: '저장' }))
  const column = within(dialog).getByRole('combobox', { name: '원천 컬럼' })
  expect(column).toHaveAttribute('aria-invalid', 'true')
  expect(column).toHaveAccessibleDescription('원천 컬럼을 선택해 주세요.')
  await user.selectOptions(column, '1003')
  await user.keyboard('{Escape}')
  await user.click(within(dialog).getByRole('button', { name: '계속 편집' }))
  expect(
    within(dialog).getByRole('combobox', { name: '원천 컬럼' }),
  ).toHaveValue('1003')
})

function waitForSaveResponse() {
  let release!: () => void
  const promise = new Promise<void>((resolve) => {
    release = resolve
  })
  onTestFinished(release)
  return { promise, release }
}

test('저장 중 객체 매핑 창을 닫고 실패하면 같은 입력과 오류를 다시 확인한다', async () => {
  const response = waitForSaveResponse()
  let calls = 0
  server.use(
    http.patch(`${objectPath}/701`, async () => {
      calls++
      await response.promise
      return HttpResponse.json(
        {
          error: {
            code: 'NETWORK_FAILURE',
            message: '응답을 받지 못했습니다.',
            details: {},
            requestId: 'ui-save-failure',
          },
        },
        { status: 503 },
      )
    }),
  )
  const user = openSources()
  await user.click(
    await screen.findByRole('button', { name: '품목 #701 객체 매핑 편집' }),
  )
  let dialog = screen.getByRole('dialog', { name: '객체 매핑 편집' })
  await within(dialog).findByRole('checkbox', { name: 'item_code' })
  await user.click(within(dialog).getByRole('checkbox', { name: '활성 매핑' }))
  await user.click(within(dialog).getByRole('button', { name: '저장' }))
  await waitFor(() => expect(calls).toBe(1))
  await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '저장 상태 보기' }))
  dialog = screen.getByRole('dialog', { name: '객체 매핑 편집' })
  expect(
    within(dialog).getByRole('button', { name: '저장 중…' }),
  ).toBeDisabled()
  await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  await act(async () => response.release())
  await user.click(
    await screen.findByRole('button', { name: '입력·오류 확인' }),
  )
  dialog = screen.getByRole('dialog', { name: '객체 매핑 편집' })
  expect(
    within(dialog).getByRole('checkbox', { name: '활성 매핑' }),
  ).not.toBeChecked()
  expect(within(dialog).getByRole('alert')).toHaveTextContent(
    '응답을 받지 못했습니다.',
  )
  expect(
    within(screen.getByRole('region', { name: '저장 요청 상태' })).getByRole(
      'alert',
    ),
  ).toHaveTextContent('저장 여부가 불확실합니다.')
  expect(calls).toBe(1)
})

test('저장 중 화면을 이동해도 완료 응답이 화면을 강제로 바꾸지 않고 대상에서 재확인한다', async () => {
  const response = waitForSaveResponse()
  server.use(
    http.patch(`${objectPath}/701`, async () => {
      await response.promise
      return HttpResponse.json({
        data: {
          id: 701,
          sourceResourceId: 11,
          objectTypeId: 501,
          keyColumns: ['item_code'],
          isActive: false,
          origin: 'MANUAL',
          proposalItemId: null,
          rowFilter: {},
          rowVersion: 2,
          reloadRequired: false,
          reloadReason: null,
        },
      })
    }),
  )
  const user = openSources()
  await user.click(
    await screen.findByRole('button', { name: '품목 #701 객체 매핑 편집' }),
  )
  const dialog = screen.getByRole('dialog', { name: '객체 매핑 편집' })
  await within(dialog).findByRole('checkbox', { name: 'item_code' })
  await user.click(within(dialog).getByRole('checkbox', { name: '활성 매핑' }))
  await user.click(within(dialog).getByRole('button', { name: '저장' }))
  await user.click(screen.getByRole('link', { name: '업무 화면으로' }))
  expect(screen.getByRole('link', { name: '매핑으로 돌아가기' })).toBeVisible()
  await act(async () => response.release())
  expect(screen.getByRole('link', { name: '매핑으로 돌아가기' })).toBeVisible()
  await user.click(screen.getByRole('link', { name: '매핑으로 돌아가기' }))
  const status = await screen.findByRole('region', { name: '저장 요청 상태' })
  await waitFor(() =>
    expect(status).toHaveTextContent('저장 응답을 받았습니다.'),
  )
  expect(
    within(status).getByRole('link', { name: '저장 대상 확인' }),
  ).toHaveAttribute('href', '/admin/sources?source=1&tab=mapping')
})

test('속성 매핑 저장 중 창을 접어도 요청이 한 번만 처리되고 완료 상태를 남긴다', async () => {
  const response = waitForSaveResponse()
  let calls = 0
  server.use(
    http.patch('/api/v1/ontology/property-mappings/801', async () => {
      calls++
      await response.promise
      return HttpResponse.json({
        data: {
          id: 801,
          sourceMappingId: 701,
          sourceResourceId: 11,
          sourceColumnId: 1002,
          propertyDefinitionId: 601,
          sourcePath: null,
          transformSteps: [],
          rowVersion: 4,
          reloadRequired: false,
          reloadReason: null,
        },
      })
    }),
  )
  const user = openSources()
  await user.click(await screen.findByRole('button', { name: '품목 #701' }))
  await screen.findByText('item_name')
  await user.click(
    await screen.findByRole('button', { name: '품목명 속성 매핑 편집' }),
  )
  const dialog = screen.getByRole('dialog', { name: '속성 매핑 편집' })
  await user.click(
    await within(dialog).findByRole('button', { name: '변환 삭제' }),
  )
  await user.click(within(dialog).getByRole('button', { name: '저장' }))
  await waitFor(() => expect(calls).toBe(1))
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '저장 상태 보기' })).toBeVisible()
  await act(async () => response.release())
  const status = screen.getByRole('region', { name: '저장 요청 상태' })
  await waitFor(() =>
    expect(status).toHaveTextContent('저장 응답을 받았습니다.'),
  )
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(calls).toBe(1)
})
