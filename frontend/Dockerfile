# frontend 이미지 (5-1c). 2단계 빌드: node로 정적 파일을 만들고, nginx가 그 파일만 서빙한다.
# 빌드: docker build -t axcore-frontend:<커밋SHA> frontend/   (저장소 루트에서)
# 실행: 컨테이너 포트 80. 메인 nginx가 / → frontend:80 으로 넘긴다 (infra/nginx/conf.d/default.conf).

# 1단계: 빌드. Node 버전은 프론트 담당 기준(24.18.0)과 Jenkins MR 검사와 같게 둔다.
FROM node:24.18.0-alpine AS build
WORKDIR /app

# 의존성 먼저 설치해서 소스만 바뀐 빌드는 이 층을 재사용한다.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# 소스 복사 후 빌드 (tsc -b && vite build → dist/). 환경변수 없음: API 주소는 /api/v1 상대 경로.
COPY . .
RUN npm run build

# 2단계: 서빙. 메인 nginx와 같은 버전 계열을 쓴다.
FROM nginx:1.30-alpine

# 기본 설정을 우리 설정으로 교체 (SPA fallback · 캐시). 서버 정보 노출 끔.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
