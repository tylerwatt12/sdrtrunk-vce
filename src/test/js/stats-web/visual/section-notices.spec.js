const { expect, test } = require('@playwright/test');

for (const [name, viewport] of [
  ['desktop', { width: 1280, height: 720 }],
  ['mobile', { width: 390, height: 844 }]
]) {
  test(`saved activity notices have space on both sides ${name}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto('/design-system.html?theme=light&view=gallery');
    await page.locator('.design-system-gallery').evaluate((gallery) => {
      gallery.insertAdjacentHTML('beforeend', `
        <section class="section ui-section" id="activity-notice-sample">
          <div class="section-title ui-section-title">System Activity</div>
          <div class="ui-notice ui-notice-warning ui-notice-spaced">New activity is not being saved.</div>
        </section>
        <section class="section ui-section" id="dashboard-notice-sample">
          <div class="section-title ui-section-title">Source radios</div>
          <div class="dashboard-activity-radio-body">
            <div class="dashboard-activity-radio-host">
              <div class="ui-notice ui-notice-warning ui-notice-spaced ui-inset-notice">No saved activity is available.</div>
            </div>
          </div>
        </section>
        <section class="section ui-section" id="dashboard-result-notice-sample">
          <div class="section-title ui-section-title">Source radios</div>
          <div class="dashboard-activity-radio-body">
            <div class="dashboard-activity-radio-host">
              <div class="dashboard-activity-radio-result">
                <div class="ui-notice ui-notice-warning ui-notice-spaced ui-inset-notice">New activity is not being saved.</div>
              </div>
            </div>
          </div>
        </section>`);
    });

    for (const selector of [
      '#activity-notice-sample > .ui-notice',
      '#dashboard-notice-sample .ui-inset-notice',
      '#dashboard-result-notice-sample .ui-inset-notice'
    ]) {
      const notice = page.locator(selector);
      await expect(notice).toBeVisible();
      const inset = await notice.evaluate((element) => {
        const parent = element.parentElement.getBoundingClientRect();
        const bounds = element.getBoundingClientRect();
        const title = element.closest('.ui-section').querySelector(':scope > .ui-section-title')
          .getBoundingClientRect();
        return { left: bounds.left - parent.left, right: parent.right - bounds.right,
          top: bounds.top - title.bottom };
      });
      expect(inset.left, `${selector} should have left inset`).toBeGreaterThanOrEqual(8);
      expect(inset.right, `${selector} should have right inset`).toBeGreaterThanOrEqual(8);
      expect(inset.top, `${selector} should sit below the title`).toBeGreaterThanOrEqual(8);
      expect(Math.abs(inset.left - inset.right), `${selector} should be centered`).toBeLessThanOrEqual(2);
    }
  });
}
