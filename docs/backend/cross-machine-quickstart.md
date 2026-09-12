# 다른 컴퓨터에서 BE와 FE 함께 실행하기

이 문서는 **새로 clone한 컴퓨터 자체에서** BE 12개와 FE를 함께 실행하는 로컬
실험 절차입니다. 기존 노트북의 서버·인증 파일·DB 복사가 필요하지 않습니다.
실제 PostgreSQL/RabbitMQ/MinIO와 Java API를 사용하되, 위성 운영과 제품은 합성
시뮬레이션입니다. FE의 전체 기능·한계는 [FE 둘러보기](../frontend/functional-tour.md)를 참고하세요.

배포 기본은 [Kubernetes](../../deploy/k8s/README.md)입니다. 아래는 빠른 기능 실험을
위한 기존 host JVM 실행 방식이며, K8s 가용성·복제 검증을 대신하지 않습니다.
같은 컴퓨터에서 두 방식을 동시에 실행하지 마세요.

## 1. 준비

macOS 또는 Linux의 Bash에서 실행합니다. Windows는 WSL2와 Docker 통합 환경을
사용하세요(이번 작업에서 WSL2 실행은 검증하지 않았습니다).

- Git, JDK **21** 및 해당 JDK를 가리키는 `JAVA_HOME`
- Node.js **22.12 이상인 22 LTS** 또는 **24 LTS**, npm
- Python 3, Docker Engine/Desktop와 Compose v2, curl, OpenSSL, `shasum`
- Maven Central, npm, 컨테이너 레지스트리, Orekit 자료 다운로드를 위한 인터넷

12개 JVM과 DB·broker·object store가 함께 실행됩니다. 초기 실험 예산으로 컴퓨터
메모리 16 GiB 이상, CPU 8 logical cores, 여유 디스크 20 GiB를 잡으세요. 이는
측정된 최소 사양이나 성능 보장이 아닙니다. Docker에 모든 메모리를 할당하지 말고
host JVM 여유를 남기세요. `docker stats --no-stream`과 OS의 Java RSS/CPU를 확인해
조정합니다. 전체 Maven 통합 테스트와 BE cold start는 순차 실행하세요.

```sh
git clone https://github.com/Isaac-Lee/Mini-Sat-Controller.git
cd Mini-Sat-Controller
git switch main
# macOS에 등록된 JDK 21을 사용하는 경우:
# export JAVA_HOME=$(/usr/libexec/java_home -v 21)
# Linux에서는 설치한 JDK 21의 실제 경로를 지정:
# export JAVA_HOME=/path/to/jdk-21
"$JAVA_HOME/bin/java" -version
node --version
docker info >/dev/null
docker compose version
./scripts/dev-env.sh
```

`.local/msa.env`에는 이 컴퓨터 전용 비밀번호가 생성됩니다. Git에 추가하거나
다른 사람에게 보내지 마세요. 기존 파일은 보존되며, 기존 볼륨을 재사용할 때도
같은 파일을 유지해야 합니다.

## 2. 최초 빌드와 기반 서비스

```sh
# macOS에서 Testcontainers가 Docker 소켓을 못 찾을 때만 설정:
# export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"
./mvnw verify
npm --prefix msc-ops-console ci
npm --prefix msc-ops-console run build
npm --prefix msc-ops-console run lint
npm --prefix msc-ops-console run test
python3 scripts/fetch-orekit-reference.py
docker compose --env-file .local/msa.env -f deploy/local/compose.yaml up -d --wait
```

어느 명령이든 실패하면 다음 단계로 넘어가지 말고 해결하세요. Maven `verify`는
실제 Testcontainers 통합 테스트를 실행합니다. MinIO에는 Compose healthcheck가
없으므로 다음으로 별도 확인합니다.

```sh
curl --fail http://127.0.0.1:59000/minio/health/ready
```

## 3. 한 터미널에서 BE 전체와 FE 실행

저장소 루트에서 아래 블록을 **Bash**에 붙여 넣으세요. 포트가 비어 있는지 먼저
확인하고 BE를 하나씩 시작해 readiness를 기다린 다음 FE를 시작합니다.
`Ctrl+C`로 이 블록이 시작한 BE·FE를 모두 종료합니다. 기반 컨테이너는 5절에서
별도로 중지합니다. 로그는 `.local/quickstart-logs/`에 남습니다.

```bash
(
  set -eu
  : "${JAVA_HOME:?JDK 21 JAVA_HOME을 지정하세요}"
  export MSC_OREKIT_ARCHIVE="$PWD/.local/orekit/3e376b326373467647b1e246ebb083cd9e57cd68/time-frames.zip"
  export MSC_OREKIT_SHA256=ddfd02ae655ba0ac9d5430146a00a2941405983a081184e761d56e8a69973be1
  test -f "$MSC_OREKIT_ARCHIVE"
  python3 - <<'PY'
import socket
sockets = []
try:
    for port in [8101,8102,8103,8104,8105,8106,8107,8109,8110,8111,8112,8114,5173]:
        sock = socket.socket()
        sockets.append(sock)
        sock.bind(('127.0.0.1', port))
finally:
    for sock in sockets:
        sock.close()
PY
  mkdir -p .local/quickstart-logs
  pids=()
  cleanup() {
    trap - EXIT INT TERM
    for pid in "${pids[@]}"; do kill -TERM "$pid" 2>/dev/null || true; done
    for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
  }
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  for spec in mission-definition:8104 reference-data:8105 flight-dynamics:8103 tasking:8101 ground-operations:8106 simulator:8114 monitoring:8109 anomaly:8110 planning:8102 spacecraft-control:8107 acquisition:8111 product:8112; do
    service=${spec%:*}
    port=${spec#*:}
    ./scripts/run-local-service.sh "$service" "$port" >".local/quickstart-logs/$service.log" 2>&1 &
    pid=$!
    pids+=("$pid")
    ready=0
    for ((attempt=0; attempt<180; attempt++)); do
      kill -0 "$pid" 2>/dev/null || break
      if curl --fail --silent --max-time 2 "http://127.0.0.1:$port/actuator/health/readiness" >/dev/null; then
        ready=1
        break
      fi
      sleep 1
    done
    if [ "$ready" != 1 ]; then
      echo "$service 시작 실패: .local/quickstart-logs/$service.log 확인" >&2
      exit 1
    fi
    echo "$service ready ($port)"
  done
  (cd msc-ops-console && exec node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort) &
  pids+=("$!")
  wait "${pids[${#pids[@]}-1]}"
)
```

이 컴퓨터의 브라우저에서 <http://127.0.0.1:5173/>를 엽니다. Vite 개발 서버가
`.local/msa.env`를 읽어 API 인증을 대리합니다. `npm run preview`는 이 개발 프록시를
대신하지 않습니다. `--host 0.0.0.0` 또는 공개 터널로 노출하지 마세요. 사용자 인증이
없는 개발 프록시에 운영자·관리자 권한이 있기 때문입니다.

| 구성 | localhost 포트 |
|---|---|
| FE | 5173 |
| Tasking / Planning / Flight Dynamics | 8101 / 8102 / 8103 |
| Mission Definition / Reference Data / Ground Operations | 8104 / 8105 / 8106 |
| Spacecraft Control / Monitoring / Anomaly | 8107 / 8109 / 8110 |
| Acquisition / Product / Simulator | 8111 / 8112 / 8114 |
| PostgreSQL / RabbitMQ / RabbitMQ 관리 | 55432 / 55672 / 55673 |
| MinIO API / 관리 | 59000 / 59001 |

8108과 8113은 이 실행 묶음에 포함된 실행 서비스가 아닙니다. 모든 서비스 역할 카드가
보인다고 BE가 정상이라는 뜻은 아니며, 실제 API 오류는 화면에서 별도로 표시됩니다.

## 4. 새 컴퓨터에서 실험 데이터 만들기

처음에는 DB가 비어 있어 완료된 예시가 없습니다. 위 실행 터미널을 유지하고,
다른 터미널에서 저장소 루트로 이동해 다음을 실행하면 별도 합성 위성·요청·계획·
명령·다운링크·제품을 만들고 요청 결과까지 검증합니다. 원본 위성 자료 복사는 필요 없습니다.

```sh
python3 scripts/verify-planning-search.py \
  --with-runs --with-camera --with-simulation-commit \
  --with-simulation-dispatch --with-v1-downlink --with-v1-result \
  --timeout-seconds 300
```

성공하면 `.local/v1-functional-verification.json`이 생성됩니다. FE에서 요청 목록을
새로고침하고 **완료된 예시 열기 → 임무 흐름 → 제품** 순서로 확인하세요. 매번 새
합성 데이터가 생성되고 성공 결과는 DB와 MinIO에 보존됩니다. 실패를 완료로
간주하지 말고 실행 로그와 해당 서비스 로그를 확인하세요.

SPACEEYE-T1 공개 궤도는 별도입니다. Mission Map에서 공개 궤도 수집/가져오기를
진행하며 외부 공급자 접근·쿨다운·epoch 유효성에 따라 실패할 수 있습니다. 공개 GP는
실제 텔레메트리가 아니고, 위 합성 시나리오의 위성 사양을 나타내지도 않습니다.

`python3 scripts/verify-v1.py`는 보존된 공개 GP와 K8s 복제 상태까지 확인하므로
이 host JVM quickstart에서 그대로 실행하는 명령이 아닙니다. K8s 환경을 구성한
경우에만 [V1 검증 안내](v1-review.md)를 따르세요.

## 5. 종료와 재시작

실행 터미널에서 `Ctrl+C` 후, 저장소 루트에서:

```sh
docker compose --env-file .local/msa.env -f deploy/local/compose.yaml stop
```

`stop`은 프로젝트 컨테이너만 중지하고 데이터 볼륨을 보존합니다. `down -v`, 볼륨
삭제, 인증 파일 재생성은 필요 없습니다. 재시작은 2절의 Compose `up -d --wait`와
MinIO 확인, 3절 실행 블록만 반복하면 됩니다. 소스를 변경했다면 먼저 해당 빌드·검사를
다시 실행하세요. Docker Desktop 자동 시작 후에는 컨테이너 상태를 재확인하세요.

K8s 방식에서 종료할 때는 자신이 실행한 포워딩 터미널을 먼저 종료하고, 전용
kubeconfig로 `msc` namespace의 Deployment를 0 replica로 줄인 뒤 기반 Compose를
중지하세요. 다른 cluster/context는 조작하지 마세요. 복원 replica 수는 중지 전에
기록해야 하며 host JVM 방식과 동시에 복원하지 않습니다.

## 검증 범위

이 안내의 FE 빌드·lint·39개 단위 테스트와 로컬 정책 검사를 확인했습니다.
다른 컴퓨터의 fresh clone에서 전체 cold start, 위 합성 시나리오, 자원 사용량은
이번 문서 작성 시 실측하지 않았습니다. 기존 실행·검증 스크립트에 맞춘 절차이며,
원격 배포나 모든 FE 조작의 E2E 성공을 보장하는 기록은 아닙니다.
