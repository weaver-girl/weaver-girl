const express = require('express');
const router = express.Router();

router.get('/', (req, res) => {
  res.json({
    name: 'Weaver-Girl',
    description: 'Lightweight Java bytecode instrumentation framework inspired by SkyWalking and OpenTelemetry Java Agent.',
    version: '1.0.0',
    repository: 'https://github.com/weaver-girl/weaver-girl',
    license: 'MIT'
  });
});

module.exports = router;
