import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import { fetchSyncSchedule, saveSyncSchedule } from './scheduleApi'

test.each([
  null,
  { syncEnabled: true, syncIntervalMinutes: null, rowVersion: 1 },
  { syncEnabled: true, syncIntervalMinutes: 0, rowVersion: 1 },
  { syncEnabled: false, syncIntervalMinutes: null, rowVersion: -1 },
  { syncEnabled: false, syncIntervalMinutes: null, rowVersion: '1' },
])(
  '잘못된 071 응답을 저장 가능한 설정으로 취급하지 않는다 (%j)',
  async (data) => {
    server.use(
      http.get('/api/v1/sources/1/sync-schedule', () =>
        HttpResponse.json({ data }),
      ),
    )
    await expect(fetchSyncSchedule(1)).rejects.toMatchObject({
      kind: 'invalid-response',
    })
  },
)

test('071 응답은 계약의 세 필드만 보관한다', async () => {
  const schedule = { syncEnabled: true, syncIntervalMinutes: 15, rowVersion: 2 }
  server.use(
    http.get('/api/v1/sources/1/sync-schedule', () =>
      HttpResponse.json({ data: { ...schedule, unrelated: 'IGNORE' } }),
    ),
  )
  expect(await fetchSyncSchedule(1)).toEqual(schedule)
})

test('072의 비어 있거나 잘못된 응답은 성공으로 처리하지 않는다', async () => {
  server.use(
    http.put('/api/v1/sources/1/sync-schedule', () =>
      HttpResponse.json({ data: { rowVersion: 2 } }),
    ),
  )
  await expect(
    saveSyncSchedule(1, {
      syncEnabled: false,
      syncIntervalMinutes: null,
      rowVersion: 1,
    }),
  ).rejects.toMatchObject({ kind: 'invalid-response' })
})
