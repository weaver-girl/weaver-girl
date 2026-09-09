const express = require('express');
const router = express.Router();

const configExample = `# weaver-girl.yaml
plugins:
  - servlet
  - spring
  - jdbc
  - redis

sampling:
  rate: 1.0
  maxRate: 1.0
  minRate: 0.1

export:
  otlpEndpoint: http://localhost:4317
  spanExportTimeoutMillis: 5000

graceful:
  emergencyDisable: false
  maxConsecutiveFailures: 200`;

router.get('/', (req, res) => {
  res.json({
    title: 'Configuration Reference',
    example: configExample
  });
});

module.exports = router;
