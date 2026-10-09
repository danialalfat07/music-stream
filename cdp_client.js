const fs = require('fs');
const WebSocket = require('ws');
const url = process.argv[2];
let expression = process.argv[3];
if (expression && expression.startsWith('@')) {
  expression = fs.readFileSync(expression.slice(1), 'utf8');
}
const ws = new WebSocket(url);
const timer = setTimeout(() => {
  console.error('TIMEOUT waiting for CDP response');
  process.exit(2);
}, 25000);
ws.on('open', () => {
  ws.send(JSON.stringify({
    id: 1,
    method: 'Runtime.evaluate',
    params: { expression, returnByValue: true }
  }));
});
ws.on('message', (data) => {
  const msg = JSON.parse(data);
  if (msg.id === 1) {
    clearTimeout(timer);
    console.log(JSON.stringify(msg.result, null, 2));
    ws.close();
    process.exit(0);
  }
});
ws.on('error', (e) => { console.error('WS error:', e.message); process.exit(1); });
