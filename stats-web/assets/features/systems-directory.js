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

  function requiredPreviewLimit(value) {
    if (typeof value !== 'number' || !Number.isInteger(value) || value < 1) {
      throw invalidResponse('The systems directory site preview limit is invalid.');
    }
    return value;
  }

  function decode(page) {
    if (!page || typeof page !== 'object' || Array.isArray(page) || !Array.isArray(page.rows)) {
      throw invalidResponse();
    }

    const parentRows = page.rows;
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

    return {
      parentRows,
      tableRows,
      page,
      previewLimit: requiredPreviewLimit(page.site_preview_limit_per_system),
      truncatedParentCount
    };
  }

  async function load(apiPage, parameters = {}) {
    if (typeof apiPage !== 'function') throw new TypeError('A systems page request function is required.');
    const page = await apiPage(API_PATH, {
      ...parameters,
      limit: DIRECTORY_LIMIT,
      includeSitePreview: true
    });
    return decode(page);
  }

  window.sdrtrunkSystemsDirectory = Object.freeze({ load, decode });
})();
