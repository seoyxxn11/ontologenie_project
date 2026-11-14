import {
  useIsMutating,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import FormDialog from '../../components/FormDialog'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import {
  buildTransform,
  toTransformDraft,
  transformLabels,
} from '../review/ruleDraft'
import TransformEditor from '../review/TransformEditor'
import type { SourceColumn } from '../sources/types'
import {
  fetchTransformFunctions,
  fetchAllPropertyMappings,
  mappingSaveMessage,
  previewableMappings,
  savePropertyMapping,
  type Definition,
  type ObjectMapping,
  type PropertyMapping,
  type TransformStep,
} from './api'
import MappingPreview from './MappingPreview'
import {
  PreviewDraftError,
  type MappingPreviewAccess,
  type MappingPreviewDraft,
} from './previewModel'

export default function PropertyMappingDialog({
  sourceId,
  parent,
  existing,
  columns,
  properties,
  onClose,
  onReload,
  onSaved,
  previewAccess,
  previewReadable = false,
}: {
  sourceId: number
  parent: ObjectMapping
  existing?: PropertyMapping
  columns: SourceColumn[]
  properties: Definition[]
  onClose: () => void
  onReload: () => void
  onSaved: (message: string) => void
  previewAccess?: MappingPreviewAccess
  previewReadable?: boolean
}) {
  const [columnId, setColumnId] = useState(existing?.sourceColumnId ?? 0)
  const [propertyId, setPropertyId] = useState(
    existing?.propertyDefinitionId ?? 0,
  )
  const [steps, setSteps] = useState(
    () => existing?.transformSteps.map(toTransformDraft) ?? [],
  )
  const [validation, setValidation] = useState('')
  const fieldId = useId()
  const client = useQueryClient()
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const catalog = useQuery({
    queryKey: ['source-mappings', 'transforms'],
    queryFn: fetchTransformFunctions,
  })
  const functions =
    catalog.data?.filter(({ op }) => Object.hasOwn(transformLabels, op)) ?? []
  const saving =
    useIsMutating({
      mutationKey: [
        'form-save',
        'property-mapping',
        parent.id,
        existing?.id ?? 'new',
      ],
    }) > 0
  const save = useMutation({
    mutationKey: [
      'form-save',
      'property-mapping',
      parent.id,
      existing?.id ?? 'new',
    ],
    meta: {
      formSave: {
        label: existing
          ? `속성 매핑 #${existing.id} 저장`
          : `객체 매핑 #${parent.id} 속성 매핑 등록`,
        href: `/admin/sources?source=${sourceId}&tab=mapping`,
      },
    },
    retry: false,
    mutationFn: (transformSteps: TransformStep[]) =>
      savePropertyMapping(
        {
          sourceMappingId: parent.id,
          sourceResourceId: parent.sourceResourceId,
          propertyDefinitionId: propertyId,
          sourceColumnId: columnId,
          transformSteps,
        },
        existing,
      ),
    onSuccess: (result) => {
      void client.invalidateQueries({
        queryKey: ['source-mappings', 'properties'],
      })
      if (mounted.current) onSaved(mappingSaveMessage(result))
    },
  })
  const stale =
    save.error instanceof ApiError && save.error.code === 'VERSION_CONFLICT'
  const hasPath = existing?.sourcePath != null
  const selectedColumnExists = columns.some((column) => column.id === columnId)
  const selectedPropertyExists = properties.some(
    (property) => property.id === propertyId,
  )

  function currentTransforms() {
    if (!selectedColumnExists || !selectedPropertyExists)
      throw new PreviewDraftError(
        '현재 사용할 수 있는 원천 컬럼과 대상 속성을 선택해 주세요.',
      )
    return steps.map((step) => {
      if (!functions.some((fn) => fn.op === step.op))
        throw new PreviewDraftError(
          '현재 사용할 수 있는 변환 함수를 선택해 주세요.',
        )
      try {
        return buildTransform(step)
      } catch (error) {
        throw new PreviewDraftError(
          error instanceof Error ? error.message : '변환 단계를 확인해 주세요.',
        )
      }
    })
  }

  async function preparePreview(): Promise<MappingPreviewDraft> {
    const transformSteps = currentTransforms()
    if (
      properties.find((property) => property.id === propertyId)?.sensitivity ===
      'RESTRICTED'
    )
      throw new PreviewDraftError(
        '민감 속성은 변환 미리보기에서 값을 읽지 않습니다. 저장하면 민감 속성으로 적재됩니다.',
      )
    // 다른 민감 속성의 매핑은 미리보기에서 뺀다(서버가 민감 속성이 섞인 요청 전체를 거절한다)
    const saved = previewableMappings(
      await fetchAllPropertyMappings(parent.id),
      properties,
    )
    const { sourceResourceId, objectTypeId, keyColumns, rowFilter, isActive } =
      parent
    return {
      sourceResourceId,
      objectTypeId,
      keyColumns,
      rowFilter,
      isActive,
      sourceMappingId: parent.id,
      rowVersion: parent.rowVersion,
      properties: [
        ...saved
          .filter((mapping) => mapping.id !== existing?.id)
          .map(
            ({
              propertyDefinitionId,
              sourceColumnId,
              sourcePath,
              transformSteps,
            }) => ({
              propertyDefinitionId,
              sourceColumnId,
              sourcePath,
              transformSteps,
            }),
          ),
        {
          propertyDefinitionId: propertyId,
          sourceColumnId: columnId,
          sourcePath: null,
          transformSteps,
        },
      ],
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (
      client.isMutating({
        mutationKey: [
          'form-save',
          'property-mapping',
          parent.id,
          existing?.id ?? 'new',
        ],
      }) ||
      stale ||
      hasPath ||
      !catalog.isSuccess
    )
      return
    let transformSteps: TransformStep[]
    try {
      transformSteps = currentTransforms()
    } catch (error) {
      if (error instanceof Error) setValidation(error.message)
      return
    }
    setValidation('')
    save.mutate(transformSteps)
  }

  const dirty =
    columnId !== (existing?.sourceColumnId ?? 0) ||
    propertyId !== (existing?.propertyDefinitionId ?? 0) ||
    JSON.stringify(steps) !==
      JSON.stringify(existing?.transformSteps.map(toTransformDraft) ?? [])
  const columnError =
    validation && !selectedColumnExists ? '원천 컬럼을 선택해 주세요.' : ''
  const propertyError =
    validation && !selectedPropertyExists ? '대상 속성을 선택해 주세요.' : ''
  return (
    <FormDialog
      title={existing ? '속성 매핑 편집' : '속성 매핑 등록'}
      size="wide"
      dirty={dirty}
      pending={saving}
      error={save.error}
      onClose={onClose}
    >
      <form noValidate onSubmit={submit} className="mt-4 space-y-4 text-sm">
        {hasPath && (
          <p role="alert">
            기존 문서 경로가 있는 매핑입니다. 문서 경로를 지원하는 화면에서
            편집해 주세요.
          </p>
        )}
        <fieldset disabled={saving || stale || hasPath} className="space-y-4">
          <div>
            <label className="block">
              원천 컬럼 (필수)
              <select
                required
                className="mt-1 w-full rounded border border-slate-300 p-2"
                aria-label="원천 컬럼"
                aria-invalid={!!columnError || undefined}
                aria-describedby={
                  columnError ? `${fieldId}-columnId` : undefined
                }
                value={columnId}
                onChange={(event) => {
                  setColumnId(Number(event.target.value))
                  setValidation('')
                }}
              >
                <option value={0}>원천 컬럼 선택</option>
                {columnId > 0 && !selectedColumnExists && (
                  <option value={columnId} disabled>
                    기존 컬럼 #{columnId} (사용할 수 없음)
                  </option>
                )}
                {columns.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.columnName}
                  </option>
                ))}
              </select>
            </label>
            {columnError && (
              <p id={`${fieldId}-columnId`} className="ui-field-error">
                {columnError}
              </p>
            )}
          </div>
          <div>
            <label className="block">
              대상 속성 (필수)
              <select
                required
                className="mt-1 w-full rounded border border-slate-300 p-2"
                aria-label="대상 속성"
                aria-invalid={!!propertyError || undefined}
                aria-describedby={
                  propertyError ? `${fieldId}-propertyId` : undefined
                }
                value={propertyId}
                onChange={(event) => {
                  setPropertyId(Number(event.target.value))
                  setValidation('')
                }}
              >
                <option value={0}>대상 속성 선택</option>
                {propertyId > 0 && !selectedPropertyExists && (
                  <option value={propertyId} disabled>
                    기존 속성 #{propertyId} (사용할 수 없음)
                  </option>
                )}
                {properties.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name} · {item.code}
                  </option>
                ))}
              </select>
            </label>
            {propertyError && (
              <p id={`${fieldId}-propertyId`} className="ui-field-error">
                {propertyError}
              </p>
            )}
          </div>
          {(!columns.length || !properties.length) && (
            <EmptyState message="매핑에 사용할 컬럼과 속성이 필요합니다." />
          )}
          {catalog.isPending && (
            <LoadingState message="변환 함수를 불러오는 중…" />
          )}
          {catalog.isError && (
            <ErrorState
              error={catalog.error}
              onRetry={() => void catalog.refetch()}
            />
          )}
          {catalog.isSuccess && (
            <TransformEditor
              label="변환 단계"
              steps={steps}
              onChange={setSteps}
              functions={functions}
              emptyMessage="변환 없이 원천 값을 사용합니다."
            />
          )}
        </fieldset>
        {validation && (
          <p role="alert" className="text-red-800">
            {validation}
          </p>
        )}
        {save.isError && <ErrorState error={save.error} />}
        {stale && (
          <Button variant="secondary" onClick={onReload}>
            최신 매핑 다시 불러오기
          </Button>
        )}
        <MappingPreview
          key={JSON.stringify([
            columnId,
            propertyId,
            steps,
            parent,
            existing,
            previewAccess?.policyDescription,
            saving,
            stale,
          ])}
          access={previewAccess}
          prepareDraft={preparePreview}
          disabled={
            !previewReadable || saving || stale || hasPath || !catalog.isSuccess
          }
        />
        <div className="flex justify-end">
          <Button
            type="submit"
            pending={saving}
            disabled={
              stale ||
              hasPath ||
              !catalog.isSuccess ||
              !columns.length ||
              !properties.length
            }
          >
            {saving ? '저장 중…' : '저장'}
          </Button>
        </div>
      </form>
    </FormDialog>
  )
}
