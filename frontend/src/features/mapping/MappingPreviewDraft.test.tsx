import { createMemoryRouter, RouterProvider } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, expect, test, vi } from 'vitest'
import { resetMappingMocks } from '../../mocks/mappingHandlers'
import { server } from '../../mocks/node'
import MappingPanel from './MappingPanel'
import type { MappingPreviewAccess, MappingPreviewResult } from './previewModel'

beforeEach(resetMappingMocks)
const result = (): MappingPreviewResult => ({
  status: 'SUCCEEDED',
  rows: [],
  rowErrors: [],
})
function service() {
  return {
    policyDescription: '검증용 정책',
    request: vi.fn(async () => ({
      previewId: 91,
      joined: false,
      expiresAt: new Date(Date.now() + 60000).toISOString(),
    })),
    read: vi.fn(async () => result()),
  }
}
function open(access?: MappingPreviewAccess) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RouterProvider
        router={createMemoryRouter([
          {
            path: '*',
            element: <MappingPanel sourceId={1} previewAccess={access} />,
          },
        ])}
      />
    </QueryClientProvider>,
  )
  return userEvent.setup()
}
async function property(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: '품목 #701' }))
  const edit = await screen.findByRole('button', {
    name: '품목명 속성 매핑 편집',
  })
  await waitFor(() => expect(edit).toBeEnabled())
  await user.click(edit)
  return within(screen.getByRole('dialog', { name: '속성 매핑 편집' }))
}

test('실제 화면의 객체·속성 편집에 준비 중 미리보기를 붙인다', async () => {
  const user = open()
  const edit = await screen.findByRole('button', {
    name: '품목 #701 객체 매핑 편집',
  })
  await user.click(edit)
  expect(
    screen.getByRole('region', { name: '변환 미리보기' }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '변환 미리보기 요청' }),
  ).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '닫기' }))
  const dialog = await property(user)
  expect(
    dialog.getByRole('button', { name: '변환 미리보기 요청' }),
  ).toBeDisabled()
})

test('속성 초안을 저장하지 않고 다른 페이지의 매핑까지 포함해 시험 변환한다', async () => {
  const api = service()
  const save = vi.fn(() => HttpResponse.json({ data: {} }))
  server.use(
    http.post('/api/v1/ontology/property-mappings', save),
    http.patch('/api/v1/ontology/property-mappings/:id', save),
  )
  const user = open(api)
  const dialog = await property(user)
  await user.selectOptions(
    await dialog.findByRole('combobox', { name: '변환 단계 1 함수' }),
    'ADD_PREFIX',
  )
  await user.type(dialog.getByRole('textbox', { name: '붙일 접두어' }), 'PRE-')
  server.use(
    http.get('/api/v1/ontology/property-mappings', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      return HttpResponse.json({
        data: page
          ? [
              {
                id: 802,
                sourceMappingId: 701,
                sourceResourceId: 11,
                propertyDefinitionId: 602,
                sourceColumnId: 1003,
                sourcePath: null,
                transformSteps: [],
                rowVersion: 1,
              },
            ]
          : [
              {
                id: 801,
                sourceMappingId: 701,
                sourceResourceId: 11,
                propertyDefinitionId: 601,
                sourceColumnId: 1002,
                sourcePath: null,
                transformSteps: [{ op: 'TRIM', arg: null }],
                rowVersion: 3,
              },
            ],
        meta: { page, size: 20, totalElements: 2, hasNext: page === 0 },
      })
    }),
  )
  await user.click(dialog.getByRole('button', { name: '변환 미리보기 요청' }))
  await dialog.findByText('변환된 객체가 없습니다.')
  expect(api.request).toHaveBeenCalledWith(
    expect.objectContaining({
      sourceResourceId: 11,
      objectTypeId: 501,
      keyColumns: ['item_code'],
      properties: [
        {
          propertyDefinitionId: 602,
          sourceColumnId: 1003,
          sourcePath: null,
          transformSteps: [],
        },
        {
          propertyDefinitionId: 601,
          sourceColumnId: 1002,
          sourcePath: null,
          transformSteps: [{ op: 'ADD_PREFIX', arg: 'PRE-' }],
        },
      ],
    }),
    20,
  )
  expect(save).not.toHaveBeenCalled()
  await user.type(dialog.getByRole('textbox', { name: '붙일 접두어' }), 'NEW')
  expect(dialog.queryByText('변환된 객체가 없습니다.')).not.toBeInTheDocument()
})

test('객체 편집에서 저장 전 키 변경을 전달하고 속성 연결을 유지한다', async () => {
  const api = service()
  const user = open(api)
  await user.click(
    await screen.findByRole('button', { name: '품목 #701 객체 매핑 편집' }),
  )
  const dialog = within(screen.getByRole('dialog', { name: '객체 매핑 편집' }))
  await user.click(await dialog.findByRole('checkbox', { name: 'item_name' }))
  await user.click(dialog.getByRole('button', { name: '변환 미리보기 요청' }))
  await dialog.findByText('변환된 객체가 없습니다.')
  expect(api.request).toHaveBeenCalledWith(
    expect.objectContaining({
      keyColumns: ['item_code', 'item_name'],
      properties: [expect.objectContaining({ propertyDefinitionId: 601 })],
    }),
    20,
  )
})

test('필수 입력·잘못된 변환 인자는 미리보기 접수 전에 거절한다', async () => {
  const api = service()
  const user = open(api)
  await user.click(
    await screen.findByRole('button', { name: '+ 객체 매핑 등록' }),
  )
  await screen.findByRole('checkbox', { name: 'item_code' })
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: '변환 미리보기 요청' }),
    ).toBeEnabled(),
  )
  await user.click(screen.getByRole('button', { name: '변환 미리보기 요청' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '객체 유형을 선택해 주세요.',
  )
  await user.click(screen.getByRole('button', { name: '닫기' }))
  const dialog = await property(user)
  await user.selectOptions(
    await dialog.findByRole('combobox', { name: '변환 단계 1 함수' }),
    'PAD_ZEROS',
  )
  await user.type(dialog.getByRole('textbox', { name: '자릿수' }), '0')
  await user.click(dialog.getByRole('button', { name: '변환 미리보기 요청' }))
  expect(await dialog.findByRole('alert')).toHaveTextContent('1 이상의 정수')
  expect(api.request).not.toHaveBeenCalled()
})

test('전체 속성 초안을 읽지 못하면 일부 매핑만으로 접수하지 않는다', async () => {
  const api = service()
  const user = open(api)
  const dialog = await property(user)
  server.use(
    http.get('/api/v1/ontology/property-mappings', () =>
      HttpResponse.json(
        {
          error: {
            code: 'INTERNAL_ERROR',
            message: 'PRIVATE-ROW-SECRET',
            details: {},
            requestId: 'test-preview',
          },
        },
        { status: 500 },
      ),
    ),
  )
  await user.click(dialog.getByRole('button', { name: '변환 미리보기 요청' }))
  const alert = await dialog.findByRole('alert')
  expect(alert).not.toHaveTextContent('PRIVATE-ROW-SECRET')
  expect(api.request).not.toHaveBeenCalled()
  expect(api.read).not.toHaveBeenCalled()
})

test.each([
  ['object', false],
  ['object', true],
  ['property', false],
  ['property', true],
] as const)(
  '행 수 Enter는 저장하지 않고 명시적 저장만 실행한다 (%s, API %s)',
  async (kind, connected) => {
    const api = service()
    const object = {
      id: 701,
      sourceResourceId: 11,
      objectTypeId: 501,
      keyColumns: ['item_code'],
      rowFilter: {},
      isActive: true,
      rowVersion: 3,
      origin: 'SEED',
      proposalItemId: null,
    }
    const save = vi.fn(() =>
      HttpResponse.json({
        data: { ...object, reloadRequired: false, reloadReason: null },
      }),
    )
    server.use(
      http.patch(
        `/api/v1/ontology/${kind === 'object' ? 'object-mappings/701' : 'property-mappings/801'}`,
        save,
      ),
    )
    const user = open(connected ? api : undefined)
    let dialog
    if (kind === 'object') {
      await user.click(
        await screen.findByRole('button', { name: '품목 #701 객체 매핑 편집' }),
      )
      dialog = within(screen.getByRole('dialog', { name: '객체 매핑 편집' }))
      await dialog.findByRole('checkbox', { name: 'item_code' })
    } else {
      dialog = await property(user)
      await dialog.findByRole('combobox', { name: '변환 단계 1 함수' })
    }
    const limit = dialog.getByRole('spinbutton', {
      name: '미리보기 행 수 (1~20)',
    })
    await user.clear(limit)
    await user.type(limit, '10{Enter}')
    expect(save).not.toHaveBeenCalled()
    expect(api.request).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.click(dialog.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1))
  },
)
