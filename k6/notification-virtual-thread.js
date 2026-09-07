import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 200,
    duration: '30s',

    summaryTrendStats: [
        'avg',
        'med',
        'p(90)',
        'p(95)',
        'p(99)',
        'max',
    ],
};

export default function () {
    const eventId =
        `virtual-thread-${__VU}-${__ITER}-${Date.now()}`;

    const payload = JSON.stringify({
        eventId: eventId,
        userId: 1,
        channels: ['EMAIL'],
    });

    const response = http.post(
        'http://localhost:8080/api/v1/notifications',
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