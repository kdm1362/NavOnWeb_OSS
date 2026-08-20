import { createHash } from "node:crypto";
import { copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const cloudflareRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(cloudflareRoot, "..");
const androidAssetRoot = path.join(repositoryRoot, "app", "src", "main", "assets", "tesla");
const outputRoot = path.join(cloudflareRoot, "dist", "pages");
const indexSource = path.join(androidAssetRoot, "index.html");
const appSource = path.join(androidAssetRoot, "app.js");
const headersTemplate = path.join(cloudflareRoot, "pages", "_headers.template");
const pagesSourceRoot = path.join(cloudflareRoot, "pages");
const manifestSource = path.join(pagesSourceRoot, "manifest.webmanifest");
const iconSourceRoot = path.join(pagesSourceRoot, "icons");
const landingSource = path.join(pagesSourceRoot, "landing.html");
const landingStyleSource = path.join(pagesSourceRoot, "landing.css");
const privacySource = path.join(pagesSourceRoot, "privacy.html");
const robotsSource = path.join(pagesSourceRoot, "robots.txt");
const sitemapSource = path.join(pagesSourceRoot, "sitemap.xml");
const redirectsSource = path.join(pagesSourceRoot, "_redirects");
const notFoundSource = path.join(pagesSourceRoot, "404.html");
const searchConsoleVerificationFileName = "google08d940d1ee3c9069.html";
const searchConsoleVerificationSource = path.join(
  pagesSourceRoot,
  searchConsoleVerificationFileName,
);
const scriptMarker = '<script src="/app.js"></script>';
const headMarker = "</head>";
const bodyMarker = "<body>";
const titleMarker = "<title>NavOnWeb</title>";
const htmlMarker = '<html lang="en" data-i18n-pending>';
const viewerMarker = '  <section id="viewer"';
const playStoreCtaMarker = "<!--NAVONWEB_PLAY_STORE_CTA-->";
// Keep a stable Pages-only asset envelope so a poisoned content-addressed upload
// can be invalidated without changing the Android-packaged web application.
const pagesAssetFormatMarker = "/* NavOnWeb Pages asset format v2. */";
const siteVariants = Object.freeze([
  {
    outputPath: "index.html",
    language: null,
    canonicalUrl: "https://navonweb.com/",
    title: "NavOnWeb | Vehicle browser projection · 차량 브라우저 연결",
    description: "Connect a supported phone-projection session to a nearby vehicle browser over a local network. 휴대전화의 차량용 화면을 같은 로컬 네트워크의 브라우저에서 연결합니다.",
    openGraphTitle: "NavOnWeb | Phone projection in a vehicle browser",
    openGraphDescription: "View, hear and control a supported phone-projection session from a compatible browser on the same local network.",
    openGraphLocale: "en_US",
    openGraphAlternateLocale: "ko_KR",
  },
  {
    outputPath: path.join("ko", "index.html"),
    language: "ko",
    canonicalUrl: "https://navonweb.com/ko/",
    title: "NavOnWeb | 차량 브라우저에서 휴대전화 화면 연결",
    description: "NavOnWeb로 지원되는 휴대전화 프로젝션 화면을 같은 네트워크의 차량 브라우저에서 보고 듣고 제어하세요.",
    openGraphTitle: "NavOnWeb | 차량 브라우저 프로젝션",
    openGraphDescription: "지원되는 휴대전화 프로젝션 화면을 같은 네트워크의 차량 브라우저에서 연결합니다.",
    openGraphLocale: "ko_KR",
    openGraphAlternateLocale: "en_US",
  },
  {
    outputPath: path.join("en", "index.html"),
    language: "en",
    canonicalUrl: "https://navonweb.com/en/",
    title: "NavOnWeb | Phone projection in a vehicle browser",
    description: "View, hear, and control a supported phone-projection session from a compatible vehicle browser on the same network.",
    openGraphTitle: "NavOnWeb | Phone projection in a vehicle browser",
    openGraphDescription: "Connect a supported phone-projection session to a compatible vehicle browser on the same network.",
    openGraphLocale: "en_US",
    openGraphAlternateLocale: "ko_KR",
  },
]);
const pwaHeadMarkup = [
  '  <meta name="theme-color" content="#05080c">',
  '  <meta name="application-name" content="NavOnWeb">',
  '  <meta name="mobile-web-app-capable" content="yes">',
  '  <meta name="apple-mobile-web-app-capable" content="yes">',
  '  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">',
  '  <meta name="apple-mobile-web-app-title" content="NavOnWeb">',
  '  <link rel="manifest" href="/manifest.webmanifest">',
  '  <link rel="icon" type="image/png" sizes="32x32" href="/icons/favicon-32.png">',
  '  <link rel="apple-touch-icon" sizes="180x180" href="/icons/apple-touch-icon.png">',
].join("\n");
const iconFileNames = [
  "favicon-32.png",
  "apple-touch-icon.png",
  "icon-192.png",
  "icon-512.png",
  "icon-maskable-512.png",
];
const marketingMediaFiles = [
  {
    source: path.join(repositoryRoot, "docs", "user-guide", "screenshots", "01-welcome.png"),
    output: "navonweb-welcome-ko.png",
  },
  {
    source: path.join(repositoryRoot, "docs", "user-guide", "screenshots", "landing-phone-welcome-en.png"),
    output: "navonweb-welcome-en.png",
  },
  {
    source: path.join(repositoryRoot, "docs", "user-guide", "screenshots", "landing-phone-main-premium-ko.png"),
    output: "navonweb-premium-running-ko.png",
  },
  {
    source: path.join(repositoryRoot, "docs", "user-guide", "screenshots", "landing-phone-main-premium-en.png"),
    output: "navonweb-premium-running-en.png",
  },
  {
    source: path.join(repositoryRoot, "docs", "user-guide", "screenshots", "landing-browser-connected-ko.png"),
    output: "navonweb-browser-connected-ko.png",
  },
  {
    source: path.join(repositoryRoot, "docs", "user-guide", "screenshots", "landing-browser-connected-en.png"),
    output: "navonweb-browser-connected-en.png",
  },
];
const options = parseArguments(process.argv.slice(2));
const signalingOrigin = validateSignalingOrigin(
  options.signalOrigin || process.env.NAVONWEB_SIGNALING_WEBSOCKET_ORIGIN,
  options.requireSecure,
);
const signalingPathPrefix = validateSignalingPathPrefix(
  options.signalPathPrefix || process.env.NAVONWEB_SIGNALING_PATH_PREFIX || "",
);
const playStoreUrl = validatePlayStoreUrl(
  options.playStoreUrl || process.env.NAVONWEB_PLAY_STORE_URL || "",
);
const signalingHttpOrigin = toSignalingHttpOrigin(signalingOrigin);

const [sourceIndex, sourceApp, sourceHeaders, sourceLanding, sourceLandingStyle] = await Promise.all([
  readFile(indexSource, "utf8"),
  readFile(appSource, "utf8"),
  readFile(headersTemplate, "utf8"),
  readFile(landingSource, "utf8"),
  readFile(landingStyleSource, "utf8"),
]);
const builtApp = `${pagesAssetFormatMarker}\n${sourceApp}`;
const builtLandingStyle = `${pagesAssetFormatMarker}\n${sourceLandingStyle}`;
const marketingMediaRevisions = new Map(await Promise.all(
  marketingMediaFiles.map(async ({ source, output }) => [
    output,
    createHash("sha256").update(await readFile(source)).digest("hex").slice(0, 16),
  ]),
));
if (!sourceIndex.includes(scriptMarker)) {
  throw new Error(`Packaged index is missing the expected marker: ${scriptMarker}`);
}
if (!sourceIndex.includes(headMarker) || !sourceIndex.includes(titleMarker) ||
    !sourceIndex.includes(htmlMarker)) {
  throw new Error(`Packaged index is missing the expected marker: ${headMarker}`);
}
if (!sourceIndex.includes(bodyMarker) || !sourceIndex.includes(viewerMarker)) {
  throw new Error("Packaged index is missing a Cloudflare landing-page insertion marker");
}
if (!sourceLanding.includes(playStoreCtaMarker)) {
  throw new Error(`Landing page is missing the expected marker: ${playStoreCtaMarker}`);
}

const appRevision = createHash("sha256").update(builtApp, "utf8").digest("hex").slice(0, 16);
const landingStyleRevision = createHash("sha256")
  .update(builtLandingStyle, "utf8")
  .digest("hex")
  .slice(0, 16);
const revisionedScriptMarker = `<script src="/app.js?v=${appRevision}"></script>`;
const landingWithStoreCta = sourceLanding.replace(
  playStoreCtaMarker,
  renderPlayStoreCta(playStoreUrl),
);
const builtLanding = marketingMediaFiles.reduce(
  (html, { output }) => html.replaceAll(
    `/media/${output}`,
    `/media/${output}?v=${marketingMediaRevisions.get(output)}`,
  ),
  landingWithStoreCta,
);
const builtIndexes = new Map(siteVariants.map((variant) => [
  variant.outputPath,
  sourceIndex
    .replace(
      htmlMarker,
      variant.language
        ? `<html lang="${variant.language}" data-navonweb-language="${variant.language}" data-i18n-pending>`
        : htmlMarker,
    )
    .replace(titleMarker, `<title>${variant.title}</title>`)
    .replace(
      headMarker,
      `${renderSeoHeadMarkup(variant)}\n${pwaHeadMarkup}\n` +
        `  <link rel="stylesheet" href="/landing.css?v=${landingStyleRevision}">\n${headMarker}`,
    )
    .replace(bodyMarker, '<body class="navonweb-marketing-page">')
    .replace(viewerMarker, `${builtLanding}\n\n${viewerMarker}`)
    .replace(
      scriptMarker,
      `<script src="/cloud-config.js"></script>\n${revisionedScriptMarker}`,
    ),
]));
const cloudConfig = [
  "/* Generated by scripts/build-pages.mjs. This file contains no secret. */",
  "window.NAVONWEB_CLOUD_CONFIG = Object.freeze({",
  `  signalingWebSocketOrigin: ${JSON.stringify(signalingOrigin)},`,
  `  signalingWebSocketPathPrefix: ${JSON.stringify(signalingPathPrefix)}`,
  "});",
  "",
].join("\n");
const builtHeaders = sourceHeaders
  .replaceAll("__SIGNALING_HTTP_ORIGIN__", signalingHttpOrigin)
  .replaceAll("__SIGNALING_WEBSOCKET_ORIGIN__", signalingOrigin);

// outputRoot is a fixed child of cloudflare/dist, never a user-provided path.
await rm(outputRoot, { recursive: true, force: true });
await mkdir(outputRoot, { recursive: true });
await mkdir(path.join(outputRoot, "icons"), { recursive: true });
await mkdir(path.join(outputRoot, "media"), { recursive: true });
await mkdir(path.join(outputRoot, "ko"), { recursive: true });
await mkdir(path.join(outputRoot, "en"), { recursive: true });
await Promise.all([
  ...[...builtIndexes].map(([outputPath, content]) =>
    writeFile(path.join(outputRoot, outputPath), content, "utf8")),
  writeFile(path.join(outputRoot, "app.js"), builtApp, "utf8"),
  writeFile(path.join(outputRoot, "landing.css"), builtLandingStyle, "utf8"),
  copyFile(privacySource, path.join(outputRoot, "privacy.html")),
  copyFile(redirectsSource, path.join(outputRoot, "_redirects")),
  copyFile(notFoundSource, path.join(outputRoot, "404.html")),
  writeFile(path.join(outputRoot, "cloud-config.js"), cloudConfig, "utf8"),
  writeFile(path.join(outputRoot, "_headers"), builtHeaders, "utf8"),
  copyFile(manifestSource, path.join(outputRoot, "manifest.webmanifest")),
  copyFile(robotsSource, path.join(outputRoot, "robots.txt")),
  copyFile(sitemapSource, path.join(outputRoot, "sitemap.xml")),
  copyFile(
    searchConsoleVerificationSource,
    path.join(outputRoot, searchConsoleVerificationFileName),
  ),
  ...iconFileNames.map((fileName) => copyFile(
    path.join(iconSourceRoot, fileName),
    path.join(outputRoot, "icons", fileName),
  )),
  ...marketingMediaFiles.map(({ source, output }) => copyFile(
    source,
    path.join(outputRoot, "media", output),
  )),
]);

console.log(`Built Pages assets from ${path.relative(repositoryRoot, androidAssetRoot)}`);
console.log(`Signaling origin: ${signalingOrigin}`);
console.log(`Signaling path prefix: ${signalingPathPrefix || "(none)"}`);
console.log(`Output: ${path.relative(repositoryRoot, outputRoot)}`);

function renderSeoHeadMarkup(variant) {
  return [
    `  <meta name="description" content="${variant.description}">`,
    '  <meta name="robots" content="index,follow,max-image-preview:large">',
    `  <link rel="canonical" href="${variant.canonicalUrl}">`,
    '  <link rel="alternate" hreflang="ko" href="https://navonweb.com/ko/">',
    '  <link rel="alternate" hreflang="en" href="https://navonweb.com/en/">',
    '  <link rel="alternate" hreflang="x-default" href="https://navonweb.com/">',
    '  <meta name="google-site-verification" content="SEisdu4RJBadbJcdZvUbthHGqn2ViQTVXjYZANruvCE">',
    '  <meta property="og:type" content="website">',
    '  <meta property="og:site_name" content="NavOnWeb">',
    `  <meta property="og:locale" content="${variant.openGraphLocale}">`,
    `  <meta property="og:locale:alternate" content="${variant.openGraphAlternateLocale}">`,
    `  <meta property="og:title" content="${variant.openGraphTitle}">`,
    `  <meta property="og:description" content="${variant.openGraphDescription}">`,
    `  <meta property="og:url" content="${variant.canonicalUrl}">`,
    '  <meta property="og:image" content="https://navonweb.com/icons/icon-512.png">',
    '  <meta property="og:image:width" content="512">',
    '  <meta property="og:image:height" content="512">',
    '  <meta property="og:image:alt" content="NavOnWeb app icon">',
    '  <meta name="twitter:card" content="summary">',
    `  <meta name="twitter:title" content="${variant.openGraphTitle}">`,
    `  <meta name="twitter:description" content="${variant.openGraphDescription}">`,
  ].join("\n");
}

function parseArguments(argumentsList) {
  const parsed = {
    signalOrigin: "",
    signalPathPrefix: "",
    playStoreUrl: "",
    requireSecure: false,
  };
  for (const argument of argumentsList) {
    if (argument === "--require-secure") {
      parsed.requireSecure = true;
    } else if (argument.startsWith("--signal-origin=")) {
      parsed.signalOrigin = argument.slice("--signal-origin=".length);
    } else if (argument.startsWith("--signal-path-prefix=")) {
      parsed.signalPathPrefix = argument.slice("--signal-path-prefix=".length);
    } else if (argument.startsWith("--play-store-url=")) {
      parsed.playStoreUrl = argument.slice("--play-store-url=".length);
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  return parsed;
}

function validatePlayStoreUrl(value) {
  const candidate = String(value || "").trim();
  if (candidate === "") return "";
  let url;
  try {
    url = new URL(candidate);
  } catch (_) {
    throw new Error("Play Store URL must be a valid https://play.google.com app details URL");
  }
  if (url.protocol !== "https:" || url.hostname !== "play.google.com" ||
      url.username || url.password || url.hash ||
      url.pathname !== "/store/apps/details" ||
      url.searchParams.get("id") !== "com.eigenkodex.navonweb") {
    throw new Error(
      "Play Store URL must target com.eigenkodex.navonweb on https://play.google.com/store/apps/details",
    );
  }
  return url.href;
}

function renderPlayStoreCta(playStoreUrl) {
  if (playStoreUrl) {
    const escapedUrl = playStoreUrl.replaceAll("&", "&amp;").replaceAll('"', "&quot;");
    return `<a class="marketing-store-cta" href="${escapedUrl}" rel="external" ` +
      'data-i18n="landingPlayStoreCta">Get NavOnWeb on Google Play</a>';
  }
  return '<span class="marketing-store-cta" role="link" aria-disabled="true" ' +
    'data-i18n="landingPlayStoreComingSoon">Coming soon on Google Play</span>';
}

function validateSignalingPathPrefix(value) {
  if (value === "") return "";
  if (typeof value !== "string" ||
      !/^\/[A-Za-z0-9._~-]+(?:\/[A-Za-z0-9._~-]+)*$/u.test(value)) {
    throw new Error("Signaling path prefix must be empty or an origin-relative path without a trailing slash");
  }
  return value;
}

function validateSignalingOrigin(value, requireSecure) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error("Set NAVONWEB_SIGNALING_WEBSOCKET_ORIGIN to the deployed wss:// origin");
  }
  const url = new URL(value.trim());
  const loopback = url.hostname === "localhost" ||
    url.hostname === "127.0.0.1" ||
    url.hostname === "[::1]";
  const validScheme = url.protocol === "wss:" ||
    (!requireSecure && url.protocol === "ws:" && loopback);
  if (!validScheme || url.username || url.password || url.pathname !== "/" ||
      url.search || url.hash) {
    throw new Error("Signaling origin must be wss:// with no path/query; ws:// is local-only");
  }
  return url.origin;
}

function toSignalingHttpOrigin(webSocketOrigin) {
  const url = new URL(webSocketOrigin);
  url.protocol = url.protocol === "wss:" ? "https:" : "http:";
  return url.origin;
}
