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
  await expect(page.getByText('Review the analytics plan')).toBeHidden();
  await expect(page.locator('main')).not.toContainText('null');
  await page.screenshot({ path: `${shots}list.png`, fullPage: true });

  await page.getByRole('checkbox', { name: "Show everyone's tasks" }).check();
  await expect(page.getByText('Review the analytics plan')).toBeVisible();
  await page.getByRole('button', { name: 'site: off' }).click();
  await expect(page.getByText('Choose the launch checklist')).toBeHidden();
  await expect(page.getByText('Update the landing page copy')).toBeVisible();
  await page.getByRole('button', { name: 'All', exact: true }).click();
  await page.getByText('Completed', { exact: true }).click();
  await expect(page.getByRole('button', { name: /Set up the project workspace/ })).toBeVisible();
  await expect(page.getByText('Retire the old launch notes')).toBeHidden();
  await page.getByText('Completed', { exact: true }).click();

  await page.getByRole('button', { name: /Choose the launch checklist/ }).click();
  await expect(page.getByRole('heading', { name: 'Which checklist should we use for launch?' })).toBeVisible();
  await expect(page.getByText('Recommendation: Short checklist')).toBeVisible();
  await page.screenshot({ path: `${shots}card.png`, fullPage: true });
  await page.getByRole('button', { name: 'Short checklist', exact: true }).click();
  await page.getByRole('button', { name: 'Send answer' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Answer sent. Moved to Ready.' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'For you' })).toBeHidden();
  await page.getByRole('button', { name: /Choose the launch checklist/ }).click();
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
  await expect(page.getByRole('button', { name: /Choose the launch checklist.*Release approval/ })).toBeVisible();

  await page.getByRole('button', { name: 'Search', exact: true }).click();
  const search = page.getByRole('searchbox', { name: 'Search all tasks' });
  await search.fill('site#38');
  await expect(page.getByText('Update the landing page copy')).toBeVisible();
  await expect(page.getByText('Choose the launch checklist')).toBeHidden();
  await expect(page.locator('main')).not.toContainText('null');
  await page.screenshot({ path: `${shots}search.png`, fullPage: true });
  await search.fill('old launch notes');
  await expect(page.getByText('Retire the old launch notes')).toBeVisible();
  expect(external).toEqual([]);
});

test('Ask instead sends a question and returns the task to Ready', async ({ page }) => {
  await page.goto(app + '?demo');
  await page.getByRole('button', { name: /Choose the launch checklist/ }).click();
  await page.getByText('Ask instead', { exact: true }).click();
  await page.getByRole('textbox', { name: 'Your question' }).fill('Who signs off on the release?');
  await page.getByRole('button', { name: 'Send question' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Question sent. Moved to Ready.' })).toBeVisible();
  await page.getByRole('button', { name: /Choose the launch checklist/ }).click();
  await page.getByText('Log (2)', { exact: true }).click();
  await expect(page.getByText('Who signs off on the release?', { exact: true })).toBeVisible();
});
