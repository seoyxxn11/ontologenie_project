import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { useAuth } from '../auth/auth-context'
import type { Account } from '../auth/types'
import {
  createAccount,
  fetchAccountEditor,
  fetchRoles,
  statusLabels,
  temporaryPasswordError,
  updateAccount,
  type AccountChanges,
  type Role,
} from './api'

function RolePermissions({ roles, code }: { roles: Role[]; code: string }) {
  const selected = roles.find((role) => role.code === code)
  return selected ? (
    <section className="rounded-lg bg-slate-50 p-4 text-sm">
      <h2 className="font-semibold">{selected.name} 권한</h2>
      {selected.permissions.length ? (
        <ul className="mt-2 flex flex-wrap gap-2">
          {selected.permissions.map((permission) => (
            <li key={permission} className="rounded border bg-white px-2 py-1">
              {permission}
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-2">등록된 권한 코드가 없습니다.</p>
      )}
    </section>
  ) : null
}

function AccountForm({
  original,
  roles,
  onReload,
}: {
  original?: Account
  roles: Role[]
  onReload: () => void
}) {
  const { account: current, refresh } = useAuth()
  const client = useQueryClient()
  const [loginId, setLoginId] = useState(original?.loginId ?? '')
  const [displayName, setDisplayName] = useState(original?.displayName ?? '')
  const [roleCode, setRoleCode] = useState<Account['roleCode'] | ''>(
    original?.roleCode ??
      (roles.some((role) => role.code === 'VIEWER') ? 'VIEWER' : ''),
  )
  const [status, setStatus] = useState<Account['status']>(
    original?.status ?? 'ACTIVE',
  )
  const [acknowledged, setAcknowledged] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [result, setResult] = useState<Account | null>(null)
  const submitting = useRef(false)
  const password = useRef<HTMLInputElement>(null)
  const confirmation = useRef<HTMLInputElement>(null)
  const blocked =
    original &&
    error instanceof ApiError &&
    [401, 403, 404, 409].includes(error.status ?? 0)
  const selfChange =
    original?.id === current?.id &&
    original !== undefined &&
    (roleCode !== original.roleCode ||
      (status !== original.status && status !== 'ACTIVE'))
  async function submit() {
    if (submitting.current || blocked || result) return
    const id = loginId.trim()
    const name = displayName.trim()
    if (
      !id ||
      !name ||
      !roleCode ||
      !roles.some((role) => role.code === roleCode)
    ) {
      setError(new Error('로그인 ID·이름·역할을 확인해 주세요.'))
      return
    }
    if (selfChange && !acknowledged) {
      setError(new Error('본인 계정의 세션 종료 안내를 확인해 주세요.'))
      return
    }
    let changes: AccountChanges | undefined
    if (original) {
      changes = { rowVersion: original.rowVersion }
      if (name !== original.displayName) changes.displayName = name
      if (roleCode !== original.roleCode) changes.roleCode = roleCode
      if (status !== original.status) changes.status = status
      if (Object.keys(changes).length === 1) {
        setError(new Error('변경할 내용을 입력해 주세요.'))
        return
      }
    } else {
      const value = password.current?.value ?? ''
      const violation = temporaryPasswordError(id, value)
      if (violation) {
        setError(new Error(violation))
        return
      }
      if (value !== confirmation.current?.value) {
        setError(new Error('임시 비밀번호 확인이 일치하지 않습니다.'))
        return
      }
    }
    submitting.current = true
    setPending(true)
    setError(null)
    try {
      // 임시 비밀번호는 비제어 입력에서 요청 시에만 읽고 요청 직후 지운다.
      const request = original
        ? updateAccount(original.id, changes!)
        : createAccount({
            loginId: id,
            displayName: name,
            roleCode,
            temporaryPassword: password.current?.value ?? '',
          })
      if (password.current) password.current.value = ''
      if (confirmation.current) confirmation.current.value = ''
      const saved = await request
      setResult(saved)
      await client.invalidateQueries({
        queryKey: ['accounts'],
        refetchType: 'none',
      })
      if (original?.id === current?.id) await refresh()
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) await refresh()
      setError(
        cause instanceof Error
          ? cause
          : new Error('계정 요청을 처리하지 못했습니다.'),
      )
    } finally {
      if (password.current) password.current.value = ''
      if (confirmation.current) confirmation.current.value = ''
      setPending(false)
      submitting.current = false
    }
  }
  if (result)
    return (
      <section
        role="status"
        className="space-y-3 rounded-xl border border-emerald-200 bg-emerald-50 p-5"
      >
        <h2 className="font-semibold">
          {original ? '계정 정보를 변경했습니다.' : '계정을 생성했습니다.'}
        </h2>
        <p>
          {result.loginId} · {result.displayName}
        </p>
        {!original && (
          <p>
            임시 비밀번호는 다시 표시하지 않습니다. 새 사용자는 첫 로그인 후
            비밀번호를 변경해야 합니다.
          </p>
        )}
        <Link
          className="text-violet-700 underline"
          to={`/admin/accounts/${result.id}`}
          onClick={() => {
            if (original) onReload()
          }}
        >
          계정 상세 확인
        </Link>
      </section>
    )
  return (
    <form
      className="max-w-3xl space-y-5"
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
    >
      <fieldset disabled={pending || Boolean(blocked)} className="space-y-5">
        <label className="flex flex-col gap-2 text-sm">
          로그인 ID
          <input
            className="rounded-lg border p-2"
            value={loginId}
            onChange={(event) => setLoginId(event.target.value)}
            disabled={Boolean(original)}
            maxLength={100}
            autoComplete="off"
          />
        </label>
        <label className="flex flex-col gap-2 text-sm">
          표시 이름
          <input
            className="rounded-lg border p-2"
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            maxLength={100}
          />
        </label>
        <label className="flex flex-col gap-2 text-sm">
          역할
          <select
            className="rounded-lg border p-2"
            value={roleCode}
            onChange={(event) => {
              setRoleCode(event.target.value as Account['roleCode'])
              setAcknowledged(false)
            }}
          >
            <option value="">역할 선택</option>
            {roles.map((role) => (
              <option key={role.code} value={role.code}>
                {role.name}
              </option>
            ))}
          </select>
        </label>
        <RolePermissions roles={roles} code={roleCode} />
        {original ? (
          <>
            <label className="flex flex-col gap-2 text-sm">
              계정 상태
              <select
                className="rounded-lg border p-2"
                value={status}
                onChange={(event) => {
                  setStatus(event.target.value as Account['status'])
                  setAcknowledged(false)
                }}
              >
                {Object.entries(statusLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <p className="text-sm text-slate-500">
              {original.mustChangePassword
                ? '첫 로그인 시 비밀번호 변경 필요'
                : '비밀번호 설정 완료'}{' '}
              · 수정 기준 버전 {original.rowVersion}
            </p>
            <p className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
              역할 변경·잠금·비활성화 시 해당 계정의 모든 세션이 종료됩니다.
              마지막 활성 관리자의 역할·상태 변경은 서버가 거절합니다.
            </p>
            {selfChange && (
              <label className="flex gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={acknowledged}
                  onChange={(event) => setAcknowledged(event.target.checked)}
                />
                본인 계정 변경 시 현재 세션도 종료되는 것을 확인했습니다.
              </label>
            )}
          </>
        ) : (
          <>
            <label className="flex flex-col gap-2 text-sm">
              임시 비밀번호
              <input
                ref={password}
                className="rounded-lg border p-2"
                type="password"
                autoComplete="new-password"
              />
            </label>
            <label className="flex flex-col gap-2 text-sm">
              임시 비밀번호 확인
              <input
                ref={confirmation}
                className="rounded-lg border p-2"
                type="password"
                autoComplete="new-password"
              />
            </label>
            <p className="text-sm text-slate-500">
              8자 이상·72바이트 이하, 영문·숫자·특수문자를 모두 포함하며 로그인
              ID와 달라야 합니다. 새 계정은 활성 상태이며 첫 로그인 시
              비밀번호를 변경합니다.
            </p>
          </>
        )}
      </fieldset>
      {error && <ErrorState error={error} />}
      {Boolean(blocked) && (
        <>
          <p className="text-sm">
            입력은 현재 화면에 보존됩니다. 최신 정보를 다시 조회하면 입력을
            초기화합니다.
          </p>
          <Button variant="secondary" onClick={onReload}>
            최신 정보 다시 조회·입력 초기화
          </Button>
        </>
      )}
      <p className="text-xs text-slate-500">
        통신 오류가 발생하면 자동 재전송하지 않습니다. 목록에서 처리 여부를
        확인한 뒤 다시 시도하세요.
      </p>
      <Button type="submit" pending={pending} disabled={Boolean(blocked)}>
        {original ? '변경 저장' : '계정 생성'}
      </Button>
    </form>
  )
}

export default function AccountEditorPage() {
  const { id } = useParams()
  const location = useLocation()
  const editing = id !== undefined
  const number = Number(id)
  const valid =
    !editing ||
    (/^\d+$/.test(id ?? '') && Number.isSafeInteger(number) && number > 0)
  const [reload, setReload] = useState(0)
  const query = useQuery({
    queryKey: ['accounts', 'editor', editing ? number : 'new', reload],
    queryFn: async ({ signal }) =>
      editing
        ? fetchAccountEditor(number, signal)
        : { account: undefined, roles: await fetchRoles(signal) },
    enabled: valid,
    retry: false,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  let content
  if (!valid) content = <EmptyState message="올바른 계정 주소가 아닙니다." />
  else if (query.isPending || query.isFetching || query.isPaused)
    content = <LoadingState message="최신 계정·역할 정보를 불러오는 중…" />
  else if (query.isError)
    content = (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  else if (!query.data.roles.length)
    content = (
      <EmptyState message="등록된 역할이 없어 계정을 저장할 수 없습니다." />
    )
  else
    content = (
      <AccountForm
        key={`${location.pathname}:${reload}:${query.data.account?.rowVersion ?? 0}`}
        original={query.data.account}
        roles={query.data.roles}
        onReload={() => setReload((value) => value + 1)}
      />
    )
  return (
    <div className="space-y-5">
      <Link
        className="text-sm text-violet-700 underline"
        to={`/admin/accounts${location.search}`}
      >
        계정 목록으로 돌아가기
      </Link>
      <h1 className="text-2xl font-semibold">
        {editing ? '계정 상세·변경' : '계정 생성'}
      </h1>
      {content}
    </div>
  )
}
