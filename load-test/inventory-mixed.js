// 상품 하나에 입고, 출고를 섞어 보내고, 일부는 같은 멱등성 키로 동시에 두 번 보낸다.
// 실행: ./load-test/run.sh [load|stress|spike] (compose 네트워크 안에서 nginx로 보낸다)
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://nginx/api/v1/inventory';
const SCENARIO = __ENV.SCENARIO || 'load';
const PRODUCT = 'LOAD-TEST-01';
const INITIAL_STOCK = 50;
const DUPLICATE_RATE = 0.2;
const STATUSES = [200, 409, 500, 502, 503, 504];

// 도착률 기반 단계. label이 있는 단계만 따로 집계하고, 올리는 구간(label 없음)은 합계에만 들어간다
function hold(rate, duration, label) {
    return { target: rate, duration, label };
}
const STAGES = {
    stress: [200, 400, 600, 800, 1000, 1200].flatMap((rate) => [
        hold(rate, '5s'),
        hold(rate, '20s', `${rate}-per-s`),
    ]),
    spike: [
        hold(200, '30s', 'before'),
        hold(1500, '5s'),
        hold(1500, '20s', 'peak'),
        hold(200, '5s'),
        hold(200, '10s', 'after-10s'),   // 몰림이 끝난 직후, 밀린 요청이 빠지는 구간
        hold(200, '30s', 'after'),
    ],
};

function scenarioOptions() {
    if (SCENARIO === 'load') {
        return { executor: 'constant-vus', vus: 50, duration: '60s' };
    }
    return {
        executor: 'ramping-arrival-rate',
        startRate: STAGES[SCENARIO][0].target,
        timeUnit: '1s',
        preAllocatedVUs: 200,
        maxVUs: 2000,
        stages: STAGES[SCENARIO].map(({ target, duration }) => ({ target, duration })),
    };
}

const labels = (STAGES[SCENARIO] || []).map((s) => s.label).filter(Boolean);
const seconds = (duration) => Number(duration.replace('s', ''));

// 지금 몇 번째 단계인지 (시나리오 시작부터 지난 시간으로)
function currentLabel() {
    let elapsed = (Date.now() - exec.scenario.startTime) / 1000;
    for (const stage of STAGES[SCENARIO]) {
        elapsed -= seconds(stage.duration);
        if (elapsed < 0) {
            return stage.label || 'ramp';
        }
    }
    return 'ramp';
}

// 키마다 200을 한 번이라도 받았으면 1. 같은 키 두 요청이 둘 다 200이어도 1이다
const appliedKeys = new Counter('applied_keys');
const statusCounters = Object.fromEntries(STATUSES.map((s) => [s, new Counter(`status_${s}`)]));
const statusOther = new Counter('status_other');

// 단계별 값이 요약(data.metrics)에 나오게 하려면 태그를 붙인 하위 지표를 thresholds에 적어야 한다 (기준값은 두지 않는다)
const perStage = ['http_reqs', 'http_req_duration', ...STATUSES.map((s) => `status_${s}`), 'status_other'];
const thresholds = Object.fromEntries(labels.flatMap((l) => perStage.map((m) => [`${m}{stage:${l}}`, []])));

export const options = {
    scenarios: { [SCENARIO]: scenarioOptions() },
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
    thresholds,
};

// 409(재고 부족)는 정상 응답이다
http.setResponseCallback(http.expectedStatuses(200, 409));

function request(type, quantity, key) {
    return {
        method: 'POST',
        url: `${BASE}/${PRODUCT}/${type}`,
        body: JSON.stringify({ quantity }),
        params: {
            headers: {
                'Content-Type': 'application/json',
                'X-User-Id': `load-vu-${__VU}`,
                'Idempotency-Key': key,
            },
        },
    };
}

function count(responses) {
    responses.forEach((r) => (statusCounters[r.status] || statusOther).add(1));
    if (responses.some((r) => r.status === 200)) {
        appliedKeys.add(1);
    }
}

export function setup() {
    const res = http.batch([request('inbound', INITIAL_STOCK, crypto.randomUUID())]);
    if (res[0].status !== 200) {
        throw new Error(`시작 재고 입고 실패: ${res[0].status} ${res[0].body}`);
    }
    count(res);
}

export default function () {
    if (SCENARIO !== 'load') {
        exec.vu.metrics.tags.stage = currentLabel();
    }
    const type = Math.random() < 0.5 ? 'inbound' : 'outbound';
    const quantity = 1 + Math.floor(Math.random() * 5);
    const req = request(type, quantity, crypto.randomUUID());
    // 같은 키를 동시에 두 번: nginx가 번갈아 보내 대개 서로 다른 앱에 들어간다
    count(http.batch(Math.random() < DUPLICATE_RATE ? [req, req] : [req]));
}

function value(data, name, stat) {
    const m = data.metrics[name];
    return m ? m.values[stat] : 0;
}

function statusLine(data, suffix = '') {
    return [...STATUSES.map((s) => `${s}: ${value(data, `status_${s}${suffix}`, 'count')}`),
        `그 밖: ${value(data, `status_other${suffix}`, 'count')}`].join(', ');
}

function stageLine(data, label) {
    const tag = `{stage:${label}}`;
    const stage = STAGES[SCENARIO].find((s) => s.label === label);
    const rate = value(data, `http_reqs${tag}`, 'count') / seconds(stage.duration);
    const p95 = value(data, `http_req_duration${tag}`, 'p(95)');
    return `  ${label.padEnd(12)} ${rate.toFixed(0).padStart(5)} req/s, p95 ${p95.toFixed(0).padStart(5)}ms, ${statusLine(data, tag)}`;
}

export function handleSummary(data) {
    const ms = (stat) => value(data, 'http_req_duration', stat).toFixed(1);
    const lines = [
        `시나리오       ${SCENARIO}`,
        `요청 수        ${value(data, 'http_reqs', 'count')}`,
        `처리량         ${value(data, 'http_reqs', 'rate').toFixed(1)} req/s`,
        `응답 시간(ms)  avg ${ms('avg')}, p50 ${ms('p(50)')}, p95 ${ms('p(95)')}, p99 ${ms('p(99)')}, max ${ms('max')}`,
        `상태 코드      ${statusLine(data)}`,
        `건너뛴 반복    ${value(data, 'dropped_iterations', 'count')}`,
        `반영된 키      ${value(data, 'applied_keys', 'count')}`,
    ];
    if (labels.length) {
        lines.push('단계별', ...labels.map((l) => stageLine(data, l)));
    }
    return {
        stdout: `\n${lines.join('\n')}\n`,
        '/results/applied_keys.txt': `${value(data, 'applied_keys', 'count')}\n`,
        [`/results/summary-${SCENARIO}.json`]: JSON.stringify(data, null, 2),
    };
}
