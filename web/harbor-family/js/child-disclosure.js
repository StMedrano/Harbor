// What a child is told about Harbor Family, on the web. Pure: no DOM, no network.
// Mirrors the Android "What Harbor watches" and location panels word for word where it can. This browser itself
// shares nothing, so every statement is about the Harbor Family Android app on the child's phone, and there is
// deliberately no off switch: only a parent can stop it, by removing the phone.

/** The fixed list of apps the Android app may check, in the order the Android panel lists them (MonitoredApps.labels()). */
export const MONITORED_APPS = ['Messages', 'WhatsApp', 'Facebook', 'Messenger', 'Instagram', 'TikTok', 'Snapchat', 'Discord', 'Telegram', 'X'];

export const SECTIONS = [
  {
    title: 'Pairing',
    lines: ['Your parents can see that this phone is paired to your family and when it last synced.'],
  },
  {
    title: 'Screen time',
    lines: ['The Harbor Family Android app measures how long apps are used, only while sharing is on. It does not limit or block apps.'],
  },
  {
    title: 'Location',
    lines: [
      'The Harbor Family Android app sends your phone’s location to your parents about every 5 minutes while moving or 15 minutes while still, when Android allows: latitude, longitude, accuracy and time. It is not live tracking.',
      'Updates may be delayed by location permissions or battery settings. A notification shows on your phone while it shares, and places are kept for up to 30 days.',
    ],
  },
  {
    title: 'What Harbor watches',
    lines: [
      `Harbor watches new notifications from these apps and your incoming text messages: ${MONITORED_APPS.join(', ')}.`,
      'It checks them on your phone for possible concerns: bullying or harassment, self-harm or wellbeing, adult content, drugs or alcohol, violence or threats, and contact from an unknown adult.',
      'When something is flagged, your parents are sent only the category, which app it came from, the time, and whether the sender is saved in your contacts. Your parents are never sent the message text, who sent it, a phone number, or any part of the message.',
      'Harbor can miss things: muted or hidden message previews and end-to-end encrypted messages may not be seen, and it makes mistakes in both directions. It does not read your whole conversations.',
    ],
  },
];

export const BROWSER_NOTE = 'This browser does not measure screen time, share location or check messages. Those come only from the Harbor Family Android app.';
export const PARENT_ONLY = 'Only your parents can turn these off, by removing this phone in Harbor Family.';

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export function renderDisclosure() {
  return SECTIONS.map(s => `<div class="lab">${esc(s.title)}</div><div class="panel"><div class="row"><div>${s.lines.map(l => `<p class="rs">${esc(l)}</p>`).join('')}</div></div></div>`).join('')
    + `<p class="note">${esc(BROWSER_NOTE)}</p><p class="rs">${esc(PARENT_ONLY)}</p>`;
}
