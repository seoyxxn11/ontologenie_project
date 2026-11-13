import { afterEach, describe, expect, it, vi } from 'vitest'
import { formatKstDateTime } from './dateTime'

afterEach(() => vi.unstubAllEnvs())

describe('KST 시각 표시', () => {
  it.each(['UTC', 'America/Los_Angeles', 'Asia/Seoul'])(
    '기기 시간대 %s와 관계없이 한국 시각을 표시한다',
    (zone) => {
      vi.stubEnv('TZ', zone)
      expect(formatKstDateTime('2026-11-12T15:00:00Z')).toBe(
        '2026-11-13 00:00:00 KST',
      )
      expect(formatKstDateTime('2026-11-12T07:00:00-08:00')).toBe(
        '2026-11-13 00:00:00 KST',
      )
      expect(formatKstDateTime(new Date('2026-11-13T00:01:02Z'))).toBe(
        '2026-11-13 09:01:02 KST',
      )
    },
  )
  it('날짜 전용 값은 시각으로 변환하지 않는다', () => {
    expect(formatKstDateTime('2026-11-13')).toBe('2026-11-13')
  })
  it.each([null, undefined, '', 'invalid'])(
    '미제공·잘못된 값 %s는 미제공 표시로 구분한다',
    (value) => {
      expect(formatKstDateTime(value)).toBe('—')
    },
  )
})
