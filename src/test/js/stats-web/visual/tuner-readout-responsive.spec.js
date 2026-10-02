'use strict';

const { test, expect } = require('@playwright/test');
const { installSiteStyleApplication, operatorTuner } = require('./fixtures/site-style-app.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const sampleRate = '2.048 MHz';

for (const width of [320, 390]) {
  for (const state of ['live', 'setup']) {
    for (const theme of ['light', 'dark']) {
      test(`tuner sample rate fits and stays accessible ${state} ${theme} ${width}`, async ({ page }) => {
        await page.setViewportSize({ width, height: 844 });
        const fixture = await installSiteStyleApplication(page, theme);
        const tuner = operatorTuner({ operator_state: state });
        const rateSetting = tuner.settings.find(setting => setting.id === 'sample_rate');
        rateSetting.value = sampleRate;
        rateSetting.options = [{ value: sampleRate, label: sampleRate },
          { value: '1024 kHz', label: '1024 kHz' }];
        await page.route('**/api/v1/admin/tuners', route =>
          route.fulfill({ json: { data: { tuners: [tuner] } } }));
        await page.goto('/app.html?view=tuners');

        const select = page.locator('select[data-tuner-setting="sample_rate"]');
        await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
        await expect(select).toBeVisible();
        await expect(select).toHaveAccessibleName('Sample rate');
        await expect(select).toHaveValue(sampleRate);
        await expect(select.locator('option:checked')).toHaveText(sampleRate);

        const geometry = await select.evaluate(element => {
          const styles = getComputedStyle(element);
          const canvas = document.createElement('canvas');
          const context = canvas.getContext('2d');
          context.font = styles.font;
          return {
            textWidth: context.measureText(element.selectedOptions[0].textContent).width,
            // The shared select reserves right padding for its arrow; neither
            // that area nor the left inset can be used to display the value.
            availableWidth: element.clientWidth - parseFloat(styles.paddingLeft) -
              parseFloat(styles.paddingRight)
          };
        });
        expect(geometry.textWidth).toBeGreaterThan(0);
        expect(geometry.availableWidth, 'The full formatted rate must fit before the select arrow')
          .toBeGreaterThanOrEqual(geometry.textWidth);
        await expectNoHorizontalOverflow(page);

        if (state === 'live') await expect(select).toBeDisabled();
        else await expect(select).toBeEnabled();
        const keyboardTarget = state === 'live' ? page.locator('[data-tuner-help="sample_rate"]') : select;
        for (let index = 0; index < 80; index++) {
          await page.keyboard.press('Tab');
          if (await keyboardTarget.evaluate(element => element === document.activeElement)) break;
        }
        await expect(keyboardTarget).toBeFocused();
        expect(fixture.unexpected).toEqual([]);
        expect(fixture.pageErrors).toEqual([]);
      });
    }
  }
}
