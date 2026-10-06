/// <reference lib="webworker" />
import { notificationClick, receivePush, saveReceipt } from "./receipts.ts";
const worker = self as unknown as ServiceWorkerGlobalScope;
worker.addEventListener(
  "install",
  (event) => event.waitUntil(worker.skipWaiting()),
);
worker.addEventListener(
  "activate",
  (event) => event.waitUntil(worker.clients.claim()),
);
worker.addEventListener("push", (event) => {
  event.waitUntil(receivePush(event.data?.text(), new Date().toISOString(), {
    save: saveReceipt,
    show: async () => {
      await worker.registration.showNotification(
        "Harbor test notification received",
      );
      for (const client of await worker.clients.matchAll({ type: "window" })) {
        client.postMessage({ type: "receipts-changed" });
      }
    },
  }));
});
worker.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(
    notificationClick(
      event.notification.data,
      worker.registration.scope,
      async (url) => {
        const existing = (await worker.clients.matchAll({ type: "window" }))
          .find((client) => client.url === url) as WindowClient | undefined;
        if (existing) {
          await existing.focus();
        } else await worker.clients.openWindow(url);
      },
    ),
  );
});
