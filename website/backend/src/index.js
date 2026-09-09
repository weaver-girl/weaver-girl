const express = require('express');
const docsRoutes = require('./routes/docs');
const pluginsRoutes = require('./routes/plugins');
const statusRoutes = require('./routes/status');
const aboutRoutes = require('./routes/about');
const configRoutes = require('./routes/config');
const examplesRoutes = require('./routes/examples');

const app = express();
app.use(express.json());

app.use('/docs', docsRoutes);
app.use('/plugins', pluginsRoutes);
app.use('/api/status', statusRoutes);
app.use('/about', aboutRoutes);
app.use('/config', configRoutes);
app.use('/examples', examplesRoutes);

app.get('/', (req, res) => {
  res.json({
    name: 'Weaver-Girl Website API',
    version: '1.0.0',
    endpoints: [
      '/docs',
      '/plugins',
      '/api/status',
      '/about',
      '/config'
    ]
  });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`Weaver-Girl website API listening on http://localhost:${PORT}`);
});

module.exports = app;
