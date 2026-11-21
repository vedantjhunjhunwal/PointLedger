import { useCallback, useEffect, useState } from 'react';
import { api, Audit, CampaignView, CustomerView, Entry, OrderView } from './api';

const yen = (n: number) => `¥${n.toLocaleString('en-US')}`;
const pts = (n: number) => `${n.toLocaleString('en-US')} pts`;
const short = (h: string) => `${h.slice(0, 10)}…`;

export default function App() {
  const [campaigns, setCampaigns] = useState<CampaignView[]>([]);
  const [audit, setAudit] = useState<Audit | null>(null);
  const [entries, setEntries] = useState<Entry[]>([]);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [c, a, l] = await Promise.all([api.campaigns(), api.audit(), api.ledger(20)]);
      setCampaigns(c); setAudit(a); setEntries(l.entries); setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 2000);
    return () => clearInterval(t);
  }, [refresh]);

  return (
    <div className="page">
      <header>
        <h1>PointLedger</h1>
        <p>Seller-funded loyalty points on a hash-chained, double-entry ledger</p>
      </header>
      {error && <div className="banner bad">API unreachable: {error}</div>}
      <AuditPanel audit={audit} />
      <div className="grid">
        <CampaignPanel campaigns={campaigns} onChange={refresh} />
        <OrderPanel onChange={refresh} />
      </div>
      <div className="grid">
        <CustomerPanel onChange={refresh} />
        <LedgerPanel entries={entries} />
      </div>
    </div>
  );
}

function AuditPanel({ audit }: { audit: Audit | null }) {
  if (!audit) return <section className="card">Loading audit…</section>;
  const checks: [string, boolean][] = [
    ['Hash chain intact', !audit.ledger.message.includes('hash chain')],
    ['Points conserved (Σ balances = 0)', audit.ledger.sumOfBalances === 0],
    ['Budget = available + reserved + awarded + refunded', audit.campaignsBalanced],
    ['Customer balances = sum of their lots', audit.lotsMatchBalances],
    ['No campaign oversold', audit.overspentCampaigns === 0],
  ];
  return (
    <section className={`card audit ${audit.ok ? 'good' : 'bad'}`}>
      <div className="audit-head">
        <strong>{audit.ok ? 'All invariants hold' : 'Invariant violated'}</strong>
        <span>{audit.ledger.entries.toLocaleString('en-US')} entries · head {short(audit.ledger.headHash)}</span>
      </div>
      <ul className="checks">
        {checks.map(([label, ok]) => <li key={label} className={ok ? 'ok' : 'fail'}>{ok ? '✓' : '✗'} {label}</li>)}
      </ul>
    </section>
  );
}

function CampaignPanel({ campaigns, onChange }: { campaigns: CampaignView[]; onChange: () => void }) {
  const [form, setForm] = useState({ id: 'spring-5', sellerId: 'seller-a', rateBp: 500, group: '', perOrderCap: 1000, budget: 50000 });
  const [msg, setMsg] = useState('');
  const create = async () => {
    try { await api.createCampaign(form); setMsg(`Funded ${form.id}`); onChange(); }
    catch (e) { setMsg((e as Error).message); }
  };
  return (
    <section className="card">
      <h2>Campaigns</h2>
      {campaigns.length === 0 && <p className="muted">No campaigns yet: fund one below.</p>}
      {campaigns.map(c => {
        const b = c.campaign.budget;
        const w = (n: number) => `${(100 * n) / b}%`;
        return (
          <div key={c.campaign.id} className="campaign">
            <div className="row">
              <strong>{c.campaign.id}</strong>
              <span className="muted">{c.campaign.sellerId} · {c.campaign.rateBp / 100}% · cap {pts(c.campaign.perOrderCap)}
                {c.campaign.group && ` · group ${c.campaign.group}`}{c.closed && ' · closed'}</span>
            </div>
            <div className="bar" title="awarded | reserved | available | refunded">
              <span className="awarded" style={{ width: w(c.awarded) }} />
              <span className="reserved" style={{ width: w(c.reserved) }} />
              <span className="available" style={{ width: w(c.available) }} />
              <span className="refunded" style={{ width: w(c.refunded) }} />
            </div>
            <div className="row small muted">
              <span>awarded {pts(c.awarded)} · reserved {pts(c.reserved)} · available {pts(c.available)}</span>
              {!c.closed && <button className="link" onClick={async () => { await api.closeCampaign(c.campaign.id); onChange(); }}>close &amp; refund</button>}
            </div>
          </div>
        );
      })}
      <div className="form">
        <input value={form.id} onChange={e => setForm({ ...form, id: e.target.value })} placeholder="campaign id" />
        <input value={form.sellerId} onChange={e => setForm({ ...form, sellerId: e.target.value })} placeholder="seller" />
        <input type="number" value={form.rateBp} onChange={e => setForm({ ...form, rateBp: +e.target.value })} title="rate in basis points (500 = 5%)" />
        <input value={form.group} onChange={e => setForm({ ...form, group: e.target.value })} placeholder="exclusive group" />
        <input type="number" value={form.perOrderCap} onChange={e => setForm({ ...form, perOrderCap: +e.target.value })} title="max points per order" />
        <input type="number" value={form.budget} onChange={e => setForm({ ...form, budget: +e.target.value })} title="budget (points)" />
        <button onClick={create}>Fund campaign</button>
      </div>
      {msg && <p className="small muted">{msg}</p>}
    </section>
  );
}

function OrderPanel({ onChange }: { onChange: () => void }) {
  const [seller, setSeller] = useState('seller-a');
  const [price, setPrice] = useState(11000);
  const [tax, setTax] = useState(10);
  const [customer, setCustomer] = useState('user-1');
  const [order, setOrder] = useState<OrderView | null>(null);
  const [msg, setMsg] = useState('');
  const place = async () => {
    try {
      const id = `o-${Date.now()}`;
      setOrder(await api.placeOrder(id, customer, [{ sku: 'demo', sellerId: seller, unitPriceInclTax: price, qty: 1, taxRatePct: tax }]));
      setMsg(''); onChange();
    } catch (e) { setMsg((e as Error).message); }
  };
  const act = async (f: (id: string) => Promise<OrderView>) => {
    if (!order) return;
    try { setOrder(await f(order.orderId)); onChange(); } catch (e) { setMsg((e as Error).message); }
  };
  return (
    <section className="card">
      <h2>Place an order</h2>
      <div className="form">
        <input value={customer} onChange={e => setCustomer(e.target.value)} placeholder="customer" />
        <input value={seller} onChange={e => setSeller(e.target.value)} placeholder="seller" />
        <input type="number" value={price} onChange={e => setPrice(+e.target.value)} title="price incl. tax (¥)" />
        <select value={tax} onChange={e => setTax(+e.target.value)}>
          <option value={10}>10% tax</option><option value={8}>8% tax (food)</option>
        </select>
        <button onClick={place}>Place</button>
      </div>
      {order && (
        <div className="order">
          <div className="row"><strong>{order.orderId}</strong><span className={`pill ${order.status}`}>{order.status}</span></div>
          <p className="small">Tax-exclusive {yen(order.taxExclusiveTotal)} · platform cap {pts(order.orderCap)}</p>
          <ul>{order.awards.map(a => <li key={a.campaignId}>{a.campaignId}: {pts(a.points)}</li>)}</ul>
          <p><strong>{pts(order.totalPoints)}</strong> chosen by the optimizer
            {order.greedyPoints < order.totalPoints && <span className="win"> · greedy would give only {pts(order.greedyPoints)}</span>}</p>
          {order.status === 'PLACED' && <div className="row gap">
            <button onClick={() => act(api.ship)}>Ship</button>
            <button className="secondary" onClick={() => act(api.cancel)}>Cancel</button>
          </div>}
        </div>
      )}
      {msg && <p className="small bad-text">{msg}</p>}
    </section>
  );
}

function CustomerPanel({ onChange }: { onChange: () => void }) {
  const [id, setId] = useState('user-1');
  const [view, setView] = useState<CustomerView | null>(null);
  const [amount, setAmount] = useState(100);
  const [msg, setMsg] = useState('');
  const load = async () => { try { setView(await api.customer(id)); setMsg(''); } catch (e) { setMsg((e as Error).message); } };
  const redeem = async () => {
    try { setView(await api.redeem(id, amount, `r-${Date.now()}`)); onChange(); setMsg(''); }
    catch (e) { setMsg((e as Error).message); }
  };
  return (
    <section className="card">
      <h2>Customer wallet</h2>
      <div className="form">
        <input value={id} onChange={e => setId(e.target.value)} />
        <button onClick={load}>Look up</button>
        <input type="number" value={amount} onChange={e => setAmount(+e.target.value)} />
        <button className="secondary" onClick={redeem}>Redeem</button>
      </div>
      {view && <>
        <p><strong>{pts(view.balance)}</strong> spendable</p>
        <table>
          <thead><tr><th>From order</th><th>Remaining</th><th>Expires</th></tr></thead>
          <tbody>{view.lots.map((l, i) => <tr key={i}><td>{l.orderId}</td><td>{pts(l.remaining)}</td>
            <td>{new Date(l.expiresAt).toLocaleDateString()}</td></tr>)}</tbody>
        </table>
        <p className="small muted">Redemptions use the lot that expires first.</p>
      </>}
      {msg && <p className="small bad-text">{msg}</p>}
    </section>
  );
}

function LedgerPanel({ entries }: { entries: Entry[] }) {
  return (
    <section className="card">
      <h2>Ledger (latest first)</h2>
      <table>
        <thead><tr><th>#</th><th>Kind</th><th>From → To</th><th>Points</th><th>Hash</th></tr></thead>
        <tbody>{entries.map(e => (
          <tr key={e.seq}>
            <td>{e.seq}</td><td><span className={`pill ${e.kind}`}>{e.kind}</span></td>
            <td className="mono">{e.from} → {e.to}</td><td>{e.amount.toLocaleString('en-US')}</td>
            <td className="mono muted">{short(e.hash)}</td>
          </tr>))}
        </tbody>
      </table>
    </section>
  );
}
