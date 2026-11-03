# Frontend

온톨로지 플랫폼의 웹 화면입니다. 업무 데이터 탐색(Explorer), AI 업무 도우미(Agent), Action 실행, 감사 이력 조회 화면을 담습니다.

## 기술 스택

| 역할           | 도구                           |
| -------------- | ------------------------------ |
| 화면           | React 19, TypeScript           |
| 개발 서버·빌드 | Vite                           |
| 스타일         | Tailwind CSS                   |
| 화면 이동      | React Router                   |
| 서버 데이터    | TanStack Query                 |
| 가짜 API       | MSW (Mock Service Worker)      |
| 테스트         | Vitest, Testing Library, jsdom |
| 코드 검사·정리 | ESLint, Prettier               |

## 시작하기

Node.js 24와 npm을 사용합니다. 모든 명령은 `frontend` 폴더에서 실행합니다.

```bash
npm install
npm run dev
```

브라우저에서 `http://localhost:5173`을 엽니다. 개발 서버에서는 MSW가 켜져서 백엔드 없이도 `/api/...` 요청에 가짜 응답을 돌려줍니다.

## 명령어

| 명령                   | 하는 일                                   |
| ---------------------- | ----------------------------------------- |
| `npm run dev`          | 개발 서버 실행                            |
| `npm run build`        | 타입 검사 후 배포용 파일을 `dist/`에 생성 |
| `npm run preview`      | 빌드 결과를 로컬에서 확인                 |
| `npm test`             | 테스트 실행 (감시 모드, `q`로 종료)       |
| `npm run lint`         | ESLint로 코드 실수 검사                   |
| `npm run format`       | Prettier로 코드 모양 정리                 |
| `npm run format:check` | 모양이 규칙과 다른 파일만 알려줌          |

변경을 마치면 `npm run lint` → `npm test` → `npm run format:check` 순서로 확인합니다.

## 폴더 구조

> 프론트엔드 초안입니다. 팀 전체 규칙(`.ai/rules/architecture.md`)이 정해지면 그에 맞춰 수정합니다.

```text
src/
├─ main.tsx          앱 시작점 (MSW 켜기, TanStack Query 연결, 화면 그리기)
├─ App.tsx           라우트 (주소 → 화면)
├─ index.css         전역 스타일 (Tailwind)
├─ features/         기능(메뉴)별 폴더
│  ├─ explorer/      업무 데이터 탐색
│  └─ agent/         AI 업무 도우미
├─ components/       두 개 이상의 기능이 같이 쓰는 공통 부품
│  ├─ AppLayout.tsx        모든 화면의 공통 틀 (헤더, 주 메뉴)
│  ├─ PagePlaceholder.tsx  아직 만들지 않은 화면 자리
│  └─ NotFoundPage.tsx     없는 주소 안내
├─ api/              모든 기능이 같이 쓰는 API 공통 코드
│  ├─ client.ts      요청 보내기 (`/api/v1` 주소, 오류 응답 처리)
│  └─ types.ts       공통 응답 형식 (`data`, `meta`, `error`)
├─ mocks/            MSW 가짜 API
│  ├─ handlers.ts    요청별 가짜 응답
│  ├─ browser.ts     개발 서버용 (`npm run dev`)
│  └─ node.ts        테스트용 (`npm test`)
└─ test/
   └─ setup.ts       테스트 공통 준비
```

### 규칙

1. **기능별로 나눕니다.** 메뉴 하나(Explorer, Agent, Action, Audit 등)마다 `features/<기능>/` 폴더를 하나 둡니다. 담당자가 나뉘어도 서로 다른 폴더에서 작업하게 됩니다.
2. **한 기능에서만 쓰는 것은 그 기능 폴더에 둡니다.** 화면 조각, 타입, API 호출 함수가 모두 해당합니다.
3. **두 기능 이상이 같이 쓰게 되면 `components/`로 옮깁니다.** 처음부터 공통으로 만들지 않고, 실제로 두 번째 사용처가 생길 때 옮깁니다.
4. **주소 하나에 대응하는 화면은 `<기능>Page.tsx`로 이름 짓습니다.** 예: `ExplorerPage.tsx`, `AgentPage.tsx`. 라우트 연결은 `App.tsx`에서만 합니다.
5. **테스트 파일은 대상 파일 옆에 둡니다.** `ExplorerPage.tsx`의 테스트는 `ExplorerPage.test.tsx`입니다.
6. **파일 이름**: 컴포넌트는 `PascalCase.tsx`(`ObjectCard.tsx`), 그 밖의 코드는 `camelCase.ts`(`handlers.ts`, `types.ts`)로 씁니다.
7. **가짜 응답은 `mocks/handlers.ts`에 모읍니다.** 개발 서버와 테스트가 같은 응답을 씁니다. 파일이 커지면 `mocks/handlers/<기능>.ts`로 나눕니다.
8. **API 호출은 기능 폴더의 `api.ts`에 모읍니다.** 화면 컴포넌트에서 `fetch`를 직접 쓰지 않고, `api/client.ts`를 거쳐 호출합니다. 가짜 응답과 타입은 [C106 API 관리 DB](https://app.notion.com/p/fb5c0ebaff7d446991d1c5adb0fdc5a8?v=405bdf14d2d881c58c39000c1d8d12b0)의 명세와 같은 모양으로 맞춥니다.

## 화면 주소

`App.tsx`에 연결된 주소입니다. 준비 중인 화면은 `PagePlaceholder`로 자리만 잡아 두었고, 담당자가 화면을 만들면 해당 줄의 `element`를 실제 화면으로 바꿉니다.

| 주소              | 화면                  | 메뉴 위치        | 관련 이슈      |
| ----------------- | --------------------- | ---------------- | -------------- |
| `/login`          | 로그인 (공통 틀 밖)   | 없음             | S15P31C106-103 |
| `/`               | `/explorer`로 이동    | 없음             |                |
| `/explorer`       | 업무 데이터           | 주 메뉴          | S15P31C106-154 |
| `/agent`          | AI 업무 도우미        | 주 메뉴          | S15P31C106-149 |
| `/admin/sources`  | 데이터 연결           | 주 메뉴 (관리자) | S15P31C106-105 |
| `/admin/rules`    | 연결 규칙             | 주 메뉴 (관리자) | S15P31C106-110 |
| `/audit`          | 실행 이력             | 헤더             | S15P31C106-237 |
| `/admin/accounts` | 계정·권한             | 헤더 (관리자)    | S15P31C106-226 |
| 그 밖의 주소      | 페이지를 찾을 수 없음 | 없음             |                |

- 관리자 화면은 `/admin/` 아래에 둡니다. 역할에 따라 메뉴를 숨기고 접근을 막는 것은 S15P31C106-104에서 처리합니다.
- 운영 서버에서 하위 주소를 새로고침해도 화면이 뜨려면 nginx가 모르는 주소를 `index.html`로 돌려줘야 합니다(S15P31C106-53).

## 코드 모양 (Prettier)

코드 모양은 사람이 맞추지 않고 Prettier에 맡깁니다. 규칙은 `.prettierrc.json`에 있습니다.

| 규칙             | 값                                 | 예                                  |
| ---------------- | ---------------------------------- | ----------------------------------- |
| 한 줄 최대 길이  | 80자 (`printWidth`)                | 넘으면 여러 줄로 나눔               |
| 들여쓰기         | 공백 2칸 (`tabWidth`, `useTabs`)   |                                     |
| 세미콜론         | 쓰지 않음 (`semi`)                 | `const a = 1`                       |
| 문자열 따옴표    | 작은따옴표 (`singleQuote`)         | `'정상'`                            |
| JSX 속성 따옴표  | 큰따옴표 (`jsxSingleQuote`)        | `<input type="password" />`         |
| 객체 키 따옴표   | 꼭 필요할 때만 (`quoteProps`)      | `{ id: 'PR-03' }`                   |
| 끝 쉼표          | 여러 줄이면 항상 (`trailingComma`) | 마지막 항목 뒤에도 `,`              |
| 중괄호 안 공백   | 넣음 (`bracketSpacing`)            | `{ id }`                            |
| JSX 닫는 `>`     | 새 줄에 (`bracketSameLine`)        |                                     |
| 화살표 함수 괄호 | 항상 (`arrowParens`)               | `(item) => item.id`                 |
| 줄바꿈 문자      | 파일에 있던 것 유지 (`endOfLine`)  | Windows·Mac 차이로 생기는 경고 방지 |

- 저장 전에 `npm run format`을 실행하거나, VS Code의 Prettier 확장에서 저장 시 자동 정리를 켭니다.
- `public/mockServiceWorker.js`, `package-lock.json`은 도구가 만드는 파일이라 `.prettierignore`로 제외합니다.
- 모양에 관한 ESLint 규칙은 `eslint-config-prettier`로 꺼서 두 도구가 충돌하지 않게 합니다.

## 가짜 API (MSW)

- 개발 서버(`npm run dev`)에서만 켜집니다. 배포용 빌드에는 들어가지 않습니다.
- 테스트에서는 `mocks/handlers.ts`에 없는 요청이 오면 실패로 처리합니다.
- `public/mockServiceWorker.js`는 MSW가 생성한 파일입니다. 직접 고치지 않습니다.

## 팀 결정이 필요한 항목

- 백엔드 개발 서버 주소·포트 (인증은 API 명세 001에 따라 세션 쿠키 + `X-XSRF-TOKEN` 헤더)

## 배포

- `develop`에 머지되면 Jenkins가 `frontend/` 변경을 감지해 `frontend/Dockerfile`로 이미지를 굽고 서버의 frontend 컨테이너를 교체합니다. 헬스체크를 통과하지 못하면 직전 버전으로 자동 롤백됩니다. 절차는 `infra/README.md`의 "자동 배포"를 참고합니다.
- 서비스 주소는 `https://k15c106.p.ssafy.io/` 이고, `/api/v1` 요청은 메인 nginx가 backend로 넘깁니다.
