import sendNotification from './notification-async.js';

export const options = {
  scenarios: {
    fixed_rate: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 200),
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: [
    'avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'
  ],
};

export default sendNotification;
