// Fictional location reports supplied to the production Map renderer. The
// embedded production no-icon asset keeps examples independent of saved icons.
const icon = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAABGdBTUEAAK/INwWK6QAAABl0RVh0U29mdHdhcmUAQWRvYmUgSW1hZ2VSZWFkeXHJZTwAAAJ5SURBVDjLpZPNS1RhFMaff2EWLWo5tGnRaqCFRBAM0cZFwVSQpVHNQAWVMQwaSSZWtimLiKnsO5lEjKzs4y1zRK3oItfMj1FnnJkaUtNrjo45H3eejpCKNa5anMX73vs855zfOS9I4n9i2SHbCpvph8q8A9PNcCzcz76EM9EETj+DmmqENaeBiJ3mRyuzQy5mwyVMKqiFbzNN0MxgKZOd2zj5GMZE/ZL5ooHZAntGW89s7Bw5Ws25llWcfQHrzHPYE/51ZOQ0M4Fiitj4UQdbzhZSb+FJ63ZypJqp7p0UsTf+FN6kvoMMl3GmNY9jj+BckcF8/HoFldLzpZIqxhthJPVdkr2cifdb5sXefyAKLFvyzVJJAssisIxstILZ0DEyeJzpHifHfNBGamFZ+C9yC7bhG7BBxCrZZqWQpoiNP6S1TMBFDh4gA0VMdxfy+0NosftQX+8gGKkBY741HLoGhbnXUOZwKTn+gGa4nOlBN9MDxdJzCTmwj+wvEKPDTPUc5Zx+kOk+NxmqZOJTIXsviYGQVgKLAos/n0CbbIAS0ir1eY9kF4O+3UzpBYzehhaugQpdR3DwKth7EeyqEoO/oYzXwyKwDDN0ipme/VKFi0l9L8M3oYW8SwxWnIKI1XT7Vqb6i/ntLoLTHdulhROcUJsZuJJjCsvEPpyf8m8io5U0VB6FtFNIe6da84XFEcYaNrDzLDw5DUZ9cEwqm6zxGWYGPBTShogtQtoerV0rLA5JKy5+ubya7SdzbKKMyRG7ByPeIfvebKfAWszUdQFavKOI0bqNbCuF4XfneAvzIaStQrpOxEpIL746rQKOD2VQbSXwtLiXg/wNTNvAOhsl8oEAAAAASUVORK5CYII=';
const clone = value => structuredClone(value);

export function createMapGuideData({ signal } = {}) {
  let disposed = false;
  const requests = [];
  const now = Date.now();
  const system = { protocol: 'P25', key: 'p25:bee00:123', name: 'County P25',
    wacn: 0xBEE00, system_id: 0x123 };
  const positions = (coordinates) => coordinates.map(([latitude, longitude], index) => ({
    latitude, longitude, timestamp_ms: now - index * 45000
  }));
  const entities = [
    { id: 'guide-map-engine7', label: 'Engine 7', identifier: '7007',
      alias_list: 'County P25', serving_system: system, icon: 'no-icon', heading: 95, speed_kph: 28,
      positions: positions([[38.8924, -77.0385], [38.8923, -77.0400], [38.8922, -77.0415],
        [38.8919, -77.0428], [38.8912, -77.0428]]) },
    { id: 'guide-map-patrol12', label: 'Patrol 12', identifier: '2112',
      alias_list: 'County P25', serving_system: system, icon: 'no-icon', heading: 180, speed_kph: 35,
      positions: positions([[38.8878, -77.0300], [38.8890, -77.0300], [38.8902, -77.0300],
        [38.8911, -77.0300], [38.8911, -77.0285]]) }
  ];
  async function fetchSnapshot() {
    if (disposed || signal?.aborted) throw new DOMException('The Map example was closed.', 'AbortError');
    requests.push({ operation: 'snapshot' });
    return clone({ generated_at_ms: now, entities, dropped_observations: 0, evicted_entities: 0 });
  }
  return { fetchSnapshot, iconUrl: () => icon, requests, dispose() { disposed = true; } };
}
