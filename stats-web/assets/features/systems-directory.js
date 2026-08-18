(() => {
  'use strict';

  const API_PATH = '/api/v1/systems';
  const DIRECTORY_LIMIT = 25;

  function invalidResponse(message) {
    const error = new Error(message || 'The systems directory response is invalid.');
    error.code = 'invalid_response';
    error.path = API_PATH;
    return error;
  }

  function requiredInteger(value, field, minimum = 0) {
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < minimum) {
      throw invalidResponse(`The systems directory ${field} is invalid.`);
    }
    return parsed;
  }

  function decode(response) {
    if (!response || typeof response !== 'object' || Array.isArray(response) || !Array.isArray(response.rows)) {
      throw invalidResponse();
    }

    const parentRows = response.rows.slice();
    const tableRows = [];
    let truncatedParentCount = 0;

    parentRows.forEach((system) => {
      if (!system || typeof system !== 'object' || Array.isArray(system) ||
          !Array.isArray(system.site_preview) || typeof system.site_preview_truncated !== 'boolean') {
        throw invalidResponse('A systems directory row has an invalid site preview.');
      }

      tableRows.push({ ...system, directory_type: 'system' });
      system.site_preview.forEach((site) => {
        if (!site || typeof site !== 'object' || Array.isArray(site)) {
          throw invalidResponse('A systems directory site preview is invalid.');
        }
        tableRows.push({ ...site, directory_type: 'site' });
      });

      if (system.site_preview_truncated) truncatedParentCount += 1;
    });

    const limit = requiredInteger(response.limit, 'page limit', 1);
    const offset = requiredInteger(response.offset, 'page offset');
    if (typeof response.has_more !== 'boolean') {
      throw invalidResponse('The systems directory paging state is invalid.');
    }

    const nextOffset = response.next_offset === null || response.next_offset === undefined ? null :
      requiredInteger(response.next_offset, 'next page offset');
    if (response.has_more && (nextOffset === null || nextOffset <= offset)) {
      throw invalidResponse('The systems directory next page offset is invalid.');
    }

    const page = {
      rows: parentRows,
      limit,
      offset,
      has_more: response.has_more,
      next_offset: nextOffset
    };
    if (response.total_count !== undefined && response.total_count !== null) {
      page.total_count = requiredInteger(response.total_count, 'total count');
    }

    return {
      parentRows,
      tableRows,
      page,
      previewLimit: requiredInteger(response.site_preview_limit_per_system,
        'site preview limit', 1),
      truncatedParentCount
    };
  }

  async function load(api, parameters = {}) {
    if (typeof api !== 'function') throw new TypeError('A systems API request function is required.');
    const response = await api(API_PATH, {
      ...parameters,
      limit: DIRECTORY_LIMIT,
      includeSitePreview: true
    });
    return decode(response);
  }

  window.sdrtrunkSystemsDirectory = Object.freeze({ load, decode });
})();
