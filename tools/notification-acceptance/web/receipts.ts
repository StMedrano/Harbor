/// <reference lib="dom" />
import { notificationRoute } from "../../../supabase/functions/_shared/notification.ts";
import type { NotificationRouteRefV1 } from "../../../packages/contracts/src/v1/notifications.ts";
export interface Receipt {
  route: NotificationRouteRefV1;
  receivedAt: string;
}
export function parseReceipt(
  value: unknown,
  receivedAt: string,
): Receipt | null {
  if (typeof value === "string") {
    try {
      value = JSON.parse(value);
    } catch {
      return null;
    }
  }
  const route = notificationRoute(value);
  return route && Number.isFinite(Date.parse(receivedAt))
    ? { route, receivedAt }
    : null;
}
export async function receivePush(
  value: unknown,
  receivedAt: string,
  deps: { save(r: Receipt): Promise<void>; show(): Promise<void> },
): Promise<void> {
  const receipt = parseReceipt(value, receivedAt);
  if (!receipt) return;
  await deps.save(receipt);
  await deps.show();
}
export async function notificationClick(
  _untrusted: unknown,
  origin: string,
  open: (url: string) => Promise<unknown>,
): Promise<void> {
  await open(new URL("/", origin).href);
}
function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("harbor.acceptance.receipts", 1);
    request.onupgradeneeded = () =>
      request.result.createObjectStore("receipts", { autoIncrement: true });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(Error("Receipt storage unavailable"));
  });
}
export async function saveReceipt(receipt: Receipt): Promise<void> {
  const validated = parseReceipt(receipt.route, receipt.receivedAt);
  if (!validated) throw Error("Invalid receipt");
  const db = await database();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction("receipts", "readwrite");
      tx.objectStore("receipts").add(validated);
      tx.oncomplete = () => resolve();
      tx.onerror = tx.onabort = () => reject(Error("Receipt save failed"));
    });
  } finally {
    db.close();
  }
}
export async function listReceipts(): Promise<Receipt[]> {
  const db = await database();
  try {
    return await new Promise<Receipt[]>((resolve, reject) => {
      const request = db.transaction("receipts", "readonly").objectStore(
        "receipts",
      ).getAll();
      request.onsuccess = () =>
        resolve(request.result.flatMap((value: Receipt) => {
          const receipt = parseReceipt(value.route, value.receivedAt);
          return receipt ? [receipt] : [];
        }));
      request.onerror = () => reject(Error("Receipt read failed"));
    });
  } finally {
    db.close();
  }
}
