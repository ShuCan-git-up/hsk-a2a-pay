const $ = (s) => document.querySelector(s);
let status = null;
const short = (a) => a && a.length > 14 ? a.slice(0, 8) + '…' + a.slice(-6) : (a || '—');
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const explorer = () => status?.chain?.contractUrl ? status.chain.contractUrl.split('/address/')[0] : 'https://testnet-explorer.hsk.xyz';
const txLink = (h) => h && /^0x[0-9a-fA-F]{64}$/.test(h) && !/^0x0+$/.test(h) ? `<a class="mono" target="_blank" href="${explorer()}/tx/${h}">${short(h)}</a>` : '<span class="muted">—</span>';
const addrLink = (a) => a ? `<a class="mono" target="_blank" href="${explorer()}/address/${a}">${short(a)}</a>` : '—';

async function api(path, opts = {}) {
  const r = await fetch(path, {headers: {'Content-Type': 'application/json'}, ...opts});
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || r.status);
  return j;
}

async function loadStatus() {
  status = await api('/api/status');
  const s = status, live = s.hsp.mode === 'mcp';
  const bal = s.chain.operatorBalanceWei && /^\d+$/.test(s.chain.operatorBalanceWei) ? (Number(BigInt(s.chain.operatorBalanceWei) / 10n ** 12n) / 1e6).toFixed(4) + ' HSK' : (s.chain.operatorBalanceWei || '—');
  $('#statusBody').innerHTML = `
    <div class="kv"><b>HSP rail</b><span class="pill ${live ? 'ok' : 'warn'}">${live ? 'LIVE · HSP Agentic Payment (MCP)' : 'SIMULATED · local HSP mock'}</span>
      <div class="muted mono">${esc(s.hsp.endpoint)}</div><div class="muted">${esc(s.hsp.health)}</div><div>Team: <b style="display:inline">${esc(s.hsp.merchantName)}</b></div></div>
    <div class="kv"><b>HashKey Chain</b>chainId ${s.chain.chainId} · ${s.chain.enabled ? '<span class="pill ok">anchoring on</span>' : '<span class="pill warn">anchoring off</span>'}
      <div>Contract ${s.chain.contract ? addrLink(s.chain.contract) : '<span class="muted">not deployed</span>'}</div>
      <div>Operator ${addrLink(s.chain.operator)} · ${esc(bal)}</div>
      ${s.chain.termsCount !== undefined ? `<div>Terms ${s.chain.termsCount} · Settled ${s.chain.settledCount}</div>` : ''}</div>
    <div class="kv"><b>Receiving agent</b>${esc(s.receiver.agent)}<div>payTo ${addrLink(s.receiver.payTo)} · ${esc(s.receiver.priceUsd)} USD/call</div>
      <div>Token ${esc(s.token.symbol)} ${addrLink(s.token.address)} (${esc(s.token.protocol)})</div></div>
    <div class="kv"><b>Payment agent</b>payer ${addrLink(s.payer.address)}<div>Cap ${esc(s.payer.maxPerPaymentUsd)} USD/payment · ${esc(s.payer.dailyCapUsd)} USD/day</div>
      <div>Approvals: ${s.payer.autoApprove ? 'automatic inside policy' : 'human approves sign + submit'}</div></div>`;
  $('#footContract').innerHTML = s.chain.contract ? addrLink(s.chain.contract) : 'not deployed yet';
  if (!live) $('#statusBody').insertAdjacentHTML('beforeend', `<div class="banner" style="grid-column:1/-1">Simulated HSP: signatures are real and verified, but no tokens move and no HSP transaction hash exists. Switch <code>HSP_MODE=mcp</code> once HSP has allow-listed this server's IP.</div>`);
}

const stateClass = (s) => ['DELIVERED', 'SETTLED'].includes(s) ? 'ok' : ['FAILED', 'REJECTED'].includes(s) ? 'bad' : s.startsWith('AWAITING') ? 'warn' : '';

function renderRun(r) {
  const s = r.summary || {};
  let approval = '';
  if (r.state === 'AWAITING_SIGN_APPROVAL' || r.state === 'AWAITING_SUBMIT_APPROVAL') {
    const stage = r.state === 'AWAITING_SIGN_APPROVAL' ? 'sign' : 'submit';
    approval = `<div class="approve"><b>${stage === 'sign' ? 'Approval #1 — sign the EIP-712 payment authorization?' : 'Approval #2 — submit the Payment Mandate to the HSP Gateway?'}</b>
      <div class="mono">Recipient (pay_to): ${esc(s.payTo)}<br>Payer: ${esc(s.payer)}<br>token_total_raw: ${esc(s.tokenTotalRaw)} raw units (${(Number(s.tokenTotalRaw || 0) / 1e6).toFixed(6)} USDC)</div>
      <div class="actions"><button onclick="act('${r.id}','approve?stage=${stage}')">Approve ${stage}</button><button class="danger" onclick="act('${r.id}','reject')">Reject</button></div></div>`;
  }
  const steps = r.timeline.map((t) => `<li class="${t.name === 'error' ? 'error' : t.name.startsWith('approv') || t.name === 'awaiting_approval' ? 'approval' : ''}">
      <div><code>${esc(t.name)}</code> ${esc(t.detail)}</div>
      ${Object.keys(t.data || {}).length ? `<div class="d mono">${Object.entries(t.data).map(([k, v]) => `${esc(k)}=${esc(typeof v === 'object' ? JSON.stringify(v) : v)}`).join(' · ')}</div>` : ''}
      <div class="t">${esc(t.at)}</div></li>`).join('');
  const links = [
    s.publishTermsTx ? `Terms on-chain: <a target="_blank" href="${esc(s.publishTermsTx)}">publishTerms tx</a>` : '',
    s.hspTx ? `HSP transfer: ${txLink(s.hspTx)}` : '',
    s.recordSettlementTx ? `Receipt on-chain: <a target="_blank" href="${esc(s.recordSettlementTx)}">recordHspSettlement tx</a>` : '',
  ].filter(Boolean).join(' · ');
  return `<section class="card"><div class="run-head"><h3>Run ${esc(r.id)} · target ${addrLink(r.target)}</h3>
    <span class="pill ${stateClass(r.state)}">${esc(r.state)}${s.simulated ? ' · simulated HSP' : ''}</span></div>
    <div class="muted">${s.flowId ? `flow_id <span class="mono">${esc(s.flowId)}</span> · ` : ''}${s.priceUsd ? esc(s.priceUsd) + ' USD' : ''}</div>
    ${links ? `<div>${links}</div>` : ''}${approval}<ul class="timeline">${steps}</ul>
    ${s.resource ? `<h3 style="margin-top:10px">Delivered resource</h3><pre>${esc(JSON.stringify(s.resource, null, 2))}</pre>` : ''}</section>`;
}

async function loadRuns() {
  const runs = await api('/api/runs');
  $('#runs').innerHTML = runs.map(renderRun).join('');
}

async function act(id, what) {
  try { await api(`/api/runs/${id}/${what}`, {method: 'POST'}); } catch (e) { alert(e.message); }
  loadRuns();
}
window.act = act;

$('#startForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  try { await api('/api/runs', {method: 'POST', body: JSON.stringify({target: $('#target').value.trim()})}); } catch (err) { alert(err.message); }
  loadRuns();
});

loadStatus().catch((e) => $('#statusBody').textContent = 'status error: ' + e.message);
loadRuns();
setInterval(loadRuns, 2000);
setInterval(() => loadStatus().catch(() => {}), 20000);
