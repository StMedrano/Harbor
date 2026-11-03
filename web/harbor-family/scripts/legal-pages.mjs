// Google Play needs the privacy policy and a way to request account deletion at public addresses. The policy and terms are
// built from the same files the app shows, so the two cannot disagree. Runs before every build; the output is not committed.
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { MONITORED_APPS } from '../js/child-disclosure.js';

const root = new URL('../', import.meta.url);
const asset = name => readFileSync(new URL(`../../../apps/parent-android/app/src/main/assets/legal/${name}`, import.meta.url), 'utf8');
const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const inline = s => esc(s).replace(/\*\*(.+?)\*\*/g, '<b>$1</b>').replace(/https:\/\/[^\s<)]*[^\s<).,]/g, u => `<a href="${u}">${u}</a>`);

/** Only what these documents use: headings, bullets and paragraphs. */
export function markdown(text) {
  const out = []; let list = false;
  for (const raw of text.replace(/\{\{monitored_apps\}\}/g, MONITORED_APPS.join(', ')).split(/\r?\n/)) {
    const line = raw.trim();
    const item = line.match(/^[-*] (.+)/);
    if (!item && list) { out.push('</ul>'); list = false; }
    if (!line) continue;
    if (item) { if (!list) { out.push('<ul>'); list = true; } out.push(`<li>${inline(item[1])}</li>`); continue; }
    const head = line.match(/^(#{1,3}) (.+)/);
    out.push(head ? `<h${head[1].length}>${inline(head[2])}</h${head[1].length}>` : `<p>${inline(line)}</p>`);
  }
  if (list) out.push('</ul>');
  return out.join('\n');
}

export const page = (title, body) => `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)} · Harbor Family</title><style>
:root{color-scheme:light dark;--bg:#f4f0e7;--ink:#0b2027;--ink2:#46606a;--acc:#1f6b5c}
@media (prefers-color-scheme:dark){:root{--bg:#0b2027;--ink:#f4f0e7;--ink2:#abc0c3;--acc:#79d5c5}}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:44rem;margin:0 auto;padding:32px 16px 64px}h1{font-size:1.9rem;line-height:1.2;margin:0 0 16px}h2{font-size:1.15rem;margin:28px 0 6px}p,li{color:var(--ink2)}a{color:var(--acc)}
.who{font-weight:700;color:var(--acc);letter-spacing:.02em;margin:0 0 20px}
</style></head><body><main><p class="who">Harbor Family</p>${body}</main></body></html>`;

export const ACCOUNT_DELETION = `<h1>Delete your Harbor Family account</h1>
<p>Harbor Family is made by Stalin Medrano. You can delete your parent account at any time, and you don't need the app installed to do it.</p>
<h2>In the Android app</h2><p>Open Family, then Settings, then <b>Delete my account</b>, and type DELETE to confirm.</p>
<h2>In a web browser</h2><p>Sign in at <a href="/">the Harbor Family web app</a>, open the Family tab, choose <b>Delete</b> next to Delete my account, and type DELETE to confirm.</p>
<h2>If you can't sign in</h2><p>Email <a href="mailto:stalinmedrano@mytechtactics.com?subject=Delete%20my%20Harbor%20Family%20account">stalinmedrano@mytechtactics.com</a> from the address on your account and ask us to delete it. We'll confirm when it's done.</p>
<h2>What is deleted</h2><ul><li>Your sign-in, your email address, your name and any place you were sharing, straight away.</li>
<li>Any family where you are the only parent, with its children, their phones, and everything those phones sent: places, screen time, activity, requests, check-ins and alerts.</li></ul>
<h2>What stays</h2><ul><li>A family with another parent stays with that parent, including its children and what their phones sent. Sharing from those phones continues until that parent removes them.</li>
<li>A record that an action took place may stay in our security log, with your identity removed.</li></ul>
<h2>A child's phone</h2><p>A child has no account. A parent removes a child's phone in the Family tab, which deletes what that phone sent.</p>`;

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const publicDir = new URL('public/', root);
  writeFileSync(new URL('privacy.html', publicDir), page('Privacy policy', markdown(asset('privacy-policy.md'))));
  writeFileSync(new URL('terms.html', publicDir), page('Terms of service', markdown(asset('terms-of-service.md'))));
  writeFileSync(new URL('account-deletion.html', publicDir), page('Delete your account', ACCOUNT_DELETION));
}
