const express = require('express');
const router = express.Router();

const plugins = [
  { name: 'servlet', description: 'HttpServlet and Filter interception' },
  { name: 'spring', description: 'Spring MVC controller interception' },
  { name: 'jdbc', description: 'JDBC statement and connection interception' },
  { name: 'redis', description: 'Redis command interception' },
  { name: 'kafka', description: 'Kafka producer/consumer interception' },
  { name: 'grpc', description: 'gRPC client/server interception' },
  { name: 'mongodb', description: 'MongoDB client interception' },
  { name: 'httpclient', description: 'Apache HttpClient interception' }
];

router.get('/', (req, res) => {
  res.json({ plugins });
});

router.get('/:name', (req, res) => {
  const plugin = plugins.find(p => p.name === req.params.name);
  if (!plugin) {
    return res.status(404).json({ error: 'Plugin not found' });
  }
  res.json(plugin);
});

module.exports = router;
