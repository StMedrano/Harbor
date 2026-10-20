import test from 'node:test';
import assert from 'node:assert/strict';
import { toAlertsView, renderAlertsList, renderAlertDetail } from '../js/alerts-view.js';

const alert = (id, at, over = {}) => ({ id, appLabel: 'WhatsApp', category: 'bullying', ruleSetVersion: 1, at, savedContact: false, ...over });
const page = (alerts, over = {}) => ({ state: 'available', notificationAccess: true, smsPermission: true, observedAt: '2026-10-18T11:00:00.000Z', receivedAt: '2026-10-18T11:01:00.000Z', alerts, nextBefore: null, ...over });

test('every alert reads as a possible concern', () => {
  const v = toAlertsView([page([alert('a', '2026-10-18T10:00:00.000Z'), alert('b', '2026-10-18T09:00:00.000Z', { category: 'unknown_adult_contact' })])]);
  const html = renderAlertsList(v, { name: 'Ava' });
  assert.match(html, /Possible bullying or harassment/);
  assert.match(html, /Possible contact from an unknown adult/);
  assert.ok(!/is being bullied|confirmed|unsafe|child is safe/i.test(html));
});

test('pages merge newest first with no duplicates, and unknown categories are dropped', () => {
  const v = toAlertsView([page([alert('a', '2026-10-18T10:00:00.000Z')], { nextBefore: '2026-10-18T10:00:00.000Z' }), page([alert('a', '2026-10-18T10:00:00.000Z'), alert('c', '2026-10-17T10:00:00.000Z'), alert('d', '2026-10-17T09:00:00.000Z', { category: 'gossip' })])]);
  assert.deepEqual(v.alerts.map(a => a.id), ['a', 'c']);
  assert.equal(v.nextBefore, null);
});

test('a next page shows a "show older" control', () => {
  const v = toAlertsView([page([alert('a', '2026-10-18T10:00:00.000Z')], { nextBefore: '2026-10-18T10:00:00.000Z' })]);
  assert.match(renderAlertsList(v), /data-a="alertsmore"/);
});

test('self-harm alerts carry guidance and crisis resources', () => {
  const v = toAlertsView([page([alert('s', '2026-10-18T10:00:00.000Z', { category: 'self_harm' })])]);
  const html = renderAlertDetail(v.alerts[0]);
  assert.match(html, /988/);
  assert.match(html, /741741/);
  assert.match(html, /not a diagnosis/);
});

test('no message text, sender or excerpt is ever rendered, even if a reply smuggles one in', () => {
  const v = toAlertsView([page([alert('a', '2026-10-18T10:00:00.000Z', { text: 'SECRET TEXT', sender: 'Bob', excerpt: 'leak' })])]);
  const html = renderAlertsList(v) + renderAlertDetail(v.alerts[0]);
  assert.ok(!/SECRET TEXT|Bob|leak/.test(html));
});

test('access being off is stated, and an empty list never claims the child is safe', () => {
  const html = renderAlertsList(toAlertsView([page([], { notificationAccess: false, smsPermission: false })]), { name: 'Ava' });
  assert.match(html, /Notification access is off/);
  assert.match(html, /SMS permission is off/);
  assert.match(html, /doesn’t mean nothing happened/);
  assert.match(html, /never claims a message is safe/);
});

test('none, expired and malformed replies', () => {
  assert.match(renderAlertsList(toAlertsView([{ state: 'none' }])), /No alerts set up yet/);
  assert.match(renderAlertsList(toAlertsView([{ state: 'expired' }])), /30 days/);
  assert.equal(toAlertsView(null).status, 'unavailable');
  assert.equal(toAlertsView([{ state: 'weird' }]).status, 'unavailable');
});

test('app labels are escaped', () => {
  const v = toAlertsView([page([alert('a', '2026-10-18T10:00:00.000Z', { appLabel: '<img src=x onerror=1>' })])]);
  const html = renderAlertsList(v) + renderAlertDetail(v.alerts[0]);
  assert.ok(!html.includes('<img'));
});
