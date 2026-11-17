import type { TransformFunction } from '../features/mapping/api'

type CatalogEntry = TransformFunction & {
  argument:
    (NonNullable<TransformFunction['argument']> & { name: string }) | null
  inputTypes: string[]
  outputType: string
  example: { step: { op: string; arg: unknown }; input: string; output: string }
}

// API 041: backend mapping/domain/TransformFunction.ALL의 설명 계약. 변환 실행기는 아니다.
const text = ['STRING']
const textOrInteger = ['STRING', 'INTEGER']
export const transformData: CatalogEntry[] = [
  {
    op: 'STRIP_PREFIX',
    label: '접두어 빼기',
    description:
      '값 앞의 지정한 문자열을 뺍니다. 그 문자열로 시작하지 않으면 그대로 둡니다.',
    argument: { name: 'prefix', type: 'string', label: '뺄 접두어' },
    inputTypes: text,
    outputType: 'STRING',
    example: {
      step: { op: 'STRIP_PREFIX', arg: 'W' },
      input: 'W23',
      output: '23',
    },
  },
  {
    op: 'ADD_PREFIX',
    label: '접두어 붙이기',
    description: '값 앞에 지정한 문자열을 붙입니다.',
    argument: { name: 'prefix', type: 'string', label: '붙일 접두어' },
    inputTypes: text,
    outputType: 'STRING',
    example: {
      step: { op: 'ADD_PREFIX', arg: 'GP' },
      input: '0031',
      output: 'GP0031',
    },
  },
  {
    op: 'PAD_ZEROS',
    label: '앞에 0 채우기',
    description:
      '숫자로만 된 값의 앞을 0으로 채워 지정한 자릿수로 맞춥니다. 숫자가 아닌 글자가 있거나 이미 그 자릿수 이상이면 그대로 둡니다.',
    argument: { name: 'width', type: 'integer', label: '자릿수' },
    inputTypes: textOrInteger,
    outputType: 'STRING',
    example: { step: { op: 'PAD_ZEROS', arg: 4 }, input: '31', output: '0031' },
  },
  {
    op: 'UPPER',
    label: '대문자로',
    description: '영문자를 대문자로 바꿉니다.',
    argument: null,
    inputTypes: text,
    outputType: 'STRING',
    example: {
      step: { op: 'UPPER', arg: null },
      input: 'p101',
      output: 'P101',
    },
  },
  {
    op: 'TRIM',
    label: '앞뒤 공백 제거',
    description: '값 앞뒤의 공백을 뺍니다.',
    argument: null,
    inputTypes: text,
    outputType: 'STRING',
    example: {
      step: { op: 'TRIM', arg: null },
      input: ' P101 ',
      output: 'P101',
    },
  },
  {
    op: 'EXTRACT_NUMBER',
    label: '숫자만 남기기',
    description: '숫자와 소수점만 남깁니다. 숫자가 하나도 없으면 실패합니다.',
    argument: null,
    inputTypes: text,
    outputType: 'STRING',
    example: {
      step: { op: 'EXTRACT_NUMBER', arg: null },
      input: 'OD-2608-012',
      output: '2608012',
    },
  },
  {
    op: 'TO_INTEGER',
    label: '정수로',
    description: '앞뒤 공백을 빼고 정수로 바꿉니다. 정수가 아니면 실패합니다.',
    argument: null,
    inputTypes: text,
    outputType: 'INTEGER',
    example: {
      step: { op: 'TO_INTEGER', arg: null },
      input: ' 0031 ',
      output: '31',
    },
  },
  {
    op: 'TO_DECIMAL',
    label: '소수로',
    description:
      '앞뒤 공백을 빼고 소수를 포함한 숫자로 바꿉니다. 숫자가 아니면 실패합니다.',
    argument: null,
    inputTypes: textOrInteger,
    outputType: 'DECIMAL',
    example: {
      step: { op: 'TO_DECIMAL', arg: null },
      input: '0012.50',
      output: '12.50',
    },
  },
  {
    op: 'TO_DATE',
    label: '날짜로',
    description:
      '2026-10-31, 2026.10.31, 2026/10/31, 20261031 형식을 2026-10-31로 바꿉니다. 월·일은 두 자리여야 하고, 없는 날짜면 실패합니다.',
    argument: null,
    inputTypes: text,
    outputType: 'DATE',
    example: {
      step: { op: 'TO_DATE', arg: null },
      input: '2026/10/31',
      output: '2026-10-31',
    },
  },
  {
    op: 'MAP_VALUE',
    label: '대응표로 바꾸기',
    description:
      '대응표에 있는 값을 짝지은 값으로 바꿉니다. 대응표에 없는 값이면 실패합니다.',
    argument: {
      name: 'mapping',
      type: 'object',
      label: '대응표(원래 값 → 바꿀 값)',
    },
    inputTypes: text,
    outputType: 'STRING',
    example: {
      step: { op: 'MAP_VALUE', arg: { RUN: '가동' } },
      input: 'RUN',
      output: '가동',
    },
  },
  {
    op: 'MAP_KNOWN',
    label: '대응표에 있는 값만 바꾸기',
    description:
      '대응표에 있는 값만 짝지은 값으로 바꾸고, 대응표에 없는 값은 비웁니다. 마지막 단계에 두면 제한 컬럼에서도 일반 속성을 뽑을 수 있습니다.',
    argument: {
      name: 'mapping',
      type: 'object',
      label: '대응표(원래 값 → 바꿀 값)',
    },
    inputTypes: text,
    outputType: 'STRING',
    example: {
      step: { op: 'MAP_KNOWN', arg: { 보전팀: 'DP40' } },
      input: '보전팀',
      output: 'DP40',
    },
  },
]
