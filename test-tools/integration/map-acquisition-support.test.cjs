const { test } = require('node:test');
const assert = require('node:assert/strict');
const { approvalRequired, classify, transferBudget } = require('./map-acquisition-support.cjs');
test('CI ceiling never authorizes the observed large regional requests', () => {
  for (const n of [50306338, 227876423])
    assert.throws(() => approvalRequired({transfer:{approved_bytes:5000000,approval_required_bytes:n}},30000000), new RegExp(`${n} bytes; configured budget 30000000`));
  assert.equal(approvalRequired({transfer:{approved_bytes:5000000,approval_required_bytes:18131689}},30000000),18131689);
  for (const n of [0, 4000000, NaN, Infinity, 5000000.1])
    assert.throws(() => approvalRequired({transfer:{approved_bytes:5000000,approval_required_bytes:n}},30000000));
});
test('failure classification preserves providers, timeouts and approval ceiling', () => {
 assert.equal(classify('Overpass HTTP 502'),'provider unavailable/HTTP 502');
 assert.equal(classify('Client.Timeout exceeded while awaiting headers'),'request timeout');
 assert.equal(classify('CI transfer ceiling exceeded: 228000000'),'CI transfer ceiling exceeded');
});
test('missing CI configuration retains default 5 MB protection', () => {
 const original=process.env.GNSS_CI_TRANSFER_BUDGET_BYTES;
 try {
  delete process.env.GNSS_CI_TRANSFER_BUDGET_BYTES;assert.equal(transferBudget(),5000000);
  for(const value of ['0','arbitrary','1000000001','Infinity','5000000.1']) {
   process.env.GNSS_CI_TRANSFER_BUDGET_BYTES=value;assert.throws(transferBudget);
  }
 } finally { if(original===undefined)delete process.env.GNSS_CI_TRANSFER_BUDGET_BYTES;else process.env.GNSS_CI_TRANSFER_BUDGET_BYTES=original; }
});
