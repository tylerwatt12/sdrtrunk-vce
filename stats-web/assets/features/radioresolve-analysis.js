'use strict';

const RADIORESOLVE_PLANNER_URL = 'https://radioresolve.com/rf-planner/';
const MAXIMUM_PLANNER_URL_LENGTH = 8192;

function buildRadioResolvePlannerUrl(planner, catalog) {
  const model = String(planner?.model || '');
  const rate = Number(planner?.rate_hz);
  if (!/^[a-z0-9-]{1,40}$/.test(model) || !Number.isSafeInteger(rate) || rate <= 0) {
    throw new Error('This tuner has no supported RF analysis profile.');
  }
  if (!Array.isArray(catalog?.channels)) {
    throw new Error('Configured channel frequencies are unavailable.');
  }

  const frequencies = catalog.channels.flatMap((channel) =>
    Array.isArray(channel?.frequencies_hz) ? channel.frequencies_hz : [])
    .map(Number)
    .filter((frequency) => Number.isSafeInteger(frequency) && frequency > 0)
    .map((frequency) => (frequency / 1_000_000).toFixed(6).replace(/0+$/, '').replace(/\.$/, ''));
  const url = new URL(RADIORESOLVE_PLANNER_URL);
  url.searchParams.set('model', model);
  url.searchParams.set('rate', String(rate));
  url.searchParams.set('version', 'sdrtrunk-vce');
  if (frequencies.length) url.searchParams.set('frequencies', frequencies.join('\n'));
  if (url.href.length > MAXIMUM_PLANNER_URL_LENGTH) {
    throw new Error('Too many configured frequencies for an external analysis link.');
  }
  return url.href;
}

export { buildRadioResolvePlannerUrl };
