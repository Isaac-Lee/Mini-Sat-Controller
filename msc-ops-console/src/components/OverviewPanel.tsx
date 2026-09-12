export type ConsoleTab = 'overview' | 'map' | 'request' | 'workflow' | 'timeline' | 'monitor' | 'authority' | 'product'
const SERVICES: Array<{ name: string; role: string; input: string; output: string; tab: ConsoleTab }> = [
  { name: 'Reference Data', role: '공개 궤도·지명·기준 자료 보존', input: '외부 GP/TLE와 승인된 지명', output: '출처·epoch·해시가 붙은 자료', tab: 'map' },
  { name: 'Flight Dynamics', role: '언제 어디에 있는지 계산', input: '궤도·시각·목표·카메라 모델', output: '궤적·접근 기회·샘플 촬영 영역', tab: 'map' },
  { name: 'Tasking', role: '촬영 요청과 결과 소유권 관리', input: '목표·우선순위·촬영 조건', output: '요청 상태·리비전·완료 결과', tab: 'request' },
  { name: 'Mission Definition', role: '활동과 시뮬레이션 모델 정의', input: '버전이 있는 카탈로그·카메라·자원 모델', output: '계획과 명령이 참조하는 활동 정의', tab: 'workflow' },
  { name: 'Planning', role: '촬영 후보 평가와 일정 확정', input: '요청·궤도·상태·안전·임무 모델', output: '후보·카메라/자원 평가·버전별 일정', tab: 'workflow' },
  { name: 'Spacecraft Control', role: '일정을 승인된 명령으로 연결', input: '확정 일정·권한·안전 검사', output: '명령 묶음·승인·전달·실행 확인', tab: 'workflow' },
  { name: 'Ground Operations', role: '지상국 접촉과 예약 관리', input: '지상국 가용성·접촉 구간', output: '예약과 다운링크 실행 근거', tab: 'workflow' },
  { name: 'Simulator', role: '위성·지상국 동작을 합성 환경에서 실행', input: '시나리오·명령·가상 시각', output: '텔레메트리·명령 원장·합성 수신 바이트', tab: 'workflow' },
  { name: 'Monitoring', role: '관측 근거로 위성 상태 추정', input: '시뮬레이션 텔레메트리·바인딩', output: '신뢰도·운용 모드·배터리/저장소/추진제', tab: 'monitor' },
  { name: 'Anomaly', role: '위험 시 동결하고 복구 승인 기록', input: '관측 상태·안전 정책', output: '동결 이유·사고·운영자 복구 승인', tab: 'authority' },
  { name: 'Acquisition', role: '수신 원본과 수신 목록 보존', input: '다운링크로 도착한 합성 바이트', output: '수신 출처·크기·해시·완전성 근거', tab: 'workflow' },
  { name: 'Product', role: '다운로드 가능한 합성 산출물 구성', input: 'Acquisition 원본과 출처', output: '합성 원본 패키지·진단 PNG', tab: 'product' },
]
export function OverviewPanel({ onNavigate }: { onNavigate: (tab: ConsoleTab) => void }) {
  return <div className="overview-panel">
    <p className="eyebrow">MINI SAT CONTROLLER · 기능 둘러보기</p>
    <h1>촬영 요청이 결과가 되기까지</h1>
    <p className="overview-lead">요청을 접수하고, 촬영 기회를 계획하고, 위성·지상국 시뮬레이터를 거쳐 합성 결과를 받습니다. 아래 순서로 화면을 살펴보세요.</p>
    <ol className="journey">
      {([['map', '궤도 확인', '공개 궤도와 예측 위치'], ['request', '요청 선택', '목표와 현재 상태'], ['workflow', '실행 흐름', '후보·일정·명령·수신 근거'], ['product', '결과 확인', '합성 원본과 PNG']] as const).map(([tab, title, note], index) => <li key={tab}>
        <button onClick={() => onNavigate(tab)}><span>{index + 1}</span><strong>{title}</strong><small>{note}</small></button>
      </li>)}
    </ol>
    <aside className="scope-note"><strong>지금 확인하는 범위</strong><p>실제 API와 저장된 데이터를 사용하는 로컬 기능 확인판입니다. 위성·지상국 실행과 제품은 합성이며, 공개 SPACEEYE-T1 궤도가 실제 카메라·배터리·통신 상태를 알려주지는 않습니다.</p><p>처음에는 요청 탭의 <b>완료된 예시 열기</b>로 전체 흐름을 확인하세요. 각 조회의 성공·누락은 해당 화면에 표시되며, 이 서비스 안내는 서버 가동 상태를 뜻하지 않습니다.</p></aside>
    <h2>12개 서비스의 역할</h2>
    <div className="service-grid">{SERVICES.map((service) => <article className="service-card" key={service.name}>
      <h3>{service.name}</h3><p>{service.role}</p><dl><dt>받는 것</dt><dd>{service.input}</dd><dt>만드는 것</dt><dd>{service.output}</dd></dl><button onClick={() => onNavigate(service.tab)}>관련 화면 열기</button>
    </article>)}</div>
    <aside className="scope-note"><h2>현재 UI와 후속 범위</h2><p>궤도 조회·수집·예측, 요청 제출·취소, 관측·안전 평가와 복구 승인, 결과 다운로드를 조작할 수 있습니다. 임무 흐름 화면은 저장된 후보·일정·명령·수신 결과를 읽어 설명합니다.</p><p>새 시나리오 생성부터 일정 확정·명령 방출·완료 등록까지의 전체 실행은 기존 BE 안내 스크립트로 제공합니다. 운영용 로그인, 실제 위성 교신, 정밀 비행역학은 이 기능 확인판의 범위 밖입니다.</p></aside>
  </div>
}
