const CHANNEL_ACTIONS_API = '/api/v1/admin/channels/actions';

export async function requestChannelAdminMutation(request, path, options = {}) {
  const result = await request(path, { timeoutMs: 30_000, ...options });
  const failed = (result?.results || []).filter((entry) => entry.success !== true);
  if (failed.length) {
    const error = new Error(failed.map((entry) => entry.message ||
      `${entry.configuration_id} could not be changed`).join(' '));
    if (result.receiver) error.receiver = result.receiver;
    throw error;
  }
  return result;
}

export async function requestChannelAction(request, { action, configurationIds, revision, receiver }, options = {}) {
  const result = await requestChannelAdminMutation(request, CHANNEL_ACTIONS_API, { ...options, method: 'POST',
    body: { ...(revision != null ? { revision } : {}), action, configuration_ids: configurationIds,
      ...(receiver ? { receiver } : {}) } });
  if (configurationIds.some((id) => !result?.results?.some((entry) => entry.configuration_id === id)))
    throw new Error('Channel state could not be confirmed. Try again.');
  return result;
}
