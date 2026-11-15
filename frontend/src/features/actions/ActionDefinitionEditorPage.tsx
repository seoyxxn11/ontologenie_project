import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Link,
  useBeforeUnload,
  useBlocker,
  useLocation,
  useParams,
} from 'react-router'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import PageHeader from '../../components/PageHeader'
import { ErrorState, LoadingState } from '../../components/Feedback'
import StatusBadge from '../../components/StatusBadge'
import { agentUsage, codeError } from './agentUsage'
import {
  actionPath,
  createActionDefinition,
  fetchActionDefinition,
  fetchActionEditorOptions,
  updateActionDefinition,
  type ActionEditorOptions,
} from './api'
import InputSchemaEditor from './InputSchemaEditor'
import { changesDataLabels, targetKindLabels } from './labels'
import {
  initialSchemaDraft,
  readSchemaDraft,
  sameJson,
  type SchemaDraft,
} from './schemaFields'
import './ActionDefinitionsPage.css'
import type {
  ActionDefinition,
  ActionDefinitionChanges,
  ActionDefinitionInput,
  ActionKind,
  ActionTargetKind,
} from './types'

function UsagePanel({
  definition,
  stored = false,
}: {
  definition: ActionDefinition
  stored?: boolean
}) {
  const usage = agentUsage(definition)
  return (
    <section className="space-y-2 management-subpanel text-sm">
      <div className="flex flex-wrap items-center gap-2">
        {stored && <span className="text-slate-500">저장된 상태</span>}
        <span className="actions-code font-mono text-sm text-slate-500">
          {definition.code}
        </span>
        <StatusBadge tone={usage.usable ? 'success' : 'warning'}>
          {usage.label}
        </StatusBadge>
      </div>
      {!usage.usable && <p>{usage.reason}</p>}
    </section>
  )
}

function RecordDefinitionView({
  definition,
  options,
}: {
  definition: ActionDefinition
  options: ActionEditorOptions
}) {
  const type = options.objectTypes.find(
    (item) => item.id === definition.targetObjectTypeId,
  )
  const rows: [string, string][] = [
    ['이름', definition.name],
    ['설명', definition.description],
    ['데이터 변경', changesDataLabels[definition.actionKind]],
    [
      '대상',
      `${targetKindLabels[definition.targetKind]}${type ? ` · ${type.name}` : ''}`,
    ],
    ['필요 권한', definition.requiredPermission],
    ['사용', definition.isActive ? '사용' : '사용 안 함'],
  ]
  return (
    <div className="management-form space-y-5">
      <UsagePanel definition={definition} />
      <p className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
        관리 작업의 정의는 시스템이 관리해 화면에서 바꿀 수 없습니다.
      </p>
      <dl className="grid gap-3 text-sm sm:grid-cols-[10rem_1fr]">
        {rows.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="text-slate-500">{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

type Draft = {
  code: string
  name: string
  description: string
  changesData: '' | ActionKind
  targetKind: ActionTargetKind
  targetObjectTypeId: string
  handlerKey: string
  schema: SchemaDraft
  requiresConfirmation: boolean
  requiredPermission: string
  isActive: boolean
}

function initialDraft(original?: ActionDefinition): Draft {
  return {
    code: original?.code ?? '',
    name: original?.name ?? '',
    description: original?.description ?? '',
    changesData: original?.actionKind ?? '',
    targetKind: original?.targetKind ?? 'OBJECT',
    targetObjectTypeId: original?.targetObjectTypeId
      ? String(original.targetObjectTypeId)
      : '',
    handlerKey: original?.handlerKey ?? '',
    schema: initialSchemaDraft(original?.inputSchema),
    requiresConfirmation: original?.requiresConfirmation ?? false,
    requiredPermission: original?.requiredPermission ?? '',
    isActive: original?.isActive ?? true,
  }
}

function readInput(
  draft: Draft,
  editing: boolean,
): ActionDefinitionInput | string {
  const code = draft.code.trim()
  const violation = editing ? '' : codeError(code)
  if (violation) return violation
  const name = draft.name.trim()
  if (!name || name.length > 200)
    return '이름은 1자 이상 200자 이하로 입력해 주세요.'
  if (!draft.description.trim()) return '설명을 입력해 주세요.'
  if (!draft.changesData)
    return '이 기능이 데이터를 만들거나 바꾸는지 선택해 주세요.'
  const handlerKey = draft.handlerKey.trim()
  if (!handlerKey || handlerKey.length > 200)
    return 'Handler 이름은 1자 이상 200자 이하로 입력해 주세요.'
  if (!draft.requiredPermission) return '필요 권한을 선택해 주세요.'
  const schema = readSchemaDraft(draft.schema)
  if (schema.error !== undefined) return schema.error
  return {
    code,
    name,
    description: draft.description.trim(),
    targetKind: draft.targetKind,
    targetObjectTypeId:
      draft.targetKind === 'OBJECT' && draft.targetObjectTypeId
        ? Number(draft.targetObjectTypeId)
        : null,
    handlerKey,
    inputSchema: schema.schema,
    actionKind: draft.changesData,
    requiresConfirmation: draft.requiresConfirmation,
    requiredPermission: draft.requiredPermission,
    isActive: draft.isActive,
  }
}

function changesFrom(
  original: ActionDefinition,
  input: ActionDefinitionInput,
): ActionDefinitionChanges {
  const changes: ActionDefinitionChanges = { rowVersion: original.rowVersion }
  if (input.name !== original.name) changes.name = input.name
  if (input.description !== original.description)
    changes.description = input.description
  if (input.targetKind !== original.targetKind)
    changes.targetKind = input.targetKind
  if (input.targetObjectTypeId !== original.targetObjectTypeId)
    changes.targetObjectTypeId = input.targetObjectTypeId
  if (input.handlerKey !== original.handlerKey)
    changes.handlerKey = input.handlerKey
  if (!sameJson(input.inputSchema, original.inputSchema))
    changes.inputSchema = input.inputSchema
  if (input.actionKind !== original.actionKind)
    changes.actionKind = input.actionKind
  if (input.requiresConfirmation !== original.requiresConfirmation)
    changes.requiresConfirmation = input.requiresConfirmation
  if (input.requiredPermission !== original.requiredPermission)
    changes.requiredPermission = input.requiredPermission
  if (input.isActive !== original.isActive) changes.isActive = input.isActive
  return changes
}

const actionTabs = [
  ['basic', '기본 정보'],
  ['conditions', '실행 조건'],
  ['schema', '입력 형식'],
] as const
type ActionTab = (typeof actionTabs)[number][0]

function ActionDefinitionForm({
  original,
  options,
  onReload,
}: {
  original?: ActionDefinition
  options: ActionEditorOptions
  onReload: () => void
}) {
  const client = useQueryClient()
  const [initial] = useState(() => initialDraft(original))
  const [draft, setDraft] = useState(initial)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [result, setResult] = useState<ActionDefinition | null>(null)
  const submitting = useRef(false)
  const formRef = useRef<HTMLFormElement>(null)
  const leaveRef = useRef<HTMLDivElement>(null)
  const previousFocus = useRef<HTMLElement | null>(null)
  const [tab, setTab] = useState<ActionTab>('basic')
  const [technicalOpen, setTechnicalOpen] = useState(false)
  const [invalidField, setInvalidField] = useState('')
  const [focusError, setFocusError] = useState(0)
  const dirty = !result && JSON.stringify(draft) !== JSON.stringify(initial)
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      (pending || dirty) &&
      (currentLocation.pathname !== nextLocation.pathname ||
        currentLocation.search !== nextLocation.search),
  )
  useEffect(() => {
    if (blocker.state === 'blocked') {
      previousFocus.current =
        document.activeElement instanceof HTMLElement
          ? document.activeElement
          : null
      leaveRef.current?.querySelector('button')?.focus()
    } else if (previousFocus.current) {
      previousFocus.current.focus()
      previousFocus.current = null
    }
  }, [blocker.state])
  useEffect(() => {
    if (result && blocker.state === 'blocked') blocker.reset()
  }, [result, blocker])
  useBeforeUnload(
    useCallback(
      (event) => {
        if (dirty || pending) {
          event.preventDefault()
          event.returnValue = ''
        }
      },
      [dirty, pending],
    ),
  )
  useEffect(() => {
    if (!focusError) return
    const element = formRef.current?.querySelector<HTMLElement>(
      invalidField === 'schema'
        ? '#action-panel-schema textarea, #action-panel-schema input'
        : `[name="${invalidField}"]`,
    )
    element?.focus()
  }, [focusError, invalidField])
  const editing = original !== undefined
  const blocked =
    editing &&
    error instanceof ApiError &&
    [401, 403, 404, 409, 422].includes(error.status ?? 0)
  const permissions = options.permissions.includes(draft.requiredPermission)
    ? options.permissions
    : [draft.requiredPermission, ...options.permissions].filter(Boolean)
  const codeViolation = editing || !draft.code ? '' : codeError(draft.code)

  function update<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraft((current) => ({ ...current, [key]: value }))
    if (invalidField === key) {
      setInvalidField('')
      setError(null)
    }
  }

  function fieldError(field: string) {
    return invalidField === field && error ? (
      <p id={`action-field-${field}-error`} className="ui-field-error">
        {error.message}
      </p>
    ) : null
  }

  async function submit() {
    if (submitting.current || blocked || result) return
    const input = readInput(draft, editing)
    if (typeof input === 'string') {
      setError(new Error(input))
      const field =
        !editing && codeError(draft.code.trim())
          ? 'code'
          : !draft.name.trim() || draft.name.trim().length > 200
            ? 'name'
            : !draft.description.trim()
              ? 'description'
              : !draft.changesData
                ? 'changesData'
                : !draft.handlerKey.trim() ||
                    draft.handlerKey.trim().length > 200
                  ? 'handlerKey'
                  : !draft.requiredPermission
                    ? 'requiredPermission'
                    : 'schema'
      setInvalidField(field)
      setTab(
        field === 'schema'
          ? 'schema'
          : ['changesData', 'requiredPermission'].includes(field)
            ? 'conditions'
            : 'basic',
      )
      if (field === 'handlerKey') setTechnicalOpen(true)
      setFocusError((value) => value + 1)
      return
    }
    let changes: ActionDefinitionChanges | undefined
    if (original) {
      changes = changesFrom(original, input)
      if (Object.keys(changes).length === 1) {
        setError(new Error('변경할 내용을 입력해 주세요.'))
        return
      }
    }
    submitting.current = true
    setPending(true)
    setError(null)
    setInvalidField('')
    try {
      const saved = original
        ? await updateActionDefinition(original.code, changes!)
        : await createActionDefinition(input)
      setResult(saved)
      await client.invalidateQueries({
        queryKey: ['actions'],
        refetchType: 'none',
      })
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause
          : new Error('Action 정의 요청을 처리하지 못했습니다.'),
      )
    } finally {
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
          {editing ? 'Action 정의를 변경했습니다.' : 'Action을 등록했습니다.'}
        </h2>
        <p>
          {result.name} ·{' '}
          <span className="actions-code font-mono">{result.code}</span>
        </p>
        {!result.handlerReady && (
          <p>
            아직 구현되지 않은 기능입니다. 구현되기 전에는 실행되지 않습니다.
            {(!original || original.handlerKey !== result.handlerKey) &&
              ' 서버가 개발자에게 구현 요청 알림을 보냅니다.'}
          </p>
        )}
        <Link
          className="text-violet-700 underline"
          to={actionPath(result.code)}
          onClick={() => {
            if (editing) onReload()
          }}
        >
          정의 상세 확인
        </Link>
      </section>
    )

  return (
    <form
      ref={formRef}
      className="management-form actions-editor"
      noValidate
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
    >
      {original && <UsagePanel definition={original} stored />}
      {blocker.state === 'blocked' && (
        <div ref={leaveRef} role="alert" className="ui-feedback actions-leave">
          <p>
            {pending
              ? '저장 요청을 처리 중입니다. 완료 후 결과를 확인해 주세요.'
              : '저장하지 않은 변경이 있습니다.'}
          </p>
          <Button variant="secondary" onClick={() => blocker.reset()}>
            편집 계속
          </Button>
          {!pending && (
            <Button variant="secondary" onClick={() => blocker.proceed()}>
              입력 폐기하고 이동
            </Button>
          )}
        </div>
      )}
      <div className="actions-tabs" role="tablist" aria-label="Action 설정">
        {actionTabs.map(([key, label], index) => (
          <button
            type="button"
            role="tab"
            key={key}
            id={`action-tab-${key}`}
            aria-controls={`action-panel-${key}`}
            aria-selected={tab === key}
            tabIndex={tab === key ? 0 : -1}
            onClick={() => setTab(key)}
            onKeyDown={(event) => {
              const next =
                event.key === 'Home'
                  ? 0
                  : event.key === 'End'
                    ? actionTabs.length - 1
                    : event.key === 'ArrowRight'
                      ? (index + 1) % actionTabs.length
                      : event.key === 'ArrowLeft'
                        ? (index + actionTabs.length - 1) % actionTabs.length
                        : -1
              if (next < 0) return
              event.preventDefault()
              setTab(actionTabs[next][0])
              document
                .getElementById(`action-tab-${actionTabs[next][0]}`)
                ?.focus()
            }}
          >
            {label}
          </button>
        ))}
      </div>
      <fieldset
        disabled={pending || Boolean(blocked)}
        className="actions-editor-fields"
      >
        <section
          id="action-panel-basic"
          role="tabpanel"
          aria-labelledby="action-tab-basic"
          hidden={tab !== 'basic'}
          className="actions-tabpanel"
        >
          <h2>기본 정보</h2>
          {!editing && (
            <>
              <div className="actions-field">
                <label className="flex flex-col gap-2 text-sm">
                  <span
                    className="actions-field-label"
                    data-required={!editing}
                  >
                    코드
                  </span>
                  <input
                    className="rounded-lg border p-2 font-mono"
                    name="code"
                    aria-required={!editing}
                    aria-invalid={
                      Boolean(codeViolation) ||
                      invalidField === 'code' ||
                      undefined
                    }
                    value={draft.code}
                    onChange={(event) =>
                      update('code', event.target.value.toUpperCase())
                    }
                    disabled={editing}
                    maxLength={64}
                    autoComplete="off"

                    aria-describedby={
                      invalidField === 'code'
                        ? 'action-code-help action-field-code-error'
                        : 'action-code-help'
                    }
                  />
                  <span
                    id="action-code-help"
                    className={
                      codeViolation ? 'text-red-700' : 'text-slate-500'
                    }
                  >
                    {codeViolation ||
                      (editing
                        ? '코드는 등록한 뒤 바꿀 수 없습니다.'
                        : '영문 대문자로 시작하고 대문자·숫자·밑줄(_)만, 64자 이하. 예: ESTIMATE_COMPLETION')}
                  </span>
                </label>
                {fieldError('code')}
              </div>
            </>
          )}

          <div className="actions-field">
            <label className="flex flex-col gap-2 text-sm">
              <span className="actions-field-label" data-required>
                이름
              </span>
              <input
                className="rounded-lg border p-2"
                name="name"
                aria-required={true}
                aria-invalid={invalidField === 'name' || undefined}
                aria-describedby={
                  invalidField === 'name'
                    ? 'action-field-name-error'
                    : undefined
                }
                value={draft.name}
                onChange={(event) => update('name', event.target.value)}
                maxLength={200}
              />
            </label>
            {fieldError('name')}
          </div>
          <div className="actions-field">
            <label className="flex flex-col gap-2 text-sm">
              <span className="actions-field-label" data-required>
                설명
              </span>
              <textarea
                className="min-h-20 rounded-lg border p-2"
                name="description"
                aria-required={true}
                aria-invalid={invalidField === 'description' || undefined}
                aria-describedby={
                  invalidField === 'description'
                    ? 'action-field-description-error'
                    : undefined
                }
                value={draft.description}
                onChange={(event) => update('description', event.target.value)}
              />
              <span className="text-slate-500">
                Agent가 이 설명을 보고 언제 쓸지 판단합니다. 무엇을 하는지
                구체적으로 적어 주세요.
              </span>
            </label>
            {fieldError('description')}
          </div>
          <details
            className="actions-disclosure"
            open={technicalOpen}
            onToggle={(event) => setTechnicalOpen(event.currentTarget.open)}
          >
            <summary>개발 연결 설정</summary>
            <div className="actions-fields">
              {editing && (
                <>
                  <div className="actions-field">
                    <label className="flex flex-col gap-2 text-sm">
                      <span
                        className="actions-field-label"
                        data-required={!editing}
                      >
                        코드
                      </span>
                      <input
                        className="rounded-lg border p-2 font-mono"
                        name="code"
                        aria-required={!editing}
                        aria-invalid={
                          Boolean(codeViolation) ||
                          invalidField === 'code' ||
                          undefined
                        }
                        value={draft.code}
                        onChange={(event) =>
                          update('code', event.target.value.toUpperCase())
                        }
                        disabled={editing}
                        maxLength={64}
                        autoComplete="off"

                        aria-describedby={
                          invalidField === 'code'
                            ? 'action-code-help action-field-code-error'
                            : 'action-code-help'
                        }
                      />
                      <span
                        id="action-code-help"
                        className={
                          codeViolation ? 'text-red-700' : 'text-slate-500'
                        }
                      >
                        {codeViolation ||
                          (editing
                            ? '코드는 등록한 뒤 바꿀 수 없습니다.'
                            : '영문 대문자로 시작하고 대문자·숫자·밑줄(_)만, 64자 이하. 예: ESTIMATE_COMPLETION')}
                      </span>
                    </label>
                    {fieldError('code')}
                  </div>
                </>
              )}
              <div className="actions-field">
                <label className="flex flex-col gap-2 text-sm">
                  <span className="actions-field-label" data-required>
                    Handler 이름
                  </span>
                  <input
                    className="rounded-lg border p-2 font-mono"
                    name="handlerKey"
                    aria-required={true}
                    aria-invalid={invalidField === 'handlerKey' || undefined}
                    aria-describedby={
                      invalidField === 'handlerKey'
                        ? 'action-field-handlerKey-error'
                        : undefined
                    }
                    value={draft.handlerKey}
                    onChange={(event) =>
                      update('handlerKey', event.target.value)
                    }
                    maxLength={200}
                    autoComplete="off"
                  />
                  <span className="text-slate-500">
                    개발자가 구현할 기능 이름입니다. 보통 코드와 같게 둡니다.
                  </span>
                </label>
                {fieldError('handlerKey')}
              </div>
            </div>
          </details>
        </section>
        <section
          id="action-panel-conditions"
          role="tabpanel"
          aria-labelledby="action-tab-conditions"
          hidden={tab !== 'conditions'}
          className="actions-tabpanel"
        >
          <h2>실행 조건</h2>
          <div className="grid gap-5 sm:grid-cols-2">
            <div className="actions-field">
              <label className="flex flex-col gap-2 text-sm">
                <span className="actions-field-label" data-required>
                  데이터 변경
                </span>
                <select
                  name="changesData"
                  aria-required={true}
                  aria-invalid={invalidField === 'changesData' || undefined}
                  aria-describedby={
                    invalidField === 'changesData'
                      ? 'action-field-changesData-error'
                      : undefined
                  }
                  value={draft.changesData}
                  onChange={(event) =>
                    update(
                      'changesData',
                      event.target.value as Draft['changesData'],
                    )
                  }
                >
                  <option value="">선택</option>
                  <option value="WRITE">데이터를 바꿈</option>
                  <option value="READ">데이터를 바꾸지 않음</option>
                </select>
              </label>
              {fieldError('changesData')}
            </div>

            <label className="flex flex-col gap-2 text-sm">
              대상
              <select
                className="rounded-lg border p-2"
                value={draft.targetKind}
                onChange={(event) => {
                  const kind = event.target.value as ActionTargetKind
                  setDraft((current) => ({
                    ...current,
                    targetKind: kind,
                    targetObjectTypeId:
                      kind === 'SYSTEM' ? '' : current.targetObjectTypeId,
                  }))
                }}
              >
                {Object.entries(targetKindLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-2 text-sm">
              대상 객체 유형
              <select
                className="rounded-lg border p-2"
                value={draft.targetObjectTypeId}
                disabled={draft.targetKind === 'SYSTEM'}
                aria-describedby={
                  draft.targetKind === 'SYSTEM'
                    ? 'action-system-help'
                    : undefined
                }
                onChange={(event) =>
                  update('targetObjectTypeId', event.target.value)
                }
              >
                <option value="">지정 안 함</option>
                {options.objectTypes.map((type) => (
                  <option key={type.id} value={type.id}>
                    {type.name} ({type.code})
                  </option>
                ))}
              </select>
            </label>

            <div className="actions-field">
              <label className="flex flex-col gap-2 text-sm">
                <span className="actions-field-label" data-required>
                  필요 권한
                </span>
                <select
                  className="rounded-lg border p-2"
                  name="requiredPermission"
                  aria-required={true}
                  aria-invalid={
                    invalidField === 'requiredPermission' || undefined
                  }
                  aria-describedby={
                    invalidField === 'requiredPermission'
                      ? 'action-field-requiredPermission-error'
                      : undefined
                  }
                  value={draft.requiredPermission}
                  onChange={(event) =>
                    update('requiredPermission', event.target.value)
                  }
                >
                  <option value="">권한 선택</option>
                  {permissions.map((code) => (
                    <option key={code} value={code}>
                      {code}
                    </option>
                  ))}
                </select>
              </label>
              {fieldError('requiredPermission')}
            </div>
          </div>
          {draft.targetKind === 'SYSTEM' && (
            <p id="action-system-help" className="text-sm text-slate-500">
              시스템 전체가 대상이므로 객체 유형을 지정하지 않습니다.
            </p>
          )}
          <label className="flex gap-2 text-sm">
            <input
              type="checkbox"
              checked={draft.requiresConfirmation}
              onChange={(event) =>
                update('requiresConfirmation', event.target.checked)
              }
            />
            실행 전에 사용자 확인 받기
          </label>
          <label className="flex gap-2 text-sm">
            <input
              type="checkbox"
              checked={draft.isActive}
              onChange={(event) => update('isActive', event.target.checked)}
            />
            사용
          </label>
          <details className="actions-disclosure">
            <summary>권한·실행 기준</summary>
            <p>
              계획·요청처럼 데이터를 만드는 기능은 사용자의 명시적 요청이 있을
              때 실행하고 이력에 남깁니다. 계산·조회 기능은 결과만 알려 줍니다.
              필요 권한은 사용자별로 따로 확인합니다.
            </p>
          </details>
        </section>
        <section
          id="action-panel-schema"
          role="tabpanel"
          aria-labelledby="action-tab-schema"
          hidden={tab !== 'schema'}
          className="actions-tabpanel"
        >
          <InputSchemaEditor
            value={draft.schema}
            onChange={(value) => update('schema', value)}
          />
          {fieldError('schema')}
        </section>
      </fieldset>
      {error && !invalidField && (
        <div id="action-form-error">
          <ErrorState error={error} />
        </div>
      )}
      {Boolean(blocked) && (
        <>
          <p className="text-sm">
            입력은 현재 화면에 보존됩니다. 최신 정의를 다시 조회하면 입력을
            초기화합니다.
          </p>
          <Button variant="secondary" onClick={onReload}>
            최신 정의 다시 조회·입력 초기화
          </Button>
        </>
      )}
      <p className="actions-save-help text-sm text-slate-500">
        통신 오류가 발생하면 자동 재전송하지 않습니다. 목록에서 처리 여부를
        확인한 뒤 다시 시도하세요.
      </p>
      <div className="actions-save-row">
        <p className="text-sm text-slate-500">
          {original ? `수정 기준 버전 ${original.rowVersion} · ` : ''}
          {dirty ? '저장하지 않은 변경 있음' : '변경사항 없음'}
        </p>
        <Button type="submit" pending={pending} disabled={Boolean(blocked)}>
          {editing ? '변경 저장' : 'Action 등록'}
        </Button>
      </div>
    </form>
  )
}

export default function ActionDefinitionEditorPage() {
  const { code } = useParams()
  const location = useLocation()
  const editing = code !== undefined
  const [reload, setReload] = useState(0)
  const query = useQuery({
    queryKey: ['actions', 'editor', code ?? 'new', reload],
    queryFn: async ({ signal }) => {
      const [definition, options] = await Promise.all([
        code === undefined ? undefined : fetchActionDefinition(code, signal),
        fetchActionEditorOptions(),
      ])
      return { definition, options }
    },
    retry: false,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  let content
  if (query.isPending || query.isFetching || query.isPaused)
    content = <LoadingState message="최신 Action 정의를 불러오는 중…" />
  else if (query.isError)
    content = (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  else if (query.data.definition?.actionMode === 'RECORD')
    content = (
      <RecordDefinitionView
        definition={query.data.definition}
        options={query.data.options}
      />
    )
  else
    content = (
      <ActionDefinitionForm
        key={`${location.pathname}:${reload}:${query.data.definition?.rowVersion ?? 0}`}
        original={query.data.definition}
        options={query.data.options}
        onReload={() => setReload((value) => value + 1)}
      />
    )
  return (
    <div className="management-page actions-definition-page space-y-5">
      <Link
        className="text-sm text-violet-700 underline"
        to={`/admin/actions${location.search}`}
      >
        Action 정의 목록으로 돌아가기
      </Link>
      <PageHeader
        title={editing ? 'Action 정의 상세·변경' : 'Action 등록'}
        description="실행 조건과 입력 형식을 확인하고 Action 정의를 설정합니다."
      />
      {content}
    </div>
  )
}
