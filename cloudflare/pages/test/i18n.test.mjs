import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";

const cloudflareRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
);
const repositoryRoot = path.resolve(cloudflareRoot, "..");
const appScript = readFileSync(
  path.join(repositoryRoot, "app", "src", "main", "assets", "web", "app.js"),
  "utf8",
);
const indexHtml = readFileSync(
  path.join(repositoryRoot, "app", "src", "main", "assets", "web", "index.html"),
  "utf8",
);
const landingHtml = readFileSync(
  path.join(cloudflareRoot, "pages", "landing.html"),
  "utf8",
);
const manifest = JSON.parse(
  readFileSync(path.join(cloudflareRoot, "pages", "manifest.webmanifest"), "utf8"),
);

function sourceBetween(startMarker, endMarker) {
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start + startMarker.length);
  assert.ok(start >= 0, `missing source marker: ${startMarker}`);
  assert.ok(end > start, `missing source marker: ${endMarker}`);
  return appScript.slice(start, end);
}

function loadLocaleResolvers(navigatorValue = {}) {
  const source = sourceBetween(
    "  function resolveBrowserLanguageCandidates",
    "  function browserRunsOnWindows",
  );
  return Function(
    "navigator",
    `"use strict";\n${source}\nreturn {\n` +
      "  resolveBrowserLanguageCandidates,\n" +
      "  resolveSystemLocale,\n" +
      "  resolvePathLocale,\n" +
      "  resolveNoticeLocaleCandidates,\n" +
      "  resolveDateTimeLocale,\n" +
      "};",
  )(navigatorValue);
}

test("system locale resolver selects Korean or English and safely falls back", () => {
  const {
    resolveBrowserLanguageCandidates,
    resolveSystemLocale,
    resolveNoticeLocaleCandidates,
  } = loadLocaleResolvers({
    languages: ["ko-KR", "en-US"],
    language: "ko-KR",
  });

  assert.deepEqual(
    resolveBrowserLanguageCandidates(),
    ["ko-KR", "en-US", "ko-KR"],
  );
  assert.equal(resolveSystemLocale(["ko-KR"]), "ko");
  assert.equal(resolveSystemLocale(["en-GB"]), "en");
  assert.equal(resolveSystemLocale(["ja-JP", "ko-KR"]), "ko");
  assert.equal(resolveSystemLocale(["ja-JP"]), "en");
  assert.equal(resolveSystemLocale([]), "en");
  assert.deepEqual(
    [...resolveNoticeLocaleCandidates(["ko-KR", "en-GB"], "ko")],
    ["ko-kr", "ko", "en-gb", "en"],
  );
});

test("locale URL overrides navigator language only on explicit locale paths", () => {
  const { resolvePathLocale, resolveSystemLocale } = loadLocaleResolvers({
    languages: ["en-US"],
    language: "en-US",
  });

  assert.equal(resolvePathLocale("/ko/"), "ko");
  assert.equal(resolvePathLocale("/ko/privacy"), "ko");
  assert.equal(resolvePathLocale("/en/"), "en");
  assert.equal(resolvePathLocale("/en/privacy"), "en");
  assert.equal(resolvePathLocale("/"), "");
  assert.equal(resolvePathLocale("/korean/"), "");
  assert.equal(resolvePathLocale("/enough"), "");
  assert.equal(resolvePathLocale("/" ) || resolveSystemLocale(), "en");
  assert.match(
    appScript,
    /const PATH_LOCALE = resolvePathLocale\(window\.location\.pathname\);[\s\S]*?ACTIVE_LOCALE = PATH_LOCALE \|\| resolveSystemLocale\(\)/u,
  );
  assert.match(
    appScript,
    /PATH_LOCALE \? \[PATH_LOCALE, \.\.\.browserLanguageCandidates\] : browserLanguageCandidates/u,
  );
});

test("notice dates use the browser region and retain a deterministic fallback", () => {
  const { resolveDateTimeLocale } = loadLocaleResolvers();

  const british = resolveDateTimeLocale(["en-GB", "en"], "en");
  assert.match(british, /^en-GB$/iu);
  assert.equal(resolveDateTimeLocale(["not_a_locale"], "ko"), "ko-KR");
  assert.equal(resolveDateTimeLocale([], "en"), "en-US");

  const source = sourceBetween(
    "  function formattedNoticeTime",
    "  function renderNotices",
  );
  const formattedNoticeTime = Function(
    "NOTICE_DATE_TIME_LOCALE",
    `"use strict";\n${source}\nreturn formattedNoticeTime;`,
  )(british);
  const timestamp = "2026-08-05T12:34:00Z";
  assert.equal(
    formattedNoticeTime(timestamp),
    new Intl.DateTimeFormat(british, {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(new Date(timestamp)),
  );
});

test("document locale application translates text and accessibility attributes", () => {
  const source = sourceBetween(
    "  function localizedKeyForRenderedText",
    "  const browserCodecCapabilities",
  );
  const pendingAttributes = new Set(["data-i18n-pending"]);
  const documentElement = {
    lang: "en",
    removeAttribute(name) {
      pendingAttributes.delete(name);
    },
  };
  const text = {
    textContent: "Connect",
    getAttribute: () => "connect",
  };
  const runtimeText = {
    textContent: "Connecting…",
    getAttribute: () => "pairingRememberedHint",
  };
  const customRuntimeText = {
    textContent: "HTTP 503 diagnostic",
    getAttribute: () => "pairingRememberedHint",
  };
  const aria = {
    attributes: new Map([["data-i18n-aria-label", "browserPairing"]]),
    getAttribute(name) {
      return this.attributes.get(name);
    },
    setAttribute(name, value) {
      this.attributes.set(name, value);
    },
  };
  const alt = {
    attributes: new Map([["data-i18n-alt", "projectionFrameAlt"]]),
    getAttribute(name) {
      return this.attributes.get(name);
    },
    setAttribute(name, value) {
      this.attributes.set(name, value);
    },
  };
  const localizedImage = {
    attributes: new Map([
      ["data-locale-src-en", "/media/screen-en.png"],
      ["data-locale-src-ko", "/media/screen-ko.png"],
      ["src", "/media/screen-en.png"],
    ]),
    getAttribute(name) {
      return this.attributes.get(name);
    },
    setAttribute(name, value) {
      this.attributes.set(name, value);
    },
  };
  const nodes = new Map([
    ["[data-i18n]", [text, runtimeText, customRuntimeText]],
    ["[data-i18n-aria-label]", [aria]],
    ["[data-i18n-alt]", [alt]],
    ["[data-locale-src-en][data-locale-src-ko]", [localizedImage]],
  ]);
  const document = {
    documentElement,
    querySelectorAll(selector) {
      return nodes.get(selector) || [];
    },
  };
  const translations = {
    connect: "연결",
    connecting: "연결 중…",
    browserPairing: "브라우저 페어링",
    projectionFrameAlt: "Android Auto 프로젝션 영상",
  };
  const dictionaries = {
    en: {
      connect: "Connect",
      connecting: "Connecting…",
      pairingRememberedHint: "Enter the code once and this browser will be remembered.",
    },
    ko: translations,
  };
  const applyDocumentLocale = Function(
    "ACTIVE_LOCALE",
    "I18N",
    "document",
    "t",
    `"use strict";\n${source}\nreturn applyDocumentLocale;`,
  )("ko", dictionaries, document, key => translations[key] || key);

  applyDocumentLocale("en");

  assert.equal(documentElement.lang, "ko");
  assert.equal(text.textContent, "연결");
  assert.equal(runtimeText.textContent, "연결 중…");
  assert.equal(customRuntimeText.textContent, "HTTP 503 diagnostic");
  assert.equal(aria.attributes.get("aria-label"), "브라우저 페어링");
  assert.equal(alt.attributes.get("alt"), "Android Auto 프로젝션 영상");
  assert.equal(localizedImage.attributes.get("src"), "/media/screen-ko.png");
  assert.equal(pendingAttributes.has("data-i18n-pending"), false);
});

test("locale failure cannot leave the page permanently hidden", () => {
  const source = sourceBetween(
    "  function localizedKeyForRenderedText",
    "  const browserCodecCapabilities",
  );
  let pendingRemoved = false;
  const document = {
    documentElement: {
      lang: "en",
      removeAttribute(name) {
        if (name === "data-i18n-pending") pendingRemoved = true;
      },
    },
    querySelectorAll(selector) {
      if (selector === "[data-i18n]") throw new Error("synthetic translation failure");
      return [];
    },
  };
  const applyDocumentLocale = Function(
    "ACTIVE_LOCALE",
    "I18N",
    "document",
    "t",
    `"use strict";\n${source}\nreturn applyDocumentLocale;`,
  )("ko", { en: {}, ko: {} }, document, key => key);

  assert.throws(applyDocumentLocale, /synthetic translation failure/u);
  assert.equal(pendingRemoved, true);
  assert.match(indexHtml, /<html lang="en" data-i18n-pending>/u);
  assert.match(
    indexHtml,
    /animation: navonweb-i18n-failsafe-reveal 1200ms step-end both;/u,
  );
  assert.match(indexHtml, /@keyframes navonweb-i18n-failsafe-reveal/u);
  assert.match(indexHtml, /from \{ visibility: hidden; \}/u);
  assert.doesNotMatch(
    indexHtml,
    /html\[data-i18n-pending\] body \{\s*visibility: hidden;/u,
  );
});

test("debug console language switch keeps the existing ko and en locale standard", () => {
  const normalizeSource = sourceBetween(
    "  function normalizeDebugLocale",
    "  function changeDebugLanguage",
  );
  const normalizeDebugLocale = Function(
    `"use strict";\n${normalizeSource}\nreturn normalizeDebugLocale;`,
  )();

  assert.equal(normalizeDebugLocale("KO"), "ko");
  assert.equal(normalizeDebugLocale(" en "), "en");
  assert.throws(() => normalizeDebugLocale("kr"), /accepts only/u);
  assert.throws(() => normalizeDebugLocale("ja"), /accepts only/u);
  const switchSource = sourceBetween(
    "  function normalizeDebugLocale",
    "  function loadRememberedCredential",
  );
  const window = {};
  const appliedPreviousLocales = [];
  let noticeCancelCount = 0;
  let noticeLoadCount = 0;
  const runtime = Function(
    "window",
    "resolveNoticeLocaleCandidates",
    "resolveDateTimeLocale",
    "applyDocumentLocale",
    "cancelNoticeRequestForRetry",
    "ensureNoticesLoaded",
    `"use strict";
let ACTIVE_LOCALE = "ko";
let NOTICE_LOCALE_CANDIDATES = Object.freeze(["ko", "en"]);
let NOTICE_DATE_TIME_LOCALE = "ko-KR";
${switchSource}
return {
  getState: () => ({
    activeLocale: ACTIVE_LOCALE,
    noticeCandidates: [...NOTICE_LOCALE_CANDIDATES],
    noticeDateTimeLocale: NOTICE_DATE_TIME_LOCALE,
  }),
};`,
  )(
    window,
    (candidates, activeLocale) => Object.freeze([...candidates, activeLocale]),
    (candidates, activeLocale) => `${candidates[0]}:${activeLocale}`,
    previousLocale => appliedPreviousLocales.push(previousLocale),
    () => { noticeCancelCount += 1; },
    () => {
      noticeLoadCount += 1;
      return Promise.resolve();
    },
  );

  assert.equal(window.dbg.changeLang("en"), "en");
  assert.equal(window.dbg.changeLang("ko"), "ko");
  assert.equal(window.dbg.changeLang("en"), "en");
  assert.deepEqual(runtime.getState(), {
    activeLocale: "en",
    noticeCandidates: ["en", "en"],
    noticeDateTimeLocale: "en:en",
  });
  assert.deepEqual(appliedPreviousLocales, ["ko", "en", "ko"]);
  assert.equal(noticeCancelCount, 3);
  assert.equal(noticeLoadCount, 3);

  const stateBeforeInvalidRequest = runtime.getState();
  assert.throws(() => window.dbg.changeLang("kr"), /accepts only/u);
  assert.deepEqual(runtime.getState(), stateBeforeInvalidRequest);
  assert.equal(noticeCancelCount, 3);
  assert.equal(noticeLoadCount, 3);
  assert.match(appScript, /Object\.defineProperty\(window, 'dbg'/u);
  assert.match(appScript, /changeLang: changeDebugLanguage/u);
  assert.doesNotMatch(appScript, /requested === 'kr'/u);
});

test("notice language refresh invalidates a completed cache without an in-flight request", () => {
  const source = sourceBetween(
    "  function cancelNoticeRequestForRetry",
    "  function boundedNoticeString",
  );
  const clearedTimers = [];
  let aborted = 0;
  const runtime = Function(
    "window",
    "onAbort",
    `"use strict";
let noticeRequestTask = null;
let noticeExpiryTimer = 41;
let noticesLoadedCredential = "remembered";
let noticeNextRequestEpochMillis = 1234;
${source}
return {
  cancelNoticeRequestForRetry,
  setInFlightRequest: () => {
    noticeRequestTask = { controller: { abort: onAbort } };
    noticeExpiryTimer = 42;
  },
  getState: () => ({
    hasRequest: noticeRequestTask !== null,
    expiryTimer: noticeExpiryTimer,
    loadedCredential: noticesLoadedCredential,
    nextRequest: noticeNextRequestEpochMillis,
  }),
};`,
  )(
    { clearTimeout: timer => clearedTimers.push(timer) },
    () => { aborted += 1; },
  );

  runtime.cancelNoticeRequestForRetry();
  assert.deepEqual(runtime.getState(), {
    hasRequest: false,
    expiryTimer: 0,
    loadedCredential: "",
    nextRequest: 0,
  });
  assert.deepEqual(clearedTimers, [41]);

  runtime.setInFlightRequest();
  runtime.cancelNoticeRequestForRetry();
  assert.equal(aborted, 1);
  assert.deepEqual(clearedTimers, [41, 42]);
});

test("PWA metadata is language-neutral instead of falsely declaring English", () => {
  assert.equal(Object.hasOwn(manifest, "lang"), false);
  assert.match(manifest.description, /supported phone projection/u);
  assert.match(manifest.description, /차량용 화면/u);
});

test("local control recovery copy is concise and equivalent in Korean and English", () => {
  const englishStart = appScript.indexOf("    en: Object.freeze({");
  const koreanStart = appScript.indexOf("    ko: Object.freeze({", englishStart);
  const i18nEnd = appScript.indexOf("  let ACTIVE_LOCALE", koreanStart);
  assert.ok(englishStart >= 0 && koreanStart > englishStart && i18nEnd > koreanStart);

  const englishSource = appScript.slice(englishStart, koreanStart);
  const koreanSource = appScript.slice(koreanStart, i18nEnd);
  assert.match(
    englishSource,
    /localControlRecovering: 'Recovering the local control connection\.'/u,
  );
  assert.match(
    koreanSource,
    /localControlRecovering: '로컬 제어 연결을 복구하는 중입니다\.'/u,
  );
  assert.doesNotMatch(
    koreanSource,
    /로컬 연결을 복구하는 중입니다\. 제어 데이터는 클라우드로 전송되지 않았습니다\./u,
  );
});

test("pre-pairing landing content has complete English and Korean translations", () => {
  const englishStart = appScript.indexOf("    en: Object.freeze({");
  const koreanStart = appScript.indexOf("    ko: Object.freeze({", englishStart);
  const i18nEnd = appScript.indexOf("  let ACTIVE_LOCALE", koreanStart);
  assert.ok(englishStart >= 0 && koreanStart > englishStart && i18nEnd > koreanStart);

  const keysIn = source => new Set(
    [...source.matchAll(/^\s+(landing[A-Za-z0-9]+):/gmu)].map(match => match[1]),
  );
  const englishKeys = keysIn(appScript.slice(englishStart, koreanStart));
  const koreanSource = appScript.slice(koreanStart, i18nEnd);
  const koreanKeys = keysIn(koreanSource);
  assert.deepEqual(koreanKeys, englishKeys);

  const markupKeys = new Set(
    [...landingHtml.matchAll(/data-i18n(?:-aria-label|-alt)?="(landing[A-Za-z0-9]+)"/gmu)]
      .map(match => match[1]),
  );
  markupKeys.add("landingPlayStoreComingSoon");
  markupKeys.add("landingPlayStoreCta");
  assert.deepEqual(markupKeys, englishKeys);
  assert.match(koreanSource, /Android Auto 화면을 가까운 브라우저로/u);
  assert.match(koreanSource, /운전 중에는 휴대전화나 차량 화면을 조작하지 마세요/u);
});
