const express = require('express');
const router = express.Router();

const docs = [
  {
    id: 'quickstart',
    title: 'Quick Start',
    content: 'Attach the agent with `java -javaagent:weaver-girl-agent.jar -jar your-app.jar`.'
  },
  {
    id: 'config',
    title: 'Configuration',
    content: 'Use YAML config to customize plugins, sampling, and export settings.'
  },
  {
    id: 'plugins',
    title: 'Plugins',
    content: '16 built-in plugins cover Servlet, Spring, JDBC, Redis, Kafka, gRPC, MongoDB, HttpClient, and more.'
  }
];

router.get('/', (req, res) => {
  res.json({ docs });
});

router.get('/:id', (req, res) => {
  const item = docs.find(d => d.id === req.params.id);
  if (!item) {
    return res.status(404).json({ error: 'Not found' });
  }
  res.json(item);
});

module.exports = router;
