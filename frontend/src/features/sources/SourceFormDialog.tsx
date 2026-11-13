import {
  useIsMutating,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query'
import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import Button from '../../components/Button'
import FormDialog from '../../components/FormDialog'
import { ApiError } from '../../api/errors'
import { ErrorState } from '../../components/Feedback'
import { createSource, updateSource } from './api'
import { parseEndpoint } from './endpoint'
import { purposeLabels } from './labels'
import type {
  AccountPurpose,
  CredentialInput,
  Source,
  SourceDetail,
  SourceKind,
} from './types'

const kindOptions: { value: SourceKind; label: string }[] = [
  { value: 'POSTGRES', label: 'PostgreSQL' },
  { value: 'CSV', label: 'CSV' },
  { value: 'DOCUMENT', label: '문서·기록' },
]

const purposes: AccountPurpose[] = ['SYNC', 'AI']

const inputClass = 'rounded-lg border border-slate-300 px-2 py-1'
type FieldName =
  'name' | 'code' | 'endpoint' | `${AccountPurpose}_${'username' | 'password'}`
type FieldErrors = Partial<Record<FieldName, string>>

function formatEndpoint(config: Source['connectionConfig']) {
  if (!config.host) return ''
  return `${config.host}${config.port ? `:${config.port}` : ''}/${config.database ?? ''}`
}

// 창을 연 때(또는 최신 내용을 다시 불러온 때)의 값. 사용자가 이 값에서 바꾼 칸만 보낸다.
function snapshot(source?: SourceDetail) {
  const username = (purpose: AccountPurpose) =>
    source?.credentials.find((item) => item.purpose === purpose)?.username ?? ''
  return {
    name: source?.name ?? '',
    endpoint: source ? formatEndpoint(source.connectionConfig) : '',
    usernames: { SYNC: username('SYNC'), AI: username('AI') },
  }
}

type Props = {
  // 없으면 등록, 있으면 수정
  source?: SourceDetail
  onClose: (navigating?: boolean) => void
  // reconnectNeeded: 접속 대상이나 계정이 바뀌어 연결을 다시 확인해야 한다. endpointChanged: 접속 대상이 바뀌었다.
  onSaved: (
    source: Source,
    reconnectNeeded: boolean,
    endpointChanged: boolean,
  ) => void
}

// 원천 등록(010)·정보 변경(012). 비밀번호는 요청에만 담고 화면에 다시 보여 주지 않는다.
function SourceFormDialog({ source, onClose, onSaved }: Props) {
  const queryClient = useQueryClient()
  const editing = source !== undefined
  const [opened, setOpened] = useState(() => snapshot(source))
  const [name, setName] = useState(opened.name)
  const [code, setCode] = useState(source?.code ?? '')
  const [kind, setKind] = useState<SourceKind>(source?.sourceKind ?? 'POSTGRES')
  const [endpoint, setEndpoint] = useState(opened.endpoint)
  const [accounts, setAccounts] = useState<
    Record<AccountPurpose, { username: string; password: string }>
  >(() => ({
    SYNC: { username: opened.usernames.SYNC, password: '' },
    AI: { username: opened.usernames.AI, password: '' },
  }))
  const [formError, setFormError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const errorId = useId()
  // 다른 곳에서 먼저 바뀌어(409) 최신 내용을 다시 불러왔다
  const [reloaded, setReloaded] = useState(false)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const usesDatabase = kind === 'POSTGRES'
  const unsupportedCreation = !editing && !usesDatabase
  const hasUnsavedChanges =
    name !== opened.name ||
    code !== (source?.code ?? '') ||
    kind !== (source?.sourceKind ?? 'POSTGRES') ||
    endpoint !== opened.endpoint ||
    purposes.some(
      (purpose) =>
        accounts[purpose].username !== opened.usernames[purpose] ||
        accounts[purpose].password !== '',
    )

  const saving =
    useIsMutating({
      mutationKey: ['form-save', 'source', source?.id ?? 'new'],
    }) > 0
  const save = useMutation({
    mutationKey: ['form-save', 'source', source?.id ?? 'new'],
    meta: {
      formSave: {
        label: `${editing ? '원천 정보 수정' : '원천 등록'} · ${name.trim() || source?.name || '새 원천'}`,
        href: source
          ? `/admin/sources?source=${source.id}&tab=connection`
          : '/admin/sources',
      },
    },
    retry: false,
    mutationFn: async () => {
      if (unsupportedCreation)
        throw new Error('이 화면에서는 PostgreSQL 연결만 등록할 수 있습니다.')
      const config = usesDatabase ? parseEndpoint(endpoint) : {}
      const credentials: CredentialInput[] = []
      for (const purpose of usesDatabase ? purposes : []) {
        const account = {
          username: accounts[purpose].username.trim(),
          password: accounts[purpose].password,
        }
        const before = opened.usernames[purpose]
        // 수정에서는 비밀번호를 새로 넣은 계정만 바꾼다.
        if (!editing || account.password) {
          credentials.push({ purpose, ...account })
        } else if (account.username !== before) {
          throw new Error(
            `${purposeLabels[purpose]} 이름을 바꾸려면 비밀번호도 입력해 주세요.`,
          )
        }
      }
      if (!editing) {
        return {
          saved: await createSource({
            code: code.trim(),
            name: name.trim(),
            sourceKind: kind,
            connectionConfig: config ?? {},
            credentials,
          }),
          reconnectNeeded: false,
          endpointChanged: false,
        }
      }
      const configChanged =
        usesDatabase && formatEndpoint(config ?? {}) !== opened.endpoint
      return {
        saved: await updateSource(source.id, {
          rowVersion: source.rowVersion,
          ...(name.trim() !== opened.name ? { name: name.trim() } : {}),
          ...(configChanged ? { connectionConfig: config ?? {} } : {}),
          ...(credentials.length ? { credentials } : {}),
        }),
        reconnectNeeded: configChanged || credentials.length > 0,
        endpointChanged: configChanged,
      }
    },
    onMutate: () => setReloaded(false),
    onSuccess: ({ saved, reconnectNeeded, endpointChanged }) => {
      void queryClient.invalidateQueries({ queryKey: ['sources'] })
      if (mounted.current) onSaved(saved, reconnectNeeded, endpointChanged)
    },
    // 다른 곳에서 먼저 저장했으면(rowVersion 충돌) 최신 내용을 받아 와야 같은 창에서 다시 저장할 수 있다.
    onError: async (error) => {
      if (!editing || !(error instanceof ApiError) || error.status !== 409)
        return
      const key = ['sources', source.id, 'detail']
      await queryClient.invalidateQueries({ queryKey: key })
      if (!mounted.current) return
      const latest = queryClient.getQueryData<SourceDetail>(key)
      if (latest) {
        // 손대지 않은 칸은 최신 값으로 바꿔, 다시 저장할 때 다른 곳에서 바꾼 값을 옛 값으로 되돌리지 않는다
        const next = snapshot(latest)
        if (name === opened.name) setName(next.name)
        if (endpoint === opened.endpoint) setEndpoint(next.endpoint)
        setAccounts((current) => ({
          SYNC:
            current.SYNC.username === opened.usernames.SYNC
              ? { ...current.SYNC, username: next.usernames.SYNC }
              : current.SYNC,
          AI:
            current.AI.username === opened.usernames.AI
              ? { ...current.AI, username: next.usernames.AI }
              : current.AI,
        }))
        setOpened(next)
      }
      setReloaded(true)
    },
  })

  function clearFieldError(field: FieldName) {
    const next = { ...fieldErrors }
    delete next[field]
    setFieldErrors(next)
    if (!Object.keys(next).length) setFormError('')
  }

  function fieldProps(field: FieldName) {
    return {
      'aria-invalid': fieldErrors[field] ? true : undefined,
      'aria-describedby': fieldErrors[field]
        ? `${errorId}-${field}`
        : undefined,
    }
  }

  function fieldError(field: FieldName) {
    return fieldErrors[field] ? (
      <p id={`${errorId}-${field}`} className="ui-field-error">
        {fieldErrors[field]}
      </p>
    ) : null
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (unsupportedCreation) return
    if (
      queryClient.isMutating({
        mutationKey: ['form-save', 'source', source?.id ?? 'new'],
      })
    )
      return
    const errors: FieldErrors = {}
    if (!name.trim()) errors.name = '원천 이름을 입력해 주세요.'
    if (!editing && !code.trim()) errors.code = '원천 코드를 입력해 주세요.'
    if (!name.trim() || (!editing && !code.trim())) {
      setFieldErrors(errors)
      setFormError('원천 이름과 코드를 입력해 주세요.')
      return
    }
    if (usesDatabase) {
      if (!parseEndpoint(endpoint)) {
        setFieldErrors({
          endpoint: '주소:포트/데이터베이스 형식을 확인해 주세요.',
        })
        setFormError(
          '접속 대상을 "주소:포트/데이터베이스" 형식으로 입력해 주세요.',
        )
        return
      }
      const missing = purposes.find(
        (purpose) =>
          !accounts[purpose].username.trim() ||
          (!editing && !accounts[purpose].password),
      )
      if (missing) {
        if (!accounts[missing].username.trim())
          errors[`${missing}_username`] = '계정 이름을 입력해 주세요.'
        if (!editing && !accounts[missing].password)
          errors[`${missing}_password`] = '비밀번호를 입력해 주세요.'
        setFieldErrors(errors)
        setFormError(
          `${purposeLabels[missing]}의 이름${editing ? '' : '과 비밀번호'}를 입력해 주세요.`,
        )
        return
      }
      const passwordNeeded = purposes.find(
        (purpose) =>
          editing &&
          accounts[purpose].username.trim() !== opened.usernames[purpose] &&
          !accounts[purpose].password,
      )
      if (passwordNeeded) {
        setFieldErrors({
          [`${passwordNeeded}_password`]:
            '계정 이름을 변경하려면 새 비밀번호를 입력해 주세요.',
        })
        setFormError(
          `${purposeLabels[passwordNeeded]} 이름을 바꾸려면 비밀번호도 입력해 주세요.`,
        )
        return
      }
    }
    setFieldErrors({})
    setFormError('')
    save.mutate()
  }

  function setAccount(
    purpose: AccountPurpose,
    field: 'username' | 'password',
    value: string,
  ) {
    clearFieldError(`${purpose}_${field}`)
    setAccounts((previous) => ({
      ...previous,
      [purpose]: { ...previous[purpose], [field]: value },
    }))
  }

  return (
    <FormDialog
      title={editing ? '원천 정보 수정' : '원천 등록'}
      dirty={hasUnsavedChanges}
      pending={saving}
      error={save.error}
      onClose={onClose}
    >
      {(requestClose) => (
        <form
          className="sources-form mt-4 space-y-4 text-sm"
          onSubmit={submit}
          noValidate
        >
          <p className="text-sm text-slate-600">
            조회 전용 계정만 등록하세요. 비밀번호는 저장할 때만 보내고 화면에
            다시 보여 주지 않습니다.
          </p>
          <div>
            <label className="flex flex-col gap-1">
              원천 이름 (필수)
              <input
                disabled={saving}
                value={name}
                onChange={(event) => {
                  setName(event.target.value)
                  clearFieldError('name')
                }}
                placeholder="예: ERP 운영 DB"
                className={inputClass}
                required
                aria-label="원천 이름"
                {...fieldProps('name')}
              />
            </label>
            {fieldError('name')}
          </div>
          <div>
            <label className="flex flex-col gap-1">
              원천 코드{!editing && ' (필수)'}
              <input
                value={code}
                onChange={(event) => {
                  setCode(event.target.value.toUpperCase())
                  clearFieldError('code')
                }}
                placeholder="예: ERP_MAIN"
                disabled={editing || saving}
                className={`${inputClass} font-mono disabled:bg-slate-50`}
                required={!editing}
                aria-label="원천 코드"
                {...fieldProps('code')}
              />
            </label>
            {fieldError('code')}
          </div>
          <label className="flex flex-col gap-1">
            유형
            <select
              value={kind}
              onChange={(event) => {
                if (!editing && event.target.value !== 'POSTGRES') return
                setKind(event.target.value as SourceKind)
                setFieldErrors({})
                setFormError('')
              }}
              disabled={editing || saving}
              className={`${inputClass} disabled:bg-slate-50`}
            >
              {kindOptions
                .filter((option) => editing || option.value === 'POSTGRES')
                .map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
            </select>
          </label>
          {usesDatabase && (
            <>
              <div>
                <label className="flex flex-col gap-1">
                  접속 대상 (필수)
                  <input
                    disabled={saving}
                    value={endpoint}
                    onChange={(event) => {
                      setEndpoint(event.target.value)
                      clearFieldError('endpoint')
                    }}
                    placeholder="예: erp-db.internal:5432/erp"
                    className={`${inputClass} font-mono`}
                    required
                    aria-label="접속 대상"
                    {...fieldProps('endpoint')}
                  />
                </label>
                {fieldError('endpoint')}
              </div>
              {purposes.map((purpose) => (
                <fieldset key={purpose} className="space-y-2">
                  <legend className="font-medium text-slate-900">
                    {purposeLabels[purpose]}
                  </legend>
                  <div className="grid gap-2 sm:grid-cols-2">
                    <div>
                      <label className="flex flex-col gap-1">
                        {`${purposeLabels[purpose]} 이름 (필수)`}
                        <input
                          disabled={saving}
                          value={accounts[purpose].username}
                          onChange={(event) =>
                            setAccount(purpose, 'username', event.target.value)
                          }
                          autoComplete="off"
                          className={`${inputClass} font-mono`}
                          required
                          aria-label={`${purposeLabels[purpose]} 이름`}
                          {...fieldProps(`${purpose}_username`)}
                        />
                      </label>
                      {fieldError(`${purpose}_username`)}
                    </div>
                    <div>
                      <label className="flex flex-col gap-1">
                        {`${purposeLabels[purpose]} 비밀번호${!editing || accounts[purpose].username.trim() !== opened.usernames[purpose] ? ' (필수)' : ' (선택)'}`}
                        <input
                          disabled={saving}
                          type="password"
                          value={accounts[purpose].password}
                          onChange={(event) =>
                            setAccount(purpose, 'password', event.target.value)
                          }
                          autoComplete="new-password"
                          placeholder={editing ? '바꿀 때만 입력' : ''}
                          className={inputClass}
                          required={
                            !editing ||
                            accounts[purpose].username.trim() !==
                              opened.usernames[purpose]
                          }
                          aria-label={`${purposeLabels[purpose]} 비밀번호`}
                          {...fieldProps(`${purpose}_password`)}
                        />
                      </label>
                      {fieldError(`${purpose}_password`)}
                    </div>
                  </div>
                </fieldset>
              ))}
            </>
          )}
          {formError && (
            <p role="alert" className="text-red-600">
              {formError}
            </p>
          )}
          {save.isError && <ErrorState error={save.error} />}
          {save.isError && reloaded && (
            <p role="status" className="text-slate-700">
              다른 곳에서 바뀐 내용을 불러왔습니다. 입력한 내용을 확인하고 다시
              저장해 주세요.
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={requestClose}>
              취소
            </Button>
            <Button
              type="submit"
              pending={saving}
              disabled={unsupportedCreation}
            >
              {editing ? '저장' : '등록'}
            </Button>
          </div>
        </form>
      )}
    </FormDialog>
  )
}

export default SourceFormDialog
