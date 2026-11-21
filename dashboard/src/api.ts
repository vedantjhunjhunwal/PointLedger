// Typed client for the PointLedger REST API.

export interface Campaign {
  id: string; sellerId: string; rateBp: number; group: string; perOrderCap: number;
  budget: number; minSubtotal: number; startsAt: number; endsAt: number;
}
export interface CampaignView {
  campaign: Campaign; available: number; reserved: number; awarded: number; refunded: number; closed: boolean;
}
export interface Award { campaignId: string; points: number }
export interface OrderView {
  orderId: string; customerId: string; status: 'PLACED' | 'SHIPPED' | 'CANCELLED';
  taxExclusiveTotal: number; orderCap: number; awards: Award[]; totalPoints: number; greedyPoints: number;
}
export interface LotView { orderId: string; remaining: number; expiresAt: number }
export interface CustomerView { customerId: string; balance: number; lots: LotView[] }
export interface Entry {
  seq: number; timestamp: number; from: string; to: string; amount: number; kind: string; ref: string; hash: string;
}
export interface Audit {
  ledger: { ok: boolean; sumOfBalances: number; entries: number; headHash: string; message: string };
  campaignsBalanced: boolean; lotsMatchBalances: boolean; overspentCampaigns: number; ok: boolean;
}
export interface LineInput { sku: string; sellerId: string; unitPriceInclTax: number; qty: number; taxRatePct: number }

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, init);
  const body = await res.json();
  if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`);
  return body as T;
}

const post = <T,>(path: string, body: unknown = {}, key?: string) =>
  call<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(key ? { 'Idempotency-Key': key } : {}) },
    body: JSON.stringify(body),
  });

export const api = {
  campaigns: () => call<CampaignView[]>('/api/campaigns'),
  audit: () => call<Audit>('/api/audit'),
  ledger: (limit = 25) => call<{ size: number; head: string; entries: Entry[] }>(`/api/ledger?limit=${limit}`),
  customer: (id: string) => call<CustomerView>(`/api/customers/${encodeURIComponent(id)}`),
  createCampaign: (c: Omit<Campaign, 'minSubtotal' | 'startsAt' | 'endsAt'>) =>
    post<CampaignView>('/api/campaigns', c, `campaign-${c.id}`),
  closeCampaign: (id: string) => post<CampaignView>(`/api/campaigns/${encodeURIComponent(id)}/close`),
  placeOrder: (orderId: string, customerId: string, lines: LineInput[]) =>
    post<OrderView>('/api/orders', { orderId, customerId, lines }, `order-${orderId}`),
  ship: (id: string) => post<OrderView>(`/api/orders/${encodeURIComponent(id)}/ship`),
  cancel: (id: string) => post<OrderView>(`/api/orders/${encodeURIComponent(id)}/cancel`),
  redeem: (customerId: string, points: number, redemptionId: string) =>
    post<CustomerView>(`/api/customers/${encodeURIComponent(customerId)}/redeem`, { points, redemptionId }, redemptionId),
};
