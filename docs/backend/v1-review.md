# V1 백엔드 기능 확인

이 버전은 실제 API·PostgreSQL·RabbitMQ·로컬 MinIO·독립 K8s 서비스를 사용합니다.
위성·지상국의 동작과 촬영 산출물은 시뮬레이션입니다. 비행역학은 현재 구현된
근사 모델로 확인하며 정밀 알고리즘 교체는 후속 작업입니다.

## 실행

저장소 루트에서, 기존 로컬 K8s와 API 포워딩이 실행 중인 상태로:

```sh
python3 scripts/verify-v1.py
```

Python 3와 kubectl이 필요합니다. 인증 정보는 기존 `.local/msa.env`에서 읽습니다.
스크립트는 매번 별도 합성 위성·요청·시나리오를 생성하며, 성공한 요청과 산출물을
보존합니다. 기존 볼륨이나 인증 파일을 초기화하지 마세요.
API 연결이 없을 때는 [로컬 실행 안내](local-runtime.md)의 포워딩을 실행합니다.
동일 포트에 중복 실행하지 마세요. 최초 환경 구성은 [K8s 안내](../../deploy/k8s/README.md)를 따릅니다.

## 확인할 내용

| 단계 | 실제로 확인하는 동작 |
| --- | --- |
| SPACEEYE-T1 | NORAD 63229의 보존된 공개 GP와 파생 TLE, 원본 해시·epoch·텍스트 다운로드 |
| 요청·계획 | 요청 접수, 자동 궤도 접근 검색, 후보·샘플 카메라 결과·자원 평가 |
| 일정·명령 | 요청에 연결된 후보 선택, 일정 저장, Control 명령 준비·승인·전달 |
| 위성 시뮬레이터 | IMAGE 실행, 가상 시간 진행, ACK 유실 UNKNOWN 및 재조회 복구 |
| 지상국 시뮬레이터 | 접촉 예측·예약, DOWNLINK, 연결 끊김과 복구, 실제 합성 바이트 수신 |
| 수신·산출물 | Acquisition 완료, Product 소유 S3 저장, 원본 해시 일치, 진단 PNG |
| 요청 결과 | 운영자 완료 등록, 요청자 결과 조회·원본/PNG 다운로드, FULFILLED 표시 |
| 관측·안전 | 합성 텔레메트리, 안전 동결, 서로 다른 운영자 2인의 복구 승인 |
| MSA | 12개 독립 Deployment, Planning/Acquisition 각 2 replica, Planning 동일 영속 결과 |

공개 궤도 확인과 합성 운영 시나리오는 구분됩니다. 합성 촬영 위성은 공개 GP만으로
알 수 없는 SPACEEYE-T1 실제 카메라·전력·통신 사양을 추정하지 않습니다.
공개 궤도 수집·전파/접근 API는 [공개 궤도 안내](public-orbits.md), TLE 표현 범위는
[TLE 안내](public-tle-export.md)를 참고하세요. V1 명령은 기존 보존 GP를 사용하며
외부 공급자에 강제 재수집하지 않습니다.

## 저장된 결과 열기

`.local/v1-functional-verification.json`에 요청·계획·일정·명령·시나리오·예약·제품 ID와
검증 결과가 저장됩니다. `requestResult.result.sources`의 `contentPath`와 `previewPath`는
Tasking의 요청자 인증 다운로드 경로입니다. 진단 이미지는 `.local/v1-request-preview.png`입니다.

요청자 계정으로 다음 localhost API를 조회할 수 있습니다. 비밀번호는 `.local/msa.env`의
`MSC_LOCAL_REQUESTER_PASSWORD`이며 문서·Git·공유 링크에 복사하지 않습니다.

```text
http://127.0.0.1:8101/api/requests/{requestId}
http://127.0.0.1:8101/api/requests/{requestId}/simulation-result
http://127.0.0.1:8101/api/requests/{requestId}/simulation-result/sources/{receiptId}/content
http://127.0.0.1:8101/api/requests/{requestId}/simulation-result/sources/{receiptId}/preview
http://127.0.0.1:8103/api/tracked-satellites/63229/tle
```

완료 등록은 운영자 전용 `POST /api/requests/{requestId}/simulation-result`입니다.
`Idempotency-Key`와 `expectedVersion`, `productId`, `imageLoadId`, `downlinkLoadId`,
`reviewReference`를 받습니다. 요청 버전·명령 실행·다운링크 계획·제품 출처를 대조한 뒤
결과 저장과 요청 상태 변경을 하나의 트랜잭션으로 처리합니다.
`FULFILLED`의 사유는 `SIMULATION_V1_COMPLETE`이며, 결과의 `completionBasis`는
물리 영상 품질을 평가하지 않았음을 명시합니다. PNG는 합성 바이트의 진단 표시입니다.

이 안내는 BE API 기준입니다. 별도 작업 중인 프런트엔드의 모든 화면 동작이나
실위성 교신, 연속 노출 정확도, 실제 영상 품질, 운영 환경 적합성을 검증한 것은 아닙니다.


## 이번 검증 기록

2026-09-12의 단독 안내 명령이 종료 코드 0으로 통과했습니다.
요청 `0e1eed4c-e2a4-4e6c-9909-5066279f846e`, 계획
`4e343d34-28f3-30b1-b0ff-698a5ac17856`, 제품
`e36a6328-ce97-452a-b193-e66092656ff1`을 보존했습니다.
원본 1,000,000 bytes와 PNG 651 bytes를 요청자 API로 내려받아 해시를 대조했습니다.
로그는 로컬 `/private/tmp/msc-v1-functional-final.log`입니다.

변경 영역 검증은 Planning/Control 13건과 Tasking 결과 API 4건이 통과했습니다.
이전 전체 reactor 400건 통과 이후 이번 전체 재검증은 로컬 자원 경쟁으로 중단했습니다.
중단 전 완료된 224건에 실패는 없었지만 전체 reactor 통과로 계산하지 않습니다.
초기 안내 실행의 포워딩 끊김과 승인 시각 만료는 성공으로 기록하지 않았고,
부하 복구 및 합성 시각 여유 조정 후 위의 단독 실행으로 최종 경로를 확인했습니다.
