import Button from '../../components/Button'
import { transformLabels, type TransformDraft } from './ruleDraft'
import RuleSelect from './RuleSelect'

type TransformOption = {
  op: string
  label: string
  argument: { label: string } | null
}

const defaultFunctions: TransformOption[] = Object.entries(transformLabels).map(
  ([op, label]) => {
    const argumentLabels: Record<string, string> = {
      STRIP_PREFIX: '접두어',
      ADD_PREFIX: '접두어',
      PAD_ZEROS: '자릿수',
      MAP_VALUE: '값 대응표',
    }
    return {
      op,
      label,
      argument: argumentLabels[op] ? { label: argumentLabels[op] } : null,
    }
  },
)

export default function TransformEditor({
  label,
  steps,
  onChange,
  functions = defaultFunctions,
  emptyMessage = '변환 없이 원래 값을 비교합니다.',
  searchableFunctions = false,
}: {
  label: string
  steps: TransformDraft[]
  onChange: (steps: TransformDraft[]) => void
  functions?: TransformOption[]
  emptyMessage?: string
  searchableFunctions?: boolean
}) {
  function replace(index: number, step: TransformDraft) {
    onChange(
      steps.map((value, position) => (position === index ? step : value)),
    )
  }
  return (
    <fieldset className="min-w-0 space-y-3 rounded-lg bg-slate-50 p-3">
      <legend className="text-sm font-medium">{label}</legend>
      {!steps.length && (
        <p className="text-xs text-slate-500">{emptyMessage}</p>
      )}
      {steps.map((step, index) => {
        const current = functions.find((option) => option.op === step.op)
        return (
          <div
            key={index}
            className="space-y-2 rounded border border-slate-200 bg-white p-3"
          >
            {searchableFunctions ? (
              <RuleSelect
                label={`${label} ${index + 1} 함수`}
                value={step.op}
                allowEmpty={false}
                options={functions.map((option) => ({
                  value: option.op,
                  name: option.label,
                  code: option.op,
                }))}
                onChange={(op) => replace(index, { op, arg: '' })}
              />
            ) : (
              <label className="block text-sm">
                {label} {index + 1} 함수
                <select
                  className="mt-1 w-full rounded border p-2"
                  value={step.op}
                  onChange={(event) =>
                    replace(index, { op: event.target.value, arg: '' })
                  }
                >
                  {!current && (
                    <option value={step.op}>{step.op} (사용할 수 없음)</option>
                  )}
                  {functions.map((option) => (
                    <option key={option.op} value={option.op}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </label>
            )}
            {current?.argument && (
              <label className="block text-sm">
                {current.argument.label}
                <input
                  className="mt-1 w-full rounded border p-2"
                  value={step.arg}
                  onChange={(event) =>
                    replace(index, { ...step, arg: event.target.value })
                  }
                  placeholder={
                    step.op === 'MAP_VALUE' ? '{"PR03":"PR-03"}' : undefined
                  }
                />
              </label>
            )}
            <div className="flex flex-wrap gap-2">
              <Button
                variant="secondary"
                disabled={index === 0}
                onClick={() => {
                  const next = [...steps]
                  ;[next[index - 1], next[index]] = [
                    next[index],
                    next[index - 1],
                  ]
                  onChange(next)
                }}
              >
                위로
              </Button>
              <Button
                variant="secondary"
                disabled={index === steps.length - 1}
                onClick={() => {
                  const next = [...steps]
                  ;[next[index], next[index + 1]] = [
                    next[index + 1],
                    next[index],
                  ]
                  onChange(next)
                }}
              >
                아래로
              </Button>
              <Button
                variant="secondary"
                onClick={() =>
                  onChange(steps.filter((_, position) => position !== index))
                }
              >
                변환 삭제
              </Button>
            </div>
          </div>
        )
      })}
      <Button
        variant="secondary"
        disabled={!functions.length}
        onClick={() => {
          const initial =
            functions.find(({ op }) => op === 'TRIM') ?? functions[0]
          if (initial) onChange([...steps, { op: initial.op, arg: '' }])
        }}
      >
        {label} 추가
      </Button>
    </fieldset>
  )
}
