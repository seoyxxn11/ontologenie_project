import { apiGet, apiPatch, apiPost } from '../../api/client'
import { ApiError } from '../../api/errors'
import type {
  ConnectionCheck,
  ConnectionCheckRequest,
  Source,
  SourceColumn,
  SourceCreateRequest,
  SourceDetail,
  SourceResource,
  SourceUpdateRequest,
} from './types'

// 009 원천 목록 조회
export async function fetchSources() {
  const body = await apiGet<Source[]>('/sources?page=0&size=100')
  return body.data
}

// 011 원천 상세 조회
export async function fetchSource(sourceId: number) {
  const body = await apiGet<SourceDetail>(`/sources/${sourceId}`)
  return body.data
}

function requireData<T>(body: { data: T } | undefined): T {
  if (!body) {
    throw new ApiError('저장 응답을 확인할 수 없어요.', {
      kind: 'invalid-response',
    })
  }
  return body.data
}

// 010 원천 등록. 비밀번호는 요청에만 담기고 응답에는 없다.
export async function createSource(request: SourceCreateRequest) {
  return requireData(await apiPost<Source>('/sources', request))
}

// 012 원천 정보 변경
export async function updateSource(
  sourceId: number,
  request: SourceUpdateRequest,
) {
  return requireData(await apiPatch<Source>(`/sources/${sourceId}`, request))
}

// 013 DB 접속 및 읽기 범위 검사 요청. 배치가 실행하고 결과는 100으로 본다.
export async function requestConnectionCheck(sourceId: number) {
  const body = await apiPost<ConnectionCheckRequest>(
    `/sources/${sourceId}/test-connection`,
  )
  if (!body) {
    throw new ApiError('연결 검사 접수 응답을 확인할 수 없어요.', {
      kind: 'invalid-response',
    })
  }
  return body.data
}

// 100 원천 연결 검사 결과 조회
export async function fetchConnectionCheck(sourceId: number, checkId: number) {
  const body = await apiGet<ConnectionCheck>(
    `/sources/${sourceId}/connection-checks/${checkId}`,
  )
  return body.data
}

// 014 원천 표·뷰·자료 목록 (한 원천의 표는 수십 개라 한 번에 받는다)
export async function fetchResources(sourceId: number) {
  const body = await apiGet<SourceResource[]>(
    `/sources/${sourceId}/resources?page=0&size=200`,
  )
  return body.data
}

// 015 자원의 컬럼 조회
export async function fetchColumns(sourceId: number, resourceId: number) {
  const body = await apiGet<SourceColumn[]>(
    `/sources/${sourceId}/resources/${resourceId}/columns`,
  )
  return body.data
}
