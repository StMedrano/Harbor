import test from 'node:test';
import assert from 'node:assert/strict';
import { markdown, page, ACCOUNT_DELETION } from '../scripts/legal-pages.mjs';

test('the documents become plain pages with nothing left unfilled', () => {
  const html = markdown('# Title\n\nFrom {{monitored_apps}} & more.\n\n- one\n- two\n\n## Next\nSee https://example.org/a.');
  assert.match(html, /<h1>Title<\/h1>/);
  assert.match(html, /<ul>\n<li>one<\/li>\n<li>two<\/li>\n<\/ul>/);
  assert.match(html, /&amp; more/);
  assert.match(html, /<a href="https:\/\/example\.org\/a">/);
  assert.doesNotMatch(html, /\{\{/);
});

test('the deletion page does what Google Play asks of it', () => {
  const html = page('Delete your account', ACCOUNT_DELETION);
  assert.match(html, /Harbor Family/, 'names the app');
  assert.match(html, /you don't need the app installed/, 'is not only a path back into the app');
  assert.match(html, /What is deleted/);
  assert.match(html, /What stays/);
  assert.match(html, /mailto:/, 'works for someone who cannot sign in');
});
