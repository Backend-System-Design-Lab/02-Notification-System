import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 30,
    duration: '30s',
};

export default function () {

    // 모든 요청마다 새로운 eventId
    const eventId =
        `preference-perf-${__VU}-${__ITER}-${Date.now()}`;

    const payload = JSON.stringify({
        eventId: eventId,
        userId: 1,
        channels: ['EMAIL'],
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
    };

    const response = http.post(
        'http://localhost:8080/api/v1/notifications',
        payload,
        params
    );

    check(response, {
        'status is 202': (r) => r.status === 202,
    });
}