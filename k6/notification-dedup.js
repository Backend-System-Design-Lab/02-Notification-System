import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 30,
    duration: '30s',
};

const BASE_URL = 'http://localhost:8080';

export default function () {

    // 모든 요청이 동일한 eventId
    const payload = JSON.stringify({
        eventId: 'dedup-performance-test',
        userId: 1,
        channels: ['SMS'],
    });

    const res = http.post(
        `${BASE_URL}/api/v1/notifications`,
        payload,
        {
            headers: {
                'Content-Type': 'application/json',
            },
        }
    );

    check(res, {
        'status is 202': (r) => r.status === 202,
    });
}