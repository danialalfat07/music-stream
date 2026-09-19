// Vercel serverless entry - wraps the Express app (handles /api/* and static via Express)
const app = require('../server.js');
module.exports = (req, res) => app(req, res);
