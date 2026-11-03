import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
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

function formatEndpoint(config: Source['connectionConfig']) {
  if (!config.host) return ''
  return `${config.host}${config.port ? `:${config.port}` : ''}/${config.database ?? ''}`
}

type Props = {
  // 없으면 등록, 있으면 수정
  source?: SourceDetail
  onClose: () => void
  onSaved: (source: Source, reconnectNeeded: boolean) => void
}

// 원천 등록(010)·정보 변경(012). 비밀번호는 요청에만 담고 화면에 다시 보여 주지 않는다.
function SourceFormDialog({ source, onClose, onSaved }: Props) {
  const queryClient = useQueryClient()
  const editing = source !== undefined
  const [name, setName] = useState(source?.name ?? '')
  const [code, setCode] = useState(source?.code ?? '')
  const [kind, setKind] = useState<SourceKind>(source?.sourceKind ?? 'POSTGRES')
  const [endpoint, setEndpoint] = useState(
    source ? formatEndpoint(source.connectionConfig) : '',
  )
  const [accounts, setAccounts] = useState<
    Record<AccountPurpose, { username: string; password: string }>
  >(() => {
    const username = (purpose: AccountPurpose) =>
      source?.credentials.find((item) => item.purpose === purpose)?.username ??
      ''
    return {
      SYNC: { username: username('SYNC'), password: '' },
      AI: { username: username('AI'), password: '' },
    }
  })
  const [formError, setFormError] = useState('')
  const usesDatabase = kind === 'POSTGRES'

  const save = useMutation({
    mutationFn: async () => {
      const config = usesDatabase ? parseEndpoint(endpoint) : {}
      const credentials: CredentialInput[] = []
      for (const purpose of usesDatabase ? purposes : []) {
        const account = accounts[purpose]
        const before = source?.credentials.find(
          (item) => item.purpose === purpose,
        )?.username
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
        }
      }
      const configChanged =
        usesDatabase &&
        formatEndpoint(config ?? {}) !== formatEndpoint(source.connectionConfig)
      return {
        saved: await updateSource(source.id, {
          rowVersion: source.rowVersion,
          ...(name.trim() !== source.name ? { name: name.trim() } : {}),
          ...(configChanged ? { connectionConfig: config ?? {} } : {}),
          ...(credentials.length ? { credentials } : {}),
        }),
        reconnectNeeded: configChanged || credentials.length > 0,
      }
    },
    onSuccess: ({ saved, reconnectNeeded }) => {
      void queryClient.invalidateQueries({ queryKey: ['sources'] })
      onSaved(saved, reconnectNeeded)
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!name.trim() || (!editing && !code.trim())) {
      setFormError('원천 이름과 코드를 입력해 주세요.')
      return
    }
    if (usesDatabase) {
      if (!parseEndpoint(endpoint)) {
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
        setFormError(
          `${purposeLabels[missing]}의 이름${editing ? '' : '과 비밀번호'}를 입력해 주세요.`,
        )
        return
      }
    }
    setFormError('')
    save.mutate()
  }

  function setAccount(
    purpose: AccountPurpose,
    field: 'username' | 'password',
    value: string,
  ) {
    setAccounts((previous) => ({
      ...previous,
      [purpose]: { ...previous[purpose], [field]: value },
    }))
  }

  return (
    <Dialog title={editing ? '원천 정보 수정' : '원천 등록'} onClose={onClose}>
      <form className="mt-4 space-y-4 text-sm" onSubmit={submit} noValidate>
        <p className="text-xs text-slate-500">
          조회 전용 계정만 등록하세요. 비밀번호는 저장할 때만 보내고 화면에 다시
          보여 주지 않습니다.
        </p>
        <label className="flex flex-col gap-1">
          원천 이름
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="예: ERP 운영 DB"
            className={inputClass}
          />
        </label>
        <label className="flex flex-col gap-1">
          원천 코드
          <input
            value={code}
            onChange={(event) => setCode(event.target.value.toUpperCase())}
            placeholder="예: ERP_MAIN"
            disabled={editing}
            className={`${inputClass} font-mono disabled:bg-slate-50`}
          />
        </label>
        <label className="flex flex-col gap-1">
          유형
          <select
            value={kind}
            onChange={(event) => setKind(event.target.value as SourceKind)}
            disabled={editing}
            className={`${inputClass} disabled:bg-slate-50`}
          >
            {kindOptions.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        {usesDatabase && (
          <>
            <label className="flex flex-col gap-1">
              접속 대상
              <input
                value={endpoint}
                onChange={(event) => setEndpoint(event.target.value)}
                placeholder="예: erp-db.internal:5432/erp"
                className={`${inputClass} font-mono`}
              />
            </label>
            {purposes.map((purpose) => (
              <fieldset key={purpose} className="space-y-2">
                <legend className="font-medium text-slate-900">
                  {purposeLabels[purpose]}
                </legend>
                <div className="grid gap-2 sm:grid-cols-2">
                  <label className="flex flex-col gap-1">
                    {`${purposeLabels[purpose]} 이름`}
                    <input
                      value={accounts[purpose].username}
                      onChange={(event) =>
                        setAccount(purpose, 'username', event.target.value)
                      }
                      autoComplete="off"
                      className={`${inputClass} font-mono`}
                    />
                  </label>
                  <label className="flex flex-col gap-1">
                    {`${purposeLabels[purpose]} 비밀번호`}
                    <input
                      type="password"
                      value={accounts[purpose].password}
                      onChange={(event) =>
                        setAccount(purpose, 'password', event.target.value)
                      }
                      autoComplete="new-password"
                      placeholder={editing ? '바꿀 때만 입력' : ''}
                      className={inputClass}
                    />
                  </label>
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
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            취소
          </Button>
          <Button type="submit" pending={save.isPending}>
            {editing ? '저장' : '등록'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

export default SourceFormDialog
