// Fictional public documents for the production Topics workspace. The tour only
// reads these local documents; notification connections and saved devices stay out.
const ROOT = '/api/v1/alerts';
const clone = value => structuredClone(value);
const topics = [
  { id: 'guide-fire-activity', name: 'Fire activity',
    description: 'Dispatches and notable activity from Regional Fire and City Fire.',
    visible: true, enabled: true },
  { id: 'guide-receiver-health', name: 'Receiver health',
    description: 'Reception changes and receiver conditions that need attention.',
    visible: true, enabled: true }
];

export function createTopicsGuideData({ signal, deviceConnected = false } = {}) {
  const requests = [];
  const now = Date.now();
  const items = [
    { id: 'guide-alert-fire-1', topic_id: 'guide-fire-activity', occurred_at: now - 180000,
      title: 'Regional Fire dispatch',
      body: 'County P25 · Regional Fire: Engine 7 and Rescue 2 dispatched to a structure fire.' },
    { id: 'guide-alert-health-1', topic_id: 'guide-receiver-health', occurred_at: now - 420000,
      title: 'County P25 reception recovered',
      body: 'Control channel decoding returned to normal after a period of poor reception.' },
    { id: 'guide-alert-fire-2', topic_id: 'guide-fire-activity', occurred_at: now - 840000,
      title: 'City Fire activity',
      body: 'City P25 · City Fire: Units reported arriving at the station after an alarm response.' }
  ];
  const requestJson = async (path, options = {}) => {
    if (signal?.aborted || options.signal?.aborted) {
      throw new DOMException('The Topics example was closed.', 'AbortError');
    }
    const method = String(options.method || 'GET').toUpperCase();
    const url = new URL(path, 'https://tour.invalid');
    requests.push({ method, path: url.pathname + url.search });
    if (method !== 'GET' || url.origin !== 'https://tour.invalid') {
      throw new Error('Topics examples only read local sample data.');
    }
    if (url.pathname === ROOT + '/topics' && !url.search) {
      return { enabled: true, push_enabled: true, mode: 'proxy', topics: clone(topics) };
    }
    if (url.pathname === ROOT + '/history' &&
        [...url.searchParams.keys()].every(key => ['limit', 'offset', 'topic_id'].includes(key))) {
      const topicId = url.searchParams.get('topic_id') || '';
      const limit = Number(url.searchParams.get('limit') || 30);
      const offset = Number(url.searchParams.get('offset') || 0);
      if ((topicId && !topics.some(topic => topic.id === topicId)) ||
          !Number.isInteger(limit) || limit < 1 || limit > 100 || !Number.isInteger(offset) || offset < 0) {
        throw new Error('Unknown Topics example selection.');
      }
      const filtered = items.filter(item => !topicId || item.topic_id === topicId);
      return { total: filtered.length, items: clone(filtered.slice(offset, offset + limit)) };
    }
    throw new Error(`Unsupported Topics example request: ${method} ${url.pathname}`);
  };
  const guideDevice = deviceConnected ? { id: 'guide-notification-device',
    manage_token: 'guide-only-notification-token', mode: 'proxy', status: 'connected', expires_at_ms: 0 } : null;
  return { requestJson, guideDevice, requests };
}
