export const previewOrigin =
  "https://harbor-git-feat-notification-accepta-83c20a-stalinvmedrano-1274.vercel.app";
export type AcceptanceSite = {
  pageUrl: string;
  workerUrl: string;
  scope: string;
};
export function acceptanceSite(value: string): AcceptanceSite {
  const url = new URL(value);
  if (url.username || url.password) {
    throw Error("Unapproved acceptance location.");
  }
  let scope: string;
  if (
    url.origin === "http://localhost:3000" &&
    ["/", "/index.html"].includes(url.pathname)
  ) scope = "/";
  else if (
    url.origin === previewOrigin &&
    ["/acceptance/", "/acceptance/index.html"].includes(url.pathname)
  ) scope = "/acceptance/";
  else throw Error("Unapproved acceptance location.");
  const pageUrl = url.origin + scope;
  return { pageUrl, workerUrl: pageUrl + "service-worker.js", scope };
}
export function requirePreviewBuild(environment: string | undefined): void {
  if (environment !== "preview") {
    throw Error(
      "Development acceptance deployment requires Vercel preview; production is forbidden.",
    );
  }
}
