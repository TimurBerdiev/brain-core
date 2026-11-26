import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';

const shots = fileURLToPath(new URL('../screenshots/', import.meta.url));
const app = '/boss-web/';

test.beforeAll(async () => { await mkdir(shots, { recursive: true }); });

test('sign-in on iPhone', async ({ page }) => {
  await page.goto(app);
  await expect(page.getByRole('heading', { name: 'Connect your Brain' })).toBeVisible();
  await expect(page.getByLabel('Brain repository')).toBeVisible();
  await expect(page.getByLabel('Fine-grained personal access token')).toHaveAttribute('type', 'password');
  await expect(page.getByRole('link', { name: 'Create a fine-grained GitHub token' }))
    .toHaveAttribute('href', 'https://github.com/settings/personal-access-tokens/new');
  await page.screenshot({ path: `${shots}sign-in.png`, fullPage: true });
});

test('sample queue, completed, answer, move and search on iPhone', async ({ page }) => {
  const external = [];
  page.context().on('request', request => {
    if (!request.url().startsWith('http://127.0.0.1:4173/')) external.push(request.url());
  });
  await page.goto(app + '?demo');
  await expect(page.getByRole('heading', { name: 'For you' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Agents' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'On hold' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Backlog' })).toBeVisible();
  await expect(page.getByText('Review how Boss shows decision priorities')).toBeHidden();
  const decisionRows = page.locator('.group.input-needed button');
  await expect(decisionRows).toHaveText([/Resume Boss work after a decision.*P1/, /Make Boss easy to open on iPhone.*P2/]);
  await expect(page.locator('main')).not.toContainText('null');
  await page.screenshot({ path: `${shots}list.png`, fullPage: true });

  await page.getByRole('checkbox', { name: "Show everyone's tasks" }).check();
  await expect(page.getByText('Review how Boss shows decision priorities')).toBeVisible();
  await page.getByRole('button', { name: 'checks: off' }).click();
  await expect(page.getByText('Resume Boss work after a decision')).toBeHidden();
  await expect(page.getByText('Check the Boss preview build')).toBeVisible();
  await page.getByRole('button', { name: 'All', exact: true }).click();
  await page.getByText('Completed', { exact: true }).click();
  await expect(page.getByRole('button', { name: /Publish the Boss web app shell/ })).toBeVisible();
  await expect(page.getByText('Research a separate Boss demo app')).toBeHidden();
  await page.getByText('Completed', { exact: true }).click();

  await page.getByRole('button', { name: /Resume Boss work after a decision/ }).click();
  await expect(page.getByRole('heading', { name: 'How should the first Boss worker resume after an answer?' })).toBeVisible();
  await expect(page.getByText('Recommendation: Run on demand', { exact: false })).toBeVisible();
  await page.screenshot({ path: `${shots}card.png`, fullPage: true });
  await page.getByRole('button', { name: 'Run on demand', exact: true }).click();
  await page.getByRole('button', { name: 'Send answer' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Answer sent. Moved to Ready.' })).toBeVisible();
  await expect(page.locator('.group.input-needed')).not.toContainText('Resume Boss work after a decision');
  await page.getByRole('button', { name: /Resume Boss work after a decision/ }).click();
  await page.getByText('Log (2)', { exact: true }).click();
  await expect(page.getByText('answer', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Move', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Status').selectOption('on-hold');
  await dialog.getByLabel('Waiting on', { exact: true }).fill('Release approval');
  await dialog.getByLabel('Check after (optional)').fill('2026-11-27');
  await page.screenshot({ path: `${shots}move.png` });
  await dialog.getByRole('button', { name: 'Move task' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Moved to On hold.' })).toBeVisible();
  await expect(page.getByRole('button', { name: /Resume Boss work after a decision.*Release approval/ })).toBeVisible();

  await page.getByRole('button', { name: 'Search', exact: true }).click();
  const search = page.getByRole('searchbox', { name: 'Search all tasks' });
  await search.fill('checks#38');
  await expect(page.getByText('Check the Boss preview build')).toBeVisible();
  await expect(page.getByText('Resume Boss work after a decision')).toBeHidden();
  await expect(page.locator('main')).not.toContainText('null');
  await page.screenshot({ path: `${shots}search.png`, fullPage: true });
  await search.fill('demo/boss#42');
  await expect(page.getByText('Resume Boss work after a decision')).toBeVisible();
  await search.fill('separate Boss demo');
  await expect(page.getByText('Research a separate Boss demo app')).toBeVisible();
  expect(external).toEqual([]);
});

test('Ask instead sends a question and returns the task to Ready', async ({ page }) => {
  await page.goto(app + '?demo');
  await page.getByRole('button', { name: /Resume Boss work after a decision/ }).click();
  await page.getByText('Ask instead', { exact: true }).click();
  await page.getByRole('textbox', { name: 'Your question' }).fill('Who signs off on the release?');
  await page.getByRole('button', { name: 'Send question' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Question sent. Moved to Ready.' })).toBeVisible();
  await page.getByRole('button', { name: /Resume Boss work after a decision/ }).click();
  await page.getByText('Log (2)', { exact: true }).click();
  await expect(page.getByText('Who signs off on the release?', { exact: true })).toBeVisible();
});

test('collapsed task details and log do not request embedded images', async ({ page }) => {
  const images = [];
  await page.route('https://example.com/*.png', async route => {
    images.push(new URL(route.request().url()).pathname);
    await route.fulfill({ status: 204 });
  });
  await page.addInitScript(() => {
    localStorage.setItem('boss-web.settings', JSON.stringify({ repo: 'owner/brain', token: 'sample' }));
    const issue = { number: 42, title: 'Task with images', body: '![Body](https://example.com/body.png)',
      labels: [{ name: 'backlog' }], state: 'open', updated_at: '2026-11-25T10:00:00Z', html_url: '' };
    const realFetch = window.fetch;
    window.fetch = (input, options) => {
      const url = new URL(input);
      if (url.origin !== 'https://api.github.com') return realFetch(input, options);
      const path = url.pathname.replace('/repos/owner/brain/', '');
      const data = {
        'contents/repos.yaml': { content: btoa('repos: {}') },
        'contents/workspace.yaml': { content: btoa('github: owner') },
        'issues': [issue],
        'issues/42': issue,
        'issues/42/comments': [{ body: '![Log](https://example.com/log.png)' }],
      }[path];
      return Promise.resolve(new Response(JSON.stringify(data)));
    };
  });
  await page.goto(app);
  await page.getByRole('button', { name: /Task with images/ }).click();
  const log = page.getByText('Log (1)', { exact: true });
  await expect(log).toBeVisible();
  await expect(page.locator('img')).toHaveCount(0);
  expect(images).toEqual([]);
  await log.click();
  await expect.poll(() => images).toEqual(['/log.png']);
  await page.getByText('Task details', { exact: true }).click();
  await expect.poll(() => images).toEqual(['/log.png', '/body.png']);
});
