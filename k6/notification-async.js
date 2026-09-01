import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 50,
    duration: '30s',

    summaryTrendStats: [
        'avg',
        'min',
        'med',
        'max',
        'p(90)',
        'p(95)',
        'p(99)',
    ],

    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
    const eventId = `baseline-${__VU}-${__ITER}-${Date.now()}`;

    const payload = JSON.stringify({
        eventId: eventId,
        userId: 1,
        channels: ['PUSH', 'SMS', 'EMAIL'],
    });

    const response = http.post(
        `${BASE_URL}/api/v1/notifications`,
        payload,
        {
            headers: {
                'Content-Type': 'application/json',
            },
        }
    );

    check(response, {
        'status is 202': (r) => r.status === 202,
    });
}