# MSC Mission Console

React / TypeScript / Vite로 만든 로컬 FE 기능 확인판입니다.

새 컴퓨터에서는 [BE·FE 통합 실행 안내](../docs/backend/cross-machine-quickstart.md)를 따르세요.

```sh
npm install
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

기존 BE 서비스 및 포워딩, 저장소 루트의 `.local/msa.env`가 필요합니다.
[화면별 사용 안내와 검증 범위](../docs/frontend/functional-tour.md)를 먼저 읽으세요.

```sh
npm run build
npm run lint
npm run test
```

기본 화면에서 서비스 역할을 읽고, 요청 → 완료된 예시 열기 → 임무 흐름 → 제품 순서로
저장된 실제 합성 시나리오를 확인할 수 있습니다. 개발 프록시는 루프백 전용이며 운영 인증을 대신하지 않습니다.
