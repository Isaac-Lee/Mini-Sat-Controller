# MSC Mission Console

React / TypeScript / Vite로 만든 로컬 FE 기능 확인판입니다.

새 컴퓨터에서는 [BE·FE 통합 실행 안내](../docs/backend/cross-machine-quickstart.md)를 따르세요.

```sh
npm install
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

기존 BE 서비스 및 포워딩, 저장소 루트의 `.local/msa.env`가 필요합니다.

백엔드·DB 없이 화면만 둘러보려면 데모 모드로 엽니다.

```sh
npm install
npm run demo   # http://127.0.0.1:5173/?demo
```

데모 모드에서는 브라우저 안의 가짜 백엔드(`src/demo/backend.ts`)가 모든 API에 응답하고 네트워크로는
아무 요청도 보내지 않습니다. 실제 화면을 그대로 쓰므로 요청 제출·수정·취소, 궤도 가져오기·예측,
임무 흐름 근거, 2인 복구 승인, 제품 조회를 눌러볼 수 있습니다. 새 요청은 몇 초 간격으로
RECEIVED → ACCEPTED → SCHEDULED → FULFILLED로 진행하고, `중구`처럼 여러 곳이 일치하는 지명은
CLARIFICATION_NEEDED가 됩니다. 상단의 "서비스 호출 기록"은 각 클릭이 어느 서비스 API에 닿는지
보여줍니다. 모든 값은 합성이며 새로고침하면 처음 상태로 돌아갑니다.

요청을 선택하면 **예상 촬영 가능 시각**이 표시됩니다. AOI 중심점이 고도각 ≥ 10°, off-nadir ≤ 30°로
보이는 향후 7일의 통과 구간이며, **지도에서 보기**를 누르면 해당 통과를 지도에 보라색 굵은 선으로 표시합니다.
실제 모드에서는 Flight Dynamics(Orekit) 접근 예측 API가 계산하고, 데모 모드에서는 데모 궤도 요소(강하
교점 지방시 10:30 태양동기궤도)를 브라우저 SGP4(`satellite.js`, `src/orbit/sgp4.ts`)로 전파해 계산합니다.
데모의 촬영 후보도 같은 통과 중 태양 고도 ≥ 10°인 것만 PROPOSED로 둡니다. AOI 전체 커버리지나 확정 일정은
아닙니다.
[화면별 사용 안내와 검증 범위](../docs/frontend/functional-tour.md)를 먼저 읽으세요.

```sh
npm run build
npm run lint
npm run test
```

기본 화면에서 서비스 역할을 읽고, 요청 → 완료된 예시 열기 → 임무 흐름 → 제품 순서로
저장된 실제 합성 시나리오를 확인할 수 있습니다. 개발 프록시는 루프백 전용이며 운영 인증을 대신하지 않습니다.
