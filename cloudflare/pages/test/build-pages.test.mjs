import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";

const cloudflareRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
);

test("Pages CSP permits both HTTPS bootstrap and secure WebSocket signaling", () => {
  execFileSync(
    process.execPath,
    [
      "scripts/build-pages.mjs",
      "--require-secure",
      "--signal-origin=wss://navonweb.com",
      "--signal-path-prefix=/_nw",
    ],
    { cwd: cloudflareRoot, stdio: "pipe" },
  );

  const headers = readFileSync(path.join(cloudflareRoot, "dist", "pages", "_headers"), "utf8");
  assert.match(
    headers,
    /connect-src 'self' https:\/\/navonweb\.com wss:\/\/navonweb\.com;/,
  );
  assert.doesNotMatch(headers, /__SIGNALING_(?:HTTP|WEBSOCKET)_ORIGIN__/);
  const cloudConfig = readFileSync(
    path.join(cloudflareRoot, "dist", "pages", "cloud-config.js"),
    "utf8",
  );
  assert.match(cloudConfig, /signalingWebSocketPathPrefix: "\/_nw"/u);

});

test("Pages build publishes installable NavOnWeb identity and icons", () => {
  execFileSync(
    process.execPath,
    [
      "scripts/build-pages.mjs",
      "--require-secure",
      "--signal-origin=wss://navonweb.com",
      "--signal-path-prefix=/_nw",
    ],
    { cwd: cloudflareRoot, stdio: "pipe" },
  );

  const outputRoot = path.join(cloudflareRoot, "dist", "pages");
  const index = readFileSync(path.join(outputRoot, "index.html"), "utf8");
  const koreanIndex = readFileSync(path.join(outputRoot, "ko", "index.html"), "utf8");
  const englishIndex = readFileSync(path.join(outputRoot, "en", "index.html"), "utf8");
  assert.match(index, /<meta name="theme-color" content="#05080c">/u);
  assert.match(index, /<link rel="manifest" href="\/manifest\.webmanifest">/u);
  assert.match(
    index,
    /<link rel="icon" type="image\/png" sizes="32x32" href="\/icons\/favicon-32\.png">/u,
  );
  assert.match(
    index,
    /<link rel="apple-touch-icon" sizes="180x180" href="\/icons\/apple-touch-icon\.png">/u,
  );
  assert.match(index, /<body class="navonweb-marketing-page">/u);
  assert.match(index, /<section id="prepairing-marketing"/u);
  assert.ok(
    index.indexOf('id="pairing-panel"') < index.indexOf('id="prepairing-marketing"') &&
      index.indexOf('id="prepairing-marketing"') < index.indexOf('id="viewer"'),
    "the landing content must follow pairing and precede the authenticated viewer",
  );
  assert.match(index, /<h1 id="marketing-title"/u);
  assert.match(index, /<title>NavOnWeb \| Vehicle browser projection · 차량 브라우저 연결<\/title>/u);
  assert.match(index, /<meta name="description"/u);
  assert.match(index, /<link rel="canonical" href="https:\/\/navonweb\.com\/">/u);
  assert.match(index, /hreflang="ko" href="https:\/\/navonweb\.com\/ko\/"/u);
  assert.match(index, /hreflang="en" href="https:\/\/navonweb\.com\/en\/"/u);
  assert.match(index, /hreflang="x-default" href="https:\/\/navonweb\.com\/"/u);
  assert.match(
    koreanIndex,
    /<html lang="ko" data-navonweb-language="ko" data-i18n-pending>/u,
  );
  assert.match(koreanIndex, /<title>NavOnWeb \| 차량 브라우저에서 휴대전화 화면 연결<\/title>/u);
  assert.match(koreanIndex, /<meta name="description" content="NavOnWeb로 지원되는/u);
  assert.match(koreanIndex, /<link rel="canonical" href="https:\/\/navonweb\.com\/ko\/">/u);
  assert.match(koreanIndex, /<meta property="og:locale" content="ko_KR">/u);
  assert.match(
    englishIndex,
    /<html lang="en" data-navonweb-language="en" data-i18n-pending>/u,
  );
  assert.match(englishIndex, /<title>NavOnWeb \| Phone projection in a vehicle browser<\/title>/u);
  assert.match(englishIndex, /<link rel="canonical" href="https:\/\/navonweb\.com\/en\/">/u);
  assert.match(englishIndex, /<meta property="og:locale" content="en_US">/u);
  for (const localizedIndex of [koreanIndex, englishIndex]) {
    assert.match(localizedIndex, /hreflang="ko" href="https:\/\/navonweb\.com\/ko\/"/u);
    assert.match(localizedIndex, /hreflang="en" href="https:\/\/navonweb\.com\/en\/"/u);
    assert.match(localizedIndex, /hreflang="x-default" href="https:\/\/navonweb\.com\/"/u);
  }
  assert.match(
    index,
    /<meta name="google-site-verification" content="SEisdu4RJBadbJcdZvUbthHGqn2ViQTVXjYZANruvCE">/u,
  );
  assert.match(index, /class="marketing-store-cta" role="link" aria-disabled="true"/u);
  assert.doesNotMatch(index, /class="marketing-store-cta" href=/u);

  const landingCss = readFileSync(path.join(outputRoot, "landing.css"), "utf8");
  assert.match(landingCss, /^\/\* NavOnWeb Pages asset format v2\. \*\//u);
  assert.match(
    landingCss,
    /body\.navonweb-authenticated #prepairing-marketing\s*\{\s*display: none;/u,
  );
  assert.match(landingCss, /@media \(max-width: 860px\)/u);
  assert.match(landingCss, /@media \(max-width: 520px\)/u);
  assert.match(landingCss, /@media \(prefers-reduced-motion: reduce\)/u);
  assert.match(landingCss, /min-height: calc\(100svh - var\(--marketing-peek-height\)\)/u);
  assert.doesNotMatch(index, /<br\s*\/?\s*>/iu);
  assert.match(
    landingCss,
    /\.marketing-shell\s*\{[^}]*word-break: keep-all;[^}]*overflow-wrap: normal;/u,
  );
  assert.doesNotMatch(landingCss, /overflow-wrap:\s*(?:anywhere|break-word)/u);
  assert.doesNotMatch(
    landingCss,
    /\.marketing-hero h1\s*\{[^}]*max-width:/u,
  );
  assert.doesNotMatch(landingCss, /text-wrap:\s*balance/u);
  assert.match(index, /class="marketing-peek"/u);
  assert.match(index, /class="marketing-browser-showcase"/u);

  const manifest = JSON.parse(
    readFileSync(path.join(outputRoot, "manifest.webmanifest"), "utf8"),
  );
  assert.equal(manifest.id, "/");
  assert.equal(manifest.name, "NavOnWeb");
  assert.equal(manifest.short_name, "NavOnWeb");
  assert.equal(Object.hasOwn(manifest, "lang"), false);
  assert.match(manifest.description, /supported phone projection/u);
  assert.match(manifest.description, /차량용 화면/u);
  assert.equal(manifest.start_url, "/");
  assert.equal(manifest.scope, "/");
  assert.equal(manifest.display, "standalone");
  assert.deepEqual(
    manifest.icons.map(({ src, sizes, type, purpose }) => ({ src, sizes, type, purpose })),
    [
      {
        src: "/icons/icon-192.png",
        sizes: "192x192",
        type: "image/png",
        purpose: "any",
      },
      {
        src: "/icons/icon-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "any",
      },
      {
        src: "/icons/icon-maskable-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "maskable",
      },
    ],
  );

  const expectedDimensions = new Map([
    ["favicon-32.png", [32, 32]],
    ["apple-touch-icon.png", [180, 180]],
    ["icon-192.png", [192, 192]],
    ["icon-512.png", [512, 512]],
    ["icon-maskable-512.png", [512, 512]],
  ]);
  for (const [fileName, dimensions] of expectedDimensions) {
    const source = readFileSync(path.join(cloudflareRoot, "pages", "icons", fileName));
    const output = readFileSync(path.join(outputRoot, "icons", fileName));
    assert.deepEqual(output, source, `${fileName} must be copied without modification`);
    assert.deepEqual(readPngDimensions(output), dimensions, `${fileName} dimensions`);
  }

  const androidLauncher = readFileSync(path.resolve(
    cloudflareRoot,
    "..",
    "app",
    "src",
    "main",
    "res",
    "mipmap-xxxhdpi",
    "ic_launcher.png",
  ));
  assert.deepEqual(
    readFileSync(path.join(outputRoot, "icons", "icon-192.png")),
    androidLauncher,
    "the primary 192px web app icon must exactly match the Android launcher icon",
  );

  const screenshotSources = new Map([
    [
      "navonweb-welcome-ko.png",
      path.resolve(cloudflareRoot, "..", "docs", "user-guide", "screenshots", "01-welcome.png"),
    ],
    [
      "navonweb-welcome-en.png",
      path.resolve(cloudflareRoot, "..", "docs", "user-guide", "screenshots", "landing-phone-welcome-en.png"),
    ],
    [
      "navonweb-premium-running-ko.png",
      path.resolve(cloudflareRoot, "..", "docs", "user-guide", "screenshots", "landing-phone-main-premium-ko.png"),
    ],
    [
      "navonweb-premium-running-en.png",
      path.resolve(cloudflareRoot, "..", "docs", "user-guide", "screenshots", "landing-phone-main-premium-en.png"),
    ],
    [
      "navonweb-browser-connected-ko.png",
      path.resolve(cloudflareRoot, "..", "docs", "user-guide", "screenshots", "landing-browser-connected-ko.png"),
    ],
    [
      "navonweb-browser-connected-en.png",
      path.resolve(cloudflareRoot, "..", "docs", "user-guide", "screenshots", "landing-browser-connected-en.png"),
    ],
  ]);
  for (const [outputName, sourcePath] of screenshotSources) {
    const source = readFileSync(sourcePath);
    assert.deepEqual(
      readFileSync(path.join(outputRoot, "media", outputName)),
      source,
      `${outputName} must reuse the reviewed user-guide screenshot without modification`,
    );
    const revision = createHash("sha256").update(source).digest("hex").slice(0, 16);
    assert.ok(
      index.includes(`/media/${outputName}?v=${revision}`),
      `${outputName} must use a content revision so the CDN cannot retain an older screenshot`,
    );
  }

  const localizedApp = readFileSync(path.join(outputRoot, "app.js"), "utf8");
  const packagedApp = readFileSync(path.resolve(
    cloudflareRoot,
    "..",
    "app",
    "src",
    "main",
    "assets",
    "web",
    "app.js",
  ), "utf8");
  assert.match(localizedApp, /^\/\* NavOnWeb Pages asset format v2\. \*\//u);
  assert.equal(
    localizedApp,
    `/* NavOnWeb Pages asset format v2. */\n${packagedApp}`,
    "Pages app.js must be the canonical packaged app with only the build marker prepended",
  );
  assert.match(
    localizedApp,
    /localControlRecovering: '로컬 제어 연결을 복구하는 중입니다\.'/u,
  );
  assert.match(
    localizedApp,
    /localControlRecovering: 'Recovering the local control connection\.'/u,
  );
  const appRevision = createHash("sha256").update(localizedApp, "utf8").digest("hex").slice(0, 16);
  const landingStyleRevision = createHash("sha256")
    .update(landingCss, "utf8")
    .digest("hex")
    .slice(0, 16);
  assert.match(index, new RegExp(`/app\\.js\\?v=${appRevision}`, "u"));
  assert.match(index, new RegExp(`/landing\\.css\\?v=${landingStyleRevision}`, "u"));
  assert.match(
    localizedApp,
    /landingLocalBody: '휴대전화 핫스팟이나 같은 Wi-Fi를 이용합니다\. 프로젝션 미디어는 브라우저와 휴대전화 사이의 직접 경로로 전송됩니다\.'/u,
  );
  assert.match(
    localizedApp,
    /landingLocalBody: 'Use your phone\\'s hotspot or the same Wi-Fi network\. Projection media stays on the direct browser-to-phone path\.'/u,
  );
  assert.doesNotMatch(localizedApp, /네트워크가 허용하는 경우|whenever the network allows it/u);

  const robots = readFileSync(path.join(outputRoot, "robots.txt"), "utf8");
  assert.match(robots, /^User-agent: \*\r?\nAllow: \/$/mu);
  assert.match(robots, /Sitemap: https:\/\/navonweb\.com\/sitemap\.xml/u);
  const sitemap = readFileSync(path.join(outputRoot, "sitemap.xml"), "utf8");
  assert.match(sitemap, /<urlset xmlns="http:\/\/www\.sitemaps\.org\/schemas\/sitemap\/0\.9">/u);
  assert.match(sitemap, /<loc>https:\/\/navonweb\.com\/<\/loc>/u);
  assert.match(sitemap, /<loc>https:\/\/navonweb\.com\/ko\/<\/loc>/u);
  assert.match(sitemap, /<loc>https:\/\/navonweb\.com\/en\/<\/loc>/u);
  assert.match(sitemap, /<loc>https:\/\/navonweb\.com\/privacy<\/loc>/u);
  const redirects = readFileSync(path.join(outputRoot, "_redirects"), "utf8");
  assert.match(redirects, /^\/w \/ 301$/mu);
  assert.match(redirects, /^\/index\.html \/ 301$/mu);
  assert.match(redirects, /^\/landing\.html \/ 301$/mu);
  const notFound = readFileSync(path.join(outputRoot, "404.html"), "utf8");
  assert.match(notFound, /<meta name="robots" content="noindex,follow">/u);
  assert.match(notFound, /페이지를 찾을 수 없습니다/u);
  const privacy = readFileSync(path.join(outputRoot, "privacy.html"), "utf8");
  assert.match(index, /href="\/privacy" data-i18n="landingPrivacyPolicy"/u);
  assert.match(privacy, /<h2>NavOnWeb 개인정보 처리방침<\/h2>/u);
  assert.match(privacy, /<h2>NavOnWeb Privacy Policy<\/h2>/u);
  assert.match(privacy, /보고서는 전송일로부터 30일 후 삭제/u);
  assert.match(privacy, /Reports are deleted 30 days after submission/u);
  assert.match(privacy, /https:\/\/github\.com\/kdm1362\/NavOnWeb_OSS\/issues/u);
  assert.equal(
    readFileSync(path.join(outputRoot, "google08d940d1ee3c9069.html"), "utf8").trim(),
    "google-site-verification: google08d940d1ee3c9069.html",
  );

  const head = index.match(/<head>([\s\S]*?)<\/head>/u)?.[1] || "";
  const metadata = [...head.matchAll(/<(?:title|meta|link)\b[^>]*>/gmu)]
    .map(match => match[0])
    .join("\n");
  assert.doesNotMatch(metadata, /Android Auto|Tesla|Carlinkit/iu);
  assert.match(index, /<h1 id="marketing-title"[^>]*>Bring Android Auto to a nearby browser/u);
});

test("Pages build enables only the configured NavOnWeb Play Store listing", () => {
  const playStoreUrl =
    "https://play.google.com/store/apps/details?id=com.eigenkodex.navonweb&hl=ko";
  execFileSync(
    process.execPath,
    [
      "scripts/build-pages.mjs",
      "--require-secure",
      "--signal-origin=wss://navonweb.com",
      "--signal-path-prefix=/_nw",
      `--play-store-url=${playStoreUrl}`,
    ],
    { cwd: cloudflareRoot, stdio: "pipe" },
  );

  const index = readFileSync(path.join(cloudflareRoot, "dist", "pages", "index.html"), "utf8");
  assert.match(
    index,
    /class="marketing-store-cta" href="https:\/\/play\.google\.com\/store\/apps\/details\?id=com\.eigenkodex\.navonweb&amp;hl=ko"/u,
  );
  assert.match(index, /data-i18n="landingPlayStoreCta"/u);
  assert.doesNotMatch(index, /aria-disabled="true"/u);

  assert.throws(
    () => execFileSync(
      process.execPath,
      [
        "scripts/build-pages.mjs",
        "--require-secure",
        "--signal-origin=wss://navonweb.com",
        "--play-store-url=https://example.com/fake-app",
      ],
      { cwd: cloudflareRoot, stdio: "pipe" },
    ),
    /Command failed/u,
  );
});

function readPngDimensions(bytes) {
  assert.equal(bytes.subarray(1, 4).toString("ascii"), "PNG");
  assert.equal(bytes.subarray(12, 16).toString("ascii"), "IHDR");
  return [bytes.readUInt32BE(16), bytes.readUInt32BE(20)];
}
