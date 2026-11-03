const DAY_MS = 24 * 60 * 60 * 1000

// 날짜 입력값(2026-10-27)을 그날 0시(한국 시각)의 서버 시각 형식으로 바꾼다.
export function startOfDay(date: string) {
  return new Date(`${date}T00:00:00+09:00`).toISOString()
}

// 종료일은 그날까지 포함해야 하므로, 다음 날 0시를 상한(미포함)으로 보낸다.
export function startOfNextDay(date: string) {
  return new Date(
    new Date(`${date}T00:00:00+09:00`).getTime() + DAY_MS,
  ).toISOString()
}
