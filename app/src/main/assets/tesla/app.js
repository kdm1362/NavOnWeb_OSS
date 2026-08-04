/* SPDX-License-Identifier: GPL-3.0-or-later */
(() => {
  // The packaged asset must remain false. BrowserProbeServer replaces this exact
  // declaration only while serving a debuggable APK.
  const NAVONWEB_DEVELOPMENT_VIEWPORT_ENABLED = false;
  const CLOUD_RELAY_ROOM_ID_PATTERN = /^[A-Za-z0-9_-]{22}$/;
  const CLOUD_RELAY_CONFIG = resolveCloudRelayConfig();
  const CLOUD_RELAY_MODE = CLOUD_RELAY_CONFIG !== null;
  const STORAGE_KEY = CLOUD_RELAY_MODE
    ? 'navonweb.browserCredential.v2'
    : 'tecom.browserCredential.v1';
  const LEGACY_CLOUD_STORAGE_KEY = CLOUD_RELAY_MODE && CLOUD_RELAY_CONFIG.roomId
    ? `tecom.browserCredential.v1.${CLOUD_RELAY_CONFIG.roomId}`
    : '';
  const PREMIUM_PROMPT_DISMISSED_KEY = 'navonweb.premiumPromptDismissed.v1';
  const FRESH_CLOUD_ROUTE_REQUIRED_KEY = 'navonweb.freshCloudRouteRequired.v1';
  const CREDENTIAL_PATTERN = /^[A-Za-z0-9_-]{43}$/;
  const FRAME_INTERVAL_MILLIS = 200;
  const MAX_FRAME_BYTES = 1024 * 1024;
  const STATUS_HEALTHY_MIN_INTERVAL_MILLIS = 1500;
  const STATUS_HEALTHY_MAX_INTERVAL_MILLIS = 2500;
  const STATUS_FAILURE_BASE_INTERVAL_MILLIS = 2000;
  const STATUS_FAILURE_MAX_INTERVAL_MILLIS = 5000;
  const STATUS_REQUEST_TIMEOUT_MILLIS = 5000;
  const WEBRTC_SESSION_PATTERN = /^[A-Za-z0-9_-]{16,64}$/;
  const WEBRTC_ICE_TIMEOUT_MILLIS = 10000;
  const WEBRTC_ANSWER_TIMEOUT_MILLIS = 15000;
  const WEBRTC_ANSWER_POLL_MILLIS = 200;
  const WEBRTC_CONNECTION_TIMEOUT_MILLIS = 15000;
  const WEBRTC_RECOVERY_BASE_DELAY_MILLIS = 1500;
  const WEBRTC_RECOVERY_MAX_DELAY_MILLIS = 15000;
  const WEBRTC_RECOVERY_CLOSE_GRACE_MILLIS = 250;
  const CODEC_NAMES = ['h264', 'vp8', 'vp9', 'av1'];
  const AUDIO_TRACKS = ['media', 'speech', 'system'];
  const AUDIO_RECOVERY_BASE_DELAY_MILLIS = 1000;
  const AUDIO_RECOVERY_MAX_DELAY_MILLIS = 10000;
  const AUDIO_MAX_SCHEDULE_AHEAD_SECONDS = 0.45;
  const AUDIO_START_AHEAD_SECONDS = 0.04;
  const AUDIO_WEBRTC_CHANNEL_PREFIX = 'navonweb-audio-';
  const AUDIO_WEBRTC_CHANNEL_SUFFIX = '-v1';
  const AUDIO_WEBRTC_HEADER_BYTES = 12;
  const AUDIO_WEBRTC_MAX_PCM_BYTES = 32 * 1024;
  const AUDIO_WEBRTC_OPEN_TIMEOUT_MILLIS = 5000;
  const AUDIO_WEBRTC_RECOVERY_BASE_DELAY_MILLIS = 2000;
  const AUDIO_WEBRTC_RECOVERY_MAX_DELAY_MILLIS = 30000;
  const MICROPHONE_ENDPOINT = '/api/microphone';
  const MICROPHONE_SCRIPT_BUFFER_SIZE = 2048;
  const MICROPHONE_MAX_RAW_BYTES = 32 * 1024;
  const MICROPHONE_MAX_QUEUED_CHUNKS = 8;
  const MICROPHONE_UPLOAD_TIMEOUT_MILLIS = 5000;
  const MICROPHONE_RECOVERY_BASE_DELAY_MILLIS = 1000;
  const MICROPHONE_RECOVERY_MAX_DELAY_MILLIS = 10000;
  const MICROPHONE_READY_HEARTBEAT_INTERVAL_MILLIS = 5000;
  const MICROPHONE_IDLE_HEARTBEAT_BYTES = new Uint8Array([0, 0]);
  const MICROPHONE_FALLBACK_SAMPLE_RATE_HZ = 48000;
  const MICROPHONE_WEBRTC_CHANNEL_LABEL = 'navonweb-microphone-v1';
  const MICROPHONE_WEBRTC_HEADER_BYTES = 12;
  const MICROPHONE_WEBRTC_MAX_BUFFERED_AMOUNT = 64 * 1024;
  const MICROPHONE_WEBRTC_BUFFERED_AMOUNT_LOW_THRESHOLD = 16 * 1024;
  const MICROPHONE_MIN_SAMPLE_RATE_HZ = 8000;
  const MICROPHONE_MAX_SAMPLE_RATE_HZ = 192000;
  const CONTROL_WEBRTC_CHANNEL_LABEL = 'navonweb-control-v1';
  const CONTROL_WEBRTC_MAX_MESSAGE_BYTES = 192 * 1024;
  const CONTROL_WEBRTC_MAX_IN_FLIGHT_REQUESTS = 16;
  const CONTROL_WEBRTC_REQUEST_TIMEOUT_MILLIS = 15000;
  const DEVELOPMENT_VIEWPORT_QUERY = 'navonweb-dev-viewport';
  const DEVELOPMENT_TESLA_DRIVING_MODE = 'tesla-driving';
  const DEVELOPMENT_TESLA_CYCLE_MODE = 'tesla-cycle';
  const DEVELOPMENT_TESLA_WIDTH_SCALE = 0.68;
  const DEVELOPMENT_TESLA_CYCLE_INTERVAL_MILLIS = 12000;
  const VIEWPORT_REPORT_SETTLE_MILLIS = 850;
  const VIEWPORT_REPORT_RETRY_MILLIS = 1500;
  const VIEWPORT_CONTROLLER_BUSY_RETRY_MILLIS = 5000;
  const VIEWPORT_REPORT_TIMEOUT_MILLIS = 5000;
  const MIN_VIEWPORT_DEVICE_PIXEL_RATIO = 0.5;
  const MAX_VIEWPORT_DEVICE_PIXEL_RATIO = 8;
  const PINCH_EXPAND_SCALE = 1.18;
  const PINCH_COLLAPSE_SCALE = 0.82;
  const FULLSCREEN_HINT_DURATION_MILLIS = 5000;
  const PREMIUM_PROMPT_DURATION_MILLIS = 10000;
  const MAX_NOTICE_COUNT = 20;
  const MAX_NOTICE_RESPONSE_BYTES = 64 * 1024;
  const MAX_NOTICE_TITLE_CHARACTERS = 160;
  const MAX_NOTICE_BODY_CHARACTERS = 4000;
  const NOTICE_REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000;
  const NOTICE_RETRY_INTERVAL_MILLIS = 30 * 1000;
  const TOUCH_REQUEST_TIMEOUT_MILLIS = 1500;
  const CLOUD_RELAY_REQUEST_TIMEOUT_MILLIS = 15000;
  const CLOUD_RELAY_CONNECT_TIMEOUT_MILLIS = 10000;
  const REPAIR_PAIRING_DELAY_MILLIS = 10000;
  const CLOUD_RELAY_MAX_BODY_BYTES = 128 * 1024;
  const CLOUD_RELAY_MAX_RESPONSE_BYTES = 160 * 1024;
  const CLOUD_RELAY_REQUEST_ID_PATTERN = /^[A-Za-z0-9_-]{16,64}$/;
  const DYNAMIC_ASPECT_BODY_CLASS = 'navonweb-dynamic-aspect';
  const AUTHENTICATED_BODY_CLASS = 'navonweb-authenticated';
  const DEVELOPMENT_TESLA_BODY_CLASS = 'navonweb-development-tesla-driving';

  function resolveCloudRelayConfig() {
    const raw = window.NAVONWEB_CLOUD_CONFIG;
    if (!raw || typeof raw.signalingWebSocketOrigin !== 'string') return null;
    try {
      const origin = new URL(raw.signalingWebSocketOrigin);
      const pathPrefix = typeof raw.signalingWebSocketPathPrefix === 'string'
        ? raw.signalingWebSocketPathPrefix
        : '';
      const params = new URLSearchParams(location.hash.replace(/^#/, ''));
      const roomId = params.get('device') || '';
      if (origin.protocol !== 'wss:' || origin.username || origin.password ||
          origin.pathname !== '/' || origin.search || origin.hash ||
          (pathPrefix && !/^\/[A-Za-z0-9._~-]+(?:\/[A-Za-z0-9._~-]+)*$/.test(pathPrefix)) ||
          (roomId && !CLOUD_RELAY_ROOM_ID_PATTERN.test(roomId))) return null;
      const secureHttpOrigin = origin.origin.replace(/^wss:/, 'https:');
      return Object.freeze({
        roomId,
        bootstrapPairUrl: `${secureHttpOrigin}${pathPrefix}/bootstrap/pair`,
        webSocketUrl: roomId
          ? `${origin.origin}${pathPrefix}/ws/browser/${roomId}`
          : `${origin.origin}${pathPrefix}/ws/browser`
      });
    } catch (_) {
      return null;
    }
  }
  const I18N = Object.freeze({
    en: Object.freeze({
      browserPairing: 'Browser pairing',
      pairingCodeLabel: '8-digit pairing code shown on your phone',
      connect: 'Connect',
      pairingRememberedHint: 'Enter the code once and this browser will be remembered.',
      androidAutoScreen: 'Android Auto screen',
      projectionInputArea: 'Android Auto video and touch input area',
      projectionFrameAlt: 'Android Auto projection video',
      androidAutoWaiting: 'Waiting for Android Auto',
      fullscreenEnter: 'Fullscreen',
      fullscreenEnterLabel: 'View Android Auto in fullscreen',
      fullscreenExitLabel: 'Exit Android Auto fullscreen',
      normalViewState: 'Showing standard view',
      fullscreenViewState: 'Showing fullscreen',
      theaterViewState: 'Showing theater mode',
      fullscreenHintWindows: 'Press Esc to exit fullscreen.',
      fullscreenHintTouch: 'Pinch out/in to toggle fullscreen.',
      announcements: 'Announcements',
      noticesLoading: 'Loading announcements…',
      noAnnouncements: 'There are no announcements.',
      noticesUnavailable: 'Announcements are temporarily unavailable.',
      noticesStale: 'Showing saved announcements.',
      announcementUntitled: 'Announcement',
      premiumUpgradeMessage: 'Please purchase the Premium tier to use high-resolution (1080p) video and stereo audio.',
      premiumDoNotShowAgain: "Don't show again",
      confirm: 'OK',
      connectionExpiredPhone: 'The browser connection has expired. Enter the new code shown on your phone.',
      connectionExpired: 'The browser connection has expired. Enter a new code.',
      savedConnectionExpired: 'The saved connection has expired. Enter the new code shown on your phone.',
      videoWaiting: 'Waiting for video',
      mediaPermissionPrompt: 'Tap once to enable sound and the browser microphone.',
      mediaPermissionAllow: 'Enable sound and microphone',
      mediaPermissionDenied: 'Microphone access is blocked. Allow it in this site\'s permissions, then retry.',
      localNetworkPrompt: 'Allow local network access to connect directly to your phone.',
      localNetworkDenied: "Local network access is blocked. Allow it in this site's permissions, then retry.",
      localNetworkAllow: 'Allow local network',
      localNetworkRetry: 'Retry',
      androidAutoReconnecting: 'Reconnecting to Android Auto',
      serverWaiting: 'Waiting for server',
      eightDigitRequired: 'Enter an 8-digit number.',
      connecting: 'Connecting…',
      invalidCode: 'The code is not valid.',
      expiredCode: 'The code has expired. Check the app for a new code.',
      retryLater: 'Try again in a moment.',
      unableToConnect: 'Unable to connect. Try again in a moment.',
      repairPairing: 'Get a new pairing code',
      repairPairingLabel: 'Pair with a new code',
      repairPairingHint: 'Check your phone for a new pairing code, then enter it here.'
    }),
    ko: Object.freeze({
      browserPairing: '브라우저 페어링',
      pairingCodeLabel: '휴대전화에 표시된 8자리 페어링 코드',
      connect: '연결',
      pairingRememberedHint: '코드를 입력하면 이 브라우저가 기억됩니다.',
      androidAutoScreen: 'Android Auto 화면',
      projectionInputArea: 'Android Auto 영상 및 터치 입력 영역',
      projectionFrameAlt: 'Android Auto 프로젝션 영상',
      androidAutoWaiting: 'Android Auto 연결 대기',
      fullscreenEnter: '전체 화면',
      fullscreenEnterLabel: 'Android Auto 전체 화면으로 보기',
      fullscreenExitLabel: 'Android Auto 전체 화면 종료',
      normalViewState: '기본 화면으로 표시 중',
      fullscreenViewState: '전체 화면으로 표시 중',
      theaterViewState: '극장 모드로 표시 중',
      fullscreenHintWindows: 'Esc로 전체화면 종료',
      fullscreenHintTouch: '핀치 줌아웃/인으로 전체화면을 전환할 수 있습니다',
      announcements: '공지사항',
      noticesLoading: '공지사항을 불러오는 중…',
      noAnnouncements: '등록된 공지사항이 없습니다.',
      noticesUnavailable: '공지사항을 일시적으로 불러올 수 없습니다.',
      noticesStale: '저장된 공지사항을 표시하고 있습니다.',
      announcementUntitled: '공지사항',
      premiumUpgradeMessage: '고해상도(1080p), 스테레오 음질을 사용하려면 프리미엄 티어를 결제해 주세요',
      premiumDoNotShowAgain: '다시 보지 않기',
      confirm: '확인',
      connectionExpiredPhone: '브라우저 연결이 만료되었습니다. 휴대전화의 새 코드를 입력하세요.',
      connectionExpired: '브라우저 연결이 만료되었습니다. 새 코드를 입력하세요.',
      savedConnectionExpired: '저장된 연결이 만료되었습니다. 휴대전화의 새 코드를 입력하세요.',
      videoWaiting: '영상 연결 대기',
      mediaPermissionPrompt: '소리와 브라우저 마이크를 사용하려면 한 번 눌러 주세요.',
      mediaPermissionAllow: '소리 및 마이크 사용',
      mediaPermissionDenied: '마이크 접근이 차단되었습니다. 이 사이트의 권한에서 허용한 뒤 다시 시도하세요.',
      localNetworkPrompt: '휴대전화에 직접 연결하려면 로컬 네트워크 접근을 허용하세요.',
      localNetworkDenied: '로컬 네트워크 접근이 차단되었습니다. 이 사이트의 권한에서 허용한 뒤 다시 시도하세요.',
      localNetworkAllow: '로컬 네트워크 허용',
      localNetworkRetry: '다시 시도',
      androidAutoReconnecting: 'Android Auto 다시 연결 중',
      serverWaiting: '서버 연결 대기',
      eightDigitRequired: '8자리 숫자를 입력하세요.',
      connecting: '연결 중…',
      invalidCode: '코드가 올바르지 않습니다.',
      expiredCode: '코드가 만료되었습니다. 앱에서 새 코드를 확인하세요.',
      retryLater: '잠시 후 다시 시도하세요.',
      unableToConnect: '연결할 수 없습니다. 잠시 후 다시 시도하세요.',
      repairPairing: '페어링 코드 다시 받기',
      repairPairingLabel: '새 코드로 다시 페어링',
      repairPairingHint: '휴대전화 앱에서 새 페어링 코드를 확인한 뒤 입력하세요.'
    })
  });
  const ACTIVE_LOCALE = resolveSystemLocale();
  const NOTICE_LOCALE_CANDIDATES = resolveNoticeLocaleCandidates();
  const WINDOWS_PLATFORM = browserRunsOnWindows();
  const VIEWPORT_CLIENT_ID = generateViewportClientId();
  const LEGACY_PROJECTION_PROFILE = Object.freeze({
    id: 'legacy-800x480',
    width: 800,
    height: 480,
    androidAutoFramesPerSecond: 60,
    webRtcFramesPerSecond: 30,
    densityDpi: 140,
    sourceAspectWidth: 5,
    sourceAspectHeight: 3
  });
  const LEGACY_PROJECTION_VIEWPORT = Object.freeze({
    encodedWidth: 800,
    encodedHeight: 480,
    totalMarginWidth: 0,
    totalMarginHeight: 0,
    contentLeft: 0,
    contentTop: 0,
    contentWidth: 800,
    contentHeight: 480,
    densityDpi: 140
  });

  const main = document.querySelector('main');
  const pairingPanel = document.querySelector('#pairing-panel');
  const pairingForm = document.querySelector('#pairing-form');
  const code = document.querySelector('#code');
  const pair = document.querySelector('#pair');
  const pairStatus = document.querySelector('#pair-status');
  const viewer = document.querySelector('#viewer');
  const pad = document.querySelector('#pad');
  const projectionContent = document.querySelector('#projection-content');
  const frame = document.querySelector('#frame');
  const webRtcVideo = document.querySelector('#webrtc-video');
  const streamState = document.querySelector('#stream-state');
  const localNetworkPanel = document.querySelector('#local-network-panel');
  const localNetworkMessage = document.querySelector('#local-network-message');
  const localNetworkAllow = document.querySelector('#local-network-allow');
  const mediaPermissionPanel = document.querySelector('#media-permission-panel');
  const mediaPermissionMessage = document.querySelector('#media-permission-message');
  const mediaPermissionAllow = document.querySelector('#media-permission-allow');
  const repairPairingPanel = document.querySelector('#repair-pairing-panel');
  const repairPairingButton = document.querySelector('#repair-pairing');
  const viewerControls = document.querySelector('#viewer-controls');
  const fullscreenButton = document.querySelector('#fullscreen');
  const fullscreenState = document.querySelector('#fullscreen-state');
  const fullscreenHint = document.querySelector('#fullscreen-hint');
  const premiumPrompt = document.querySelector('#premium-prompt');
  const premiumPromptDismiss = document.querySelector('#premium-prompt-dismiss');
  const premiumPromptConfirm = document.querySelector('#premium-prompt-confirm');
  const noticePanel = document.querySelector('#notice-panel');
  const noticeStatus = document.querySelector('#notice-status');
  const noticeList = document.querySelector('#notice-list');

  let cloudRelayTransport = null;
  let browserCredential = '';
  let statusPolling = false;
  let statusTimer = 0;
  let statusGeneration = 0;
  let statusPollTask = null;
  let statusFailureCount = 0;
  let framePolling = false;
  let frameTimer = 0;
  let frameAbortController = null;
  let frameVersion = 0;
  let frameObjectUrl = '';
  let pairingInFlight = false;
  let repairPairingTimer = 0;
  let freshCloudRelayRouteRequired = loadFreshCloudRelayRouteRequirement();
  let activePointerId = null;
  let lastPointerPosition = {x: 0, y: 0};
  let lastMove = 0;
  const touchPointers = new Map();
  let pinchGesture = null;
  let suppressAndroidAutoTouch = false;
  const touchControlQueue = [];
  let pendingMoveTouch = null;
  let touchPumpGeneration = null;
  let touchQueueGeneration = 0;
  let activeTouchAbortController = null;
  let touchRecoveryCancelPending = false;
  let touchRecoveryCancelInFlight = false;
  let activeProjectionProfile = LEGACY_PROJECTION_PROFILE;
  let activeProjectionViewport = LEGACY_PROJECTION_VIEWPORT;
  let projectionProfileRevision = 0;
  let webRtcPeer = null;
  let webRtcControlTransport = null;
  const webRtcAudioChannels = new Map();
  const webRtcAudioOpenTimers = new Map();
  let webRtcSessionId = '';
  let webRtcGeneration = 0;
  let webRtcStarting = false;
  let webRtcCapabilitiesPromise = null;
  let webRtcServerCapabilities = null;
  let webRtcConnectionWaitCancel = null;
  let webRtcRecoveryTimer = 0;
  let webRtcRecoveryAttempts = 0;
  let webRtcRecoveryInFlight = false;
  let webRtcIcePairPublishedGeneration = 0;
  let localNetworkPermissionState = CLOUD_RELAY_MODE ? 'checking' : 'not_applicable';
  let localNetworkPermissionStatus = null;
  let localNetworkPermissionQuery = null;
  let localNetworkPermissionRequestInFlight = false;
  let androidAutoInteractive = false;
  let androidAutoTouchReady = false;
  let pageActive = true;
  let theaterMode = false;
  let expandedViewRequestGeneration = 0;
  let fullscreenEntryPendingGeneration = 0;
  let expandedViewWasActive = false;
  let fullscreenHintTimer = 0;
  let premiumPromptOfferedForSession = false;
  let premiumPromptTimer = 0;
  let noticeRequestTask = null;
  let noticesLoadedCredential = '';
  let noticeNextRequestEpochMillis = 0;
  let noticeExpiryTimer = 0;
  let audioContext = null;
  let audioUnlocked = false;
  let outputAudioWebRtcRecoveryRequired = false;
  let outputAudioWebRtcUnsupported = false;
  let outputAudioWebRtcRecoveryTimer = 0;
  let outputAudioWebRtcRecoveryAttempts = 0;
  let audioGeneration = 0;
  let audioRecoveryTimer = 0;
  let audioRecoveryAttempts = 0;
  const audioStreams = new Map();
  let microphoneState = 'idle';
  let microphoneGeneration = 0;
  let microphonePermissionStatus = null;
  let microphoneMediaStream = null;
  let microphoneTrack = null;
  let microphoneAudioContext = null;
  let microphoneSourceNode = null;
  let microphoneProcessorNode = null;
  let microphoneSilenceNode = null;
  let microphoneAbortController = null;
  let microphoneUploadActiveGeneration = 0;
  let microphoneRecoveryTimer = 0;
  let microphoneRecoveryAttempts = 0;
  let microphoneCaptureRequested = false;
  let microphonePermissionPrimed = false;
  let microphoneInputSampleRateHz = MICROPHONE_FALLBACK_SAMPLE_RATE_HZ;
  let microphoneReadyHeartbeatTimer = 0;
  let microphoneWebRtcChannel = null;
  let microphoneWebRtcChannelGeneration = 0;
  let microphoneWebRtcPendingFrame = null;
  const microphoneQueue = [];
  let viewportLayoutFrame = 0;
  let viewportResizeObserver = null;
  let activeViewportValue = null;
  let developmentViewportMode = '';
  let developmentTeslaDriving = false;
  let developmentTeslaCycleTimer = 0;
  let viewportReportTimer = 0;
  let viewportReportAbortController = null;
  let pendingViewportReport = null;
  let lastViewportReportKey = '';

  function resolveSystemLocale() {
    const candidates = [];
    if (Array.isArray(navigator.languages)) candidates.push(...navigator.languages);
    if (typeof navigator.language === 'string') candidates.push(navigator.language);
    for (const candidate of candidates) {
      const base = String(candidate || '').trim().toLowerCase().split(/[-_]/, 1)[0];
      if (base === 'ko' || base === 'en') return base;
    }
    return 'en';
  }

  function resolveNoticeLocaleCandidates() {
    const result = [];
    const add = value => {
      const normalized = String(value || '').trim().replace(/_/g, '-').toLowerCase();
      if (!/^[a-z]{2,8}(?:-[a-z0-9]{1,8})*$/.test(normalized)) return;
      if (!result.includes(normalized)) result.push(normalized);
      const primary = normalized.split('-', 1)[0];
      if (!result.includes(primary)) result.push(primary);
    };
    if (Array.isArray(navigator.languages)) navigator.languages.forEach(add);
    add(navigator.language);
    add(ACTIVE_LOCALE);
    add('en');
    add('ko');
    return Object.freeze(result);
  }

  function browserRunsOnWindows() {
    const candidates = [];
    if (navigator.userAgentData && typeof navigator.userAgentData.platform === 'string') {
      candidates.push(navigator.userAgentData.platform);
    }
    if (typeof navigator.platform === 'string') candidates.push(navigator.platform);
    if (typeof navigator.userAgent === 'string') candidates.push(navigator.userAgent);
    return candidates.some(candidate => /windows|win32|win64|wince|wow64/i.test(candidate));
  }

  function t(key) {
    const selected = I18N[ACTIVE_LOCALE] || I18N.en;
    return selected[key] || I18N.en[key] || key;
  }

  function applyDocumentLocale() {
    document.documentElement.lang = ACTIVE_LOCALE;
    document.querySelectorAll('[data-i18n]').forEach(element => {
      element.textContent = t(element.getAttribute('data-i18n'));
    });
    document.querySelectorAll('[data-i18n-aria-label]').forEach(element => {
      element.setAttribute('aria-label', t(element.getAttribute('data-i18n-aria-label')));
    });
    document.querySelectorAll('[data-i18n-alt]').forEach(element => {
      element.setAttribute('alt', t(element.getAttribute('data-i18n-alt')));
    });
  }

  const browserCodecCapabilities = (() => {
    const supported = new Set();
    if (typeof RTCRtpReceiver === 'undefined' || !RTCRtpReceiver.getCapabilities) return supported;
    const capabilities = RTCRtpReceiver.getCapabilities('video');
    for (const entry of (capabilities && capabilities.codecs) || []) {
      const name = String(entry.mimeType || '').split('/').pop().toLowerCase();
      if (CODEC_NAMES.includes(name)) supported.add(name);
    }
    return supported;
  })();

  function loadRememberedCredential() {
    try {
      const saved = window.localStorage.getItem(STORAGE_KEY) ||
        (LEGACY_CLOUD_STORAGE_KEY && window.localStorage.getItem(LEGACY_CLOUD_STORAGE_KEY)) || '';
      return CREDENTIAL_PATTERN.test(saved) ? saved : '';
    } catch (_) {
      return '';
    }
  }

  function rememberCredential(value) {
    try {
      window.localStorage.setItem(STORAGE_KEY, value);
      if (LEGACY_CLOUD_STORAGE_KEY) window.localStorage.removeItem(LEGACY_CLOUD_STORAGE_KEY);
    } catch (_) {
      // 저장소가 차단된 브라우저는 현재 탭에서만 연결을 유지합니다.
    }
  }

  function forgetCredential() {
    try {
      window.localStorage.removeItem(STORAGE_KEY);
      if (LEGACY_CLOUD_STORAGE_KEY) window.localStorage.removeItem(LEGACY_CLOUD_STORAGE_KEY);
    } catch (_) {
      // 저장소 접근 실패와 관계없이 현재 연결은 폐기합니다.
    }
  }

  function premiumPromptWasDismissed() {
    try {
      return window.localStorage.getItem(PREMIUM_PROMPT_DISMISSED_KEY) === 'true';
    } catch (_) {
      return false;
    }
  }

  function rememberPremiumPromptDismissal() {
    try {
      window.localStorage.setItem(PREMIUM_PROMPT_DISMISSED_KEY, 'true');
    } catch (_) {
      // A blocked store limits the dismissal to this authenticated page session.
    }
  }

  function hidePremiumPrompt() {
    if (premiumPromptTimer) window.clearTimeout(premiumPromptTimer);
    premiumPromptTimer = 0;
    premiumPrompt.hidden = true;
    premiumPromptDismiss.checked = false;
  }

  function resetPremiumPromptSession() {
    premiumPromptOfferedForSession = false;
    hidePremiumPrompt();
  }

  function maybeShowPremiumPrompt(wasInteractive, projection) {
    if (wasInteractive || !androidAutoInteractive || premiumPromptOfferedForSession ||
        !projection || projection.entitlement !== 'free' || premiumPromptWasDismissed()) return;
    premiumPromptOfferedForSession = true;
    hidePremiumPrompt();
    premiumPrompt.hidden = false;
    premiumPromptTimer = window.setTimeout(() => {
      premiumPromptTimer = 0;
      premiumPrompt.hidden = true;
      premiumPromptDismiss.checked = false;
    }, PREMIUM_PROMPT_DURATION_MILLIS);
  }

  function resetNoticeSession() {
    if (noticeRequestTask) noticeRequestTask.controller.abort();
    if (noticeExpiryTimer) window.clearTimeout(noticeExpiryTimer);
    noticeExpiryTimer = 0;
    noticeRequestTask = null;
    noticesLoadedCredential = '';
    noticeNextRequestEpochMillis = 0;
    noticePanel.hidden = true;
    noticePanel.open = false;
    noticeList.replaceChildren();
    noticeStatus.hidden = false;
    noticeStatus.textContent = t('noticesLoading');
  }

  function cancelNoticeRequestForRetry() {
    if (!noticeRequestTask) return;
    noticeRequestTask.controller.abort();
    noticeRequestTask = null;
    if (noticesLoadedCredential === browserCredential) noticesLoadedCredential = '';
    noticeNextRequestEpochMillis = 0;
  }

  function boundedNoticeString(value, maximumCharacters) {
    if (typeof value !== 'string') return '';
    return value.trim().slice(0, maximumCharacters);
  }

  function localizedNoticeText(entry, field, maximumCharacters) {
    const nested = entry && typeof entry[field] === 'object' && entry[field] !== null
      ? entry[field]
      : null;
    const selectedSuffix = ACTIVE_LOCALE === 'ko' ? 'Ko' : 'En';
    const fallbackSuffix = ACTIVE_LOCALE === 'ko' ? 'En' : 'Ko';
    const selectedSnake = ACTIVE_LOCALE === 'ko' ? '_ko' : '_en';
    const fallbackSnake = ACTIVE_LOCALE === 'ko' ? '_en' : '_ko';
    const normalizedNested = new Map();
    if (nested) {
      Object.entries(nested).slice(0, 10).forEach(([language, value]) => {
        const normalizedLanguage = String(language).trim().replace(/_/g, '-').toLowerCase();
        if (/^[a-z]{2,8}(?:-[a-z0-9]{1,8})*$/.test(normalizedLanguage) &&
            !normalizedNested.has(normalizedLanguage)) {
          normalizedNested.set(normalizedLanguage, value);
        }
      });
    }
    const localizedCandidates = NOTICE_LOCALE_CANDIDATES
      .map(language => normalizedNested.get(language));
    const candidates = [
      ...localizedCandidates,
      entry && entry[`${field}${selectedSuffix}`],
      entry && entry[`${field}${selectedSnake}`],
      normalizedNested.get('en'),
      normalizedNested.get('ko'),
      entry && entry[`${field}${fallbackSuffix}`],
      entry && entry[`${field}${fallbackSnake}`],
      ...normalizedNested.values(),
      entry && entry[field]
    ];
    for (const candidate of candidates) {
      const normalized = boundedNoticeString(candidate, maximumCharacters);
      if (normalized) return normalized;
    }
    return '';
  }

  function normalizeNotice(entry) {
    if (!entry || typeof entry !== 'object') return null;
    const title = localizedNoticeText(entry, 'title', MAX_NOTICE_TITLE_CHARACTERS);
    const body = localizedNoticeText(entry, 'body', MAX_NOTICE_BODY_CHARACTERS);
    if (!title && !body) return null;
    const endsAt = boundedNoticeString(entry.endsAt, 64);
    const endsAtEpochMillis = endsAt ? Date.parse(endsAt) : null;
    if (endsAt && (!Number.isFinite(endsAtEpochMillis) || endsAtEpochMillis <= Date.now())) {
      return null;
    }
    return {
      title: title || t('announcementUntitled'),
      body,
      publishedAt: boundedNoticeString(entry.publishedAt, 64),
      endsAtEpochMillis
    };
  }

  function normalizeNoticePayload(payload) {
    if (!payload || !Array.isArray(payload.notices)) {
      return {notices: [], stale: false, available: false};
    }
    return {
      notices: payload.notices.slice(0, MAX_NOTICE_COUNT).map(normalizeNotice).filter(Boolean),
      stale: payload.stale === true,
      available: payload.available !== false
    };
  }

  function formattedNoticeTime(value) {
    if (!value) return '';
    const timestamp = Date.parse(value);
    if (!Number.isFinite(timestamp)) return '';
    try {
      return new Intl.DateTimeFormat(ACTIVE_LOCALE === 'ko' ? 'ko-KR' : 'en-US', {
        dateStyle: 'medium',
        timeStyle: 'short'
      }).format(new Date(timestamp));
    } catch (_) {
      return '';
    }
  }

  function renderNotices(payload) {
    if (noticeExpiryTimer) window.clearTimeout(noticeExpiryTimer);
    noticeExpiryTimer = 0;
    const normalized = normalizeNoticePayload(payload);
    noticeList.replaceChildren();
    normalized.notices.forEach(notice => {
      const item = document.createElement('article');
      item.className = 'notice-item';

      const title = document.createElement('h3');
      title.className = 'notice-title';
      title.textContent = notice.title;
      item.appendChild(title);

      const displayedTime = formattedNoticeTime(notice.publishedAt);
      if (displayedTime) {
        const time = document.createElement('time');
        time.className = 'notice-time';
        time.dateTime = notice.publishedAt;
        time.textContent = displayedTime;
        item.appendChild(time);
      }

      if (notice.body) {
        const body = document.createElement('p');
        body.className = 'notice-body';
        body.textContent = notice.body;
        item.appendChild(body);
      }
      noticeList.appendChild(item);
    });
    const empty = normalized.notices.length === 0;
    noticeStatus.hidden = normalized.available && !empty && !normalized.stale;
    noticeStatus.textContent = !normalized.available
      ? t('noticesUnavailable')
      : empty ? t('noAnnouncements')
        : normalized.stale ? t('noticesStale') : '';
    noticePanel.hidden = !browserCredential;
    const expiryTimes = normalized.notices
      .map(notice => notice.endsAtEpochMillis)
      .filter(Number.isFinite);
    if (expiryTimes.length > 0) {
      const delay = Math.min(
        Math.max(1, Math.min(...expiryTimes) - Date.now() + 25),
        2_147_483_647
      );
      noticeExpiryTimer = window.setTimeout(() => {
        noticeExpiryTimer = 0;
        renderNotices(payload);
      }, delay);
    }
  }

  async function readBoundedNoticePayload(response) {
    const declaredLength = Number.parseInt(response.headers.get('content-length') || '', 10);
    if (Number.isFinite(declaredLength) && declaredLength > MAX_NOTICE_RESPONSE_BYTES) {
      throw new Error('notice response too large');
    }
    if (!response.body || typeof response.body.getReader !== 'function') {
      const fallback = await response.arrayBuffer();
      if (fallback.byteLength > MAX_NOTICE_RESPONSE_BYTES) throw new Error('notice response too large');
      return JSON.parse(new TextDecoder('utf-8').decode(fallback));
    }
    const reader = response.body.getReader();
    const chunks = [];
    let totalBytes = 0;
    try {
      while (true) {
        const next = await reader.read();
        if (next.done) break;
        const chunk = next.value || new Uint8Array(0);
        totalBytes += chunk.byteLength;
        if (totalBytes > MAX_NOTICE_RESPONSE_BYTES) {
          await reader.cancel().catch(() => null);
          throw new Error('notice response too large');
        }
        chunks.push(chunk);
      }
    } finally {
      reader.releaseLock();
    }
    const bytes = new Uint8Array(totalBytes);
    let offset = 0;
    chunks.forEach(chunk => {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    });
    return JSON.parse(new TextDecoder('utf-8').decode(bytes));
  }

  function ensureNoticesLoaded() {
    const credential = browserCredential;
    const now = Date.now();
    if (noticeRequestTask && noticeRequestTask.credential === credential) {
      return noticeRequestTask.promise;
    }
    if (!credential ||
        (noticesLoadedCredential === credential && now < noticeNextRequestEpochMillis)) {
      return Promise.resolve();
    }
    if (noticeRequestTask) noticeRequestTask.controller.abort();
    noticePanel.hidden = false;
    noticeStatus.hidden = false;
    noticeStatus.textContent = t('noticesLoading');
    noticeList.replaceChildren();
    const task = {
      credential,
      controller: new AbortController(),
      promise: null
    };
    noticeRequestTask = task;
    task.promise = api('/api/notices', {signal: task.controller.signal}, credential)
      .then(response => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return readBoundedNoticePayload(response);
      })
      .then(payload => {
        if (noticeRequestTask !== task || browserCredential !== credential) return;
        noticesLoadedCredential = credential;
        noticeNextRequestEpochMillis = Date.now() +
          (payload && payload.available !== false
            ? NOTICE_REFRESH_INTERVAL_MILLIS
            : NOTICE_RETRY_INTERVAL_MILLIS);
        renderNotices(payload);
      })
      .catch(error => {
        if (error.name === 'AbortError' || noticeRequestTask !== task || browserCredential !== credential) return;
        noticesLoadedCredential = credential;
        noticeNextRequestEpochMillis = Date.now() + NOTICE_RETRY_INTERVAL_MILLIS;
        if (noticeExpiryTimer) window.clearTimeout(noticeExpiryTimer);
        noticeExpiryTimer = 0;
        noticeList.replaceChildren();
        noticeStatus.hidden = false;
        noticeStatus.textContent = t('noticesUnavailable');
        noticePanel.hidden = false;
      })
      .finally(() => {
        if (noticeRequestTask === task) noticeRequestTask = null;
      });
    return task.promise;
  }

  function setPairStatus(message, error = false) {
    pairStatus.textContent = message;
    pairStatus.style.color = error ? '#fda4af' : '';
  }

  function loadFreshCloudRelayRouteRequirement() {
    if (!CLOUD_RELAY_MODE || CLOUD_RELAY_CONFIG.roomId) return false;
    try {
      return window.sessionStorage.getItem(FRESH_CLOUD_ROUTE_REQUIRED_KEY) === '1';
    } catch (_) {
      return false;
    }
  }

  function setFreshCloudRelayRouteRequirement(required) {
    freshCloudRelayRouteRequired = Boolean(
      required && CLOUD_RELAY_MODE && !CLOUD_RELAY_CONFIG.roomId
    );
    try {
      if (freshCloudRelayRouteRequired) {
        window.sessionStorage.setItem(FRESH_CLOUD_ROUTE_REQUIRED_KEY, '1');
      } else {
        window.sessionStorage.removeItem(FRESH_CLOUD_ROUTE_REQUIRED_KEY);
      }
    } catch (_) {
      // Session storage is optional. The in-memory gate still protects this page lifetime.
    }
  }

  function cancelRepairPairingCountdown() {
    if (repairPairingTimer) window.clearTimeout(repairPairingTimer);
    repairPairingTimer = 0;
    repairPairingPanel.hidden = true;
  }

  function scheduleRepairPairingAction() {
    if (!browserCredential || !pageActive || document.hidden ||
        repairPairingTimer || !repairPairingPanel.hidden) return;
    const credential = browserCredential;
    repairPairingTimer = window.setTimeout(() => {
      repairPairingTimer = 0;
      if (!pageActive || document.hidden || browserCredential !== credential) return;
      repairPairingPanel.hidden = false;
    }, REPAIR_PAIRING_DELAY_MILLIS);
  }

  function generateViewportClientId() {
    try {
      const bytes = new Uint8Array(16);
      window.crypto.getRandomValues(bytes);
      return Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
    } catch (_) {
      const fallback = `page${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`;
      return fallback.replace(/[^A-Za-z0-9_-]/g, '').slice(0, 64).padEnd(16, '0');
    }
  }

  function finiteCssPixels(value) {
    const parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function browserDevicePixelRatio() {
    const value = Number(window.devicePixelRatio);
    if (!Number.isFinite(value) || value < MIN_VIEWPORT_DEVICE_PIXEL_RATIO ||
        value > MAX_VIEWPORT_DEVICE_PIXEL_RATIO) return 1;
    return Math.round(value * 1000) / 1000;
  }

  function layoutViewportDimensions() {
    const visual = window.visualViewport;
    const visualScale = visual && Number.isFinite(visual.scale) ? visual.scale : 1;
    if (visual && Math.abs(visualScale - 1) <= 0.01 &&
        Number.isFinite(visual.width) && Number.isFinite(visual.height) &&
        visual.width > 0 && visual.height > 0) {
      return {
        width: visual.width,
        height: visual.height,
        scale: visualScale,
        source: 'visualViewport'
      };
    }
    return {
      width: Math.max(document.documentElement.clientWidth, window.innerWidth || 0),
      height: Math.max(document.documentElement.clientHeight, window.innerHeight || 0),
      scale: visualScale,
      source: 'layoutViewport'
    };
  }

  function measureProjectionViewport() {
    if (viewer.hidden) return null;
    const viewport = layoutViewportDimensions();
    if (!viewerOwnsFullscreen() && !theaterMode) {
      const padRect = pad.getBoundingClientRect();
      if (padRect.width <= 0 || padRect.height <= 0) return null;
      const roundedWidth = Math.round(padRect.width * 100) / 100;
      const roundedHeight = Math.round(padRect.height * 100) / 100;
      return Object.freeze({
        width: roundedWidth,
        height: roundedHeight,
        aspectRatio: roundedWidth / roundedHeight,
        devicePixelRatio: browserDevicePixelRatio(),
        viewportScale: viewport.scale,
        source: 'projectionSurface'
      });
    }
    const viewerRect = viewer.getBoundingClientRect();
    if (viewerRect.width <= 0 || viewport.height <= 0) return null;
    const viewerStyle = window.getComputedStyle(viewer);
    const mainStyle = window.getComputedStyle(main);
    const controlsRect = viewerControls.getBoundingClientRect();
    const horizontalPadding = finiteCssPixels(viewerStyle.paddingLeft) +
      finiteCssPixels(viewerStyle.paddingRight);
    const mainVerticalPadding = viewerOwnsFullscreen() || theaterMode ? 0 :
      finiteCssPixels(mainStyle.paddingTop) + finiteCssPixels(mainStyle.paddingBottom);
    const verticalPadding = finiteCssPixels(viewerStyle.paddingTop) +
      finiteCssPixels(viewerStyle.paddingBottom) + mainVerticalPadding;
    const gap = finiteCssPixels(viewerStyle.rowGap || viewerStyle.gap);
    const width = Math.min(viewport.width, viewerRect.width) - horizontalPadding;
    const height = viewport.height - verticalPadding - controlsRect.height - gap;
    if (width <= 0 || height <= 0) return null;
    const roundedWidth = Math.round(width * 100) / 100;
    const roundedHeight = Math.round(height * 100) / 100;
    return Object.freeze({
      width: roundedWidth,
      height: roundedHeight,
      aspectRatio: roundedWidth / roundedHeight,
      devicePixelRatio: browserDevicePixelRatio(),
      viewportScale: viewport.scale,
      source: viewport.source
    });
  }

  function viewportValueChanged(previous, next) {
    if (!previous || !next) return previous !== next;
    return Math.abs(previous.width - next.width) > 0.5 ||
      Math.abs(previous.height - next.height) > 0.5 ||
      Math.abs(previous.viewportScale - next.viewportScale) > 0.01;
  }

  function setDevelopmentViewportDiagnostic(name, value) {
    if (!NAVONWEB_DEVELOPMENT_VIEWPORT_ENABLED) return;
    const attribute = `data-navonweb-${name}`;
    if (value === null || typeof value === 'undefined' || value === '') {
      document.documentElement.removeAttribute(attribute);
    } else {
      document.documentElement.setAttribute(attribute, String(value));
    }
  }

  function scheduleViewportReport(value) {
    const blockedReason = !value ? 'no-viewport' :
      !browserCredential ? 'no-browser-credential' :
      !pageActive ? 'page-inactive' :
      document.hidden ? 'document-hidden' :
      !document.body.classList.contains(DYNAMIC_ASPECT_BODY_CLASS) ? 'not-entitled' :
      Math.abs(value.viewportScale - 1) > 0.01 ? 'viewport-scaled' : '';
    if (blockedReason) {
      if (viewportReportTimer) window.clearTimeout(viewportReportTimer);
      viewportReportTimer = 0;
      pendingViewportReport = null;
      if (viewportReportAbortController) viewportReportAbortController.abort();
      viewportReportAbortController = null;
      setDevelopmentViewportDiagnostic('viewport-report', `blocked:${blockedReason}`);
      return;
    }
    // This request is same-origin to the phone-hosted server. navigator.onLine can be false in
    // vehicle browsers without public Internet even though the local NavOnWeb server is healthy.
    const key = viewportReportKey(value);
    if (viewportReportTimer && pendingViewportReport &&
        viewportReportKey(pendingViewportReport) === key) {
      setDevelopmentViewportDiagnostic('viewport-report', `scheduled:${key}`);
      return;
    }
    pendingViewportReport = value;
    setDevelopmentViewportDiagnostic('viewport-report', `scheduled:${key}`);
    if (viewportReportTimer) window.clearTimeout(viewportReportTimer);
    viewportReportTimer = window.setTimeout(() => {
      viewportReportTimer = 0;
      reportProjectionViewport();
    }, VIEWPORT_REPORT_SETTLE_MILLIS);
  }

  function viewportReportKey(value) {
    return `${Math.max(1, Math.round(value.width))}x${Math.max(1, Math.round(value.height))}`;
  }

  function requestViewportControlReclaim() {
    if (!activeViewportValue ||
        !document.body.classList.contains(DYNAMIC_ASPECT_BODY_CLASS)) return;
    lastViewportReportKey = '';
    scheduleViewportReport(activeViewportValue);
  }

  async function reportProjectionViewport() {
    const value = pendingViewportReport;
    pendingViewportReport = null;
    if (!value || !browserCredential || !pageActive || document.hidden ||
        !document.body.classList.contains(DYNAMIC_ASPECT_BODY_CLASS) ||
        Math.abs(value.viewportScale - 1) > 0.01) {
      setDevelopmentViewportDiagnostic('viewport-report', 'blocked:stale');
      return;
    }
    const key = viewportReportKey(value);
    if (key === lastViewportReportKey) return;
    if (viewportReportAbortController) viewportReportAbortController.abort();
    const controller = new AbortController();
    viewportReportAbortController = controller;
    const timeout = window.setTimeout(() => controller.abort(), VIEWPORT_REPORT_TIMEOUT_MILLIS);
    try {
      setDevelopmentViewportDiagnostic('viewport-report', `sending:${key}`);
      const query = new URLSearchParams({
        width: String(Math.max(1, Math.round(value.width))),
        height: String(Math.max(1, Math.round(value.height))),
        devicePixelRatio: String(value.devicePixelRatio)
      });
      const response = await api(
        `/api/projection/viewport?${query}`,
        {method: 'POST', signal: controller.signal}
      );
      if (response.status === 401) {
        invalidateCredential(t('connectionExpiredPhone'));
        return;
      }
      if (response.status === 200 || response.status === 202) {
        lastViewportReportKey = key;
        setDevelopmentViewportDiagnostic('viewport-report', `accepted:${response.status}:${key}`);
        return;
      }
      if (response.status === 409 || response.status >= 500) {
        const conflict = response.status === 409 ? await response.json().catch(() => ({})) : {};
        const controllerBusy = conflict.error === 'viewport_controller_busy';
        const retryDelay = controllerBusy ?
          VIEWPORT_CONTROLLER_BUSY_RETRY_MILLIS : VIEWPORT_REPORT_RETRY_MILLIS;
        setDevelopmentViewportDiagnostic(
          'viewport-report',
          `${controllerBusy ? 'busy' : 'retry'}:${response.status}:${key}`
        );
        pendingViewportReport = value;
        viewportReportTimer = window.setTimeout(() => {
          viewportReportTimer = 0;
          reportProjectionViewport();
        }, retryDelay);
      }
      if (response.status !== 409 && response.status < 500) {
        setDevelopmentViewportDiagnostic('viewport-report', `rejected:${response.status}:${key}`);
      }
    } catch (error) {
      if (error.name !== 'AbortError') {
        setDevelopmentViewportDiagnostic('viewport-report', `retry:network:${key}`);
        pendingViewportReport = value;
        viewportReportTimer = window.setTimeout(() => {
          viewportReportTimer = 0;
          reportProjectionViewport();
        }, VIEWPORT_REPORT_RETRY_MILLIS);
      } else {
        setDevelopmentViewportDiagnostic('viewport-report', `aborted:${key}`);
      }
    } finally {
      window.clearTimeout(timeout);
      if (viewportReportAbortController === controller) viewportReportAbortController = null;
    }
  }

  function stopViewportReporting() {
    if (viewportReportTimer) window.clearTimeout(viewportReportTimer);
    viewportReportTimer = 0;
    pendingViewportReport = null;
    lastViewportReportKey = '';
    if (viewportReportAbortController) viewportReportAbortController.abort();
    viewportReportAbortController = null;
  }

  function refreshDevelopmentViewportWidth() {
    if (!developmentTeslaDriving) {
      document.documentElement.style.removeProperty('--navonweb-development-viewport-width');
      return;
    }
    const viewport = layoutViewportDimensions();
    const fullContentWidth = viewport.width;
    const simulatedWidth = Math.max(320, Math.round(fullContentWidth * DEVELOPMENT_TESLA_WIDTH_SCALE));
    document.documentElement.style.setProperty(
      '--navonweb-development-viewport-width',
      `${simulatedWidth}px`
    );
  }

  function scheduleViewportLayoutSync() {
    if (viewportLayoutFrame) return;
    viewportLayoutFrame = window.requestAnimationFrame(() => {
      viewportLayoutFrame = 0;
      refreshDevelopmentViewportWidth();
      const next = measureProjectionViewport();
      const changed = viewportValueChanged(activeViewportValue, next);
      if (changed && activePointerId !== null) {
        cancelActivePointer();
      }
      activeViewportValue = next;
      if (next) {
        setDevelopmentViewportDiagnostic(
          'viewport-measure',
          `${Math.round(next.width)}x${Math.round(next.height)}`
        );
        document.documentElement.style.setProperty(
          '--navonweb-available-aspect-ratio',
          `${next.width} / ${next.height}`
        );
        if (document.body.classList.contains(DYNAMIC_ASPECT_BODY_CLASS) &&
            (changed || !lastViewportReportKey)) {
          scheduleViewportReport(next);
        }
      }
      syncProjectionContentLayout();
    });
  }

  function applyDynamicAspectEntitlement(projection) {
    // Dynamic viewport geometry is supported by every encoded profile, including FREE 800x480.
    // Entitlement still protects selection of the 720p/1080p profiles on the phone.
    const enabled = Boolean(projection && projection.activeProfile);
    if (!projection || projection.entitlement !== 'free') hidePremiumPrompt();
    const changed = document.body.classList.contains(DYNAMIC_ASPECT_BODY_CLASS) !== enabled;
    document.body.classList.toggle(DYNAMIC_ASPECT_BODY_CLASS, enabled);
    if (!enabled) stopViewportReporting();
    if (changed) scheduleViewportLayoutSync();
  }

  function applyDevelopmentTeslaDriving(enabled) {
    developmentTeslaDriving = Boolean(enabled);
    document.body.classList.toggle(DEVELOPMENT_TESLA_BODY_CLASS, developmentTeslaDriving);
    refreshDevelopmentViewportWidth();
    scheduleViewportLayoutSync();
    return developmentViewportSnapshot();
  }

  function stopDevelopmentTeslaCycle() {
    if (developmentTeslaCycleTimer) window.clearInterval(developmentTeslaCycleTimer);
    developmentTeslaCycleTimer = 0;
  }

  function startDevelopmentTeslaCycle() {
    if (developmentViewportMode !== DEVELOPMENT_TESLA_CYCLE_MODE || developmentTeslaCycleTimer) return;
    developmentTeslaCycleTimer = window.setInterval(() => {
      applyDevelopmentTeslaDriving(!developmentTeslaDriving);
    }, DEVELOPMENT_TESLA_CYCLE_INTERVAL_MILLIS);
  }

  function developmentViewportSnapshot() {
    const viewport = activeViewportValue ? Object.freeze({...activeViewportValue}) : null;
    const padRect = pad.getBoundingClientRect();
    const contentRect = projectionContent.getBoundingClientRect();
    const simulatedWidth = finiteCssPixels(
      document.documentElement.style.getPropertyValue('--navonweb-development-viewport-width')
    );
    return Object.freeze({
      mode: developmentViewportMode,
      teslaDriving: developmentTeslaDriving,
      widthScale: DEVELOPMENT_TESLA_WIDTH_SCALE,
      simulatedWidthCssPixels: developmentTeslaDriving && simulatedWidth > 0 ? simulatedWidth : null,
      viewport,
      padRect: Object.freeze({
        left: padRect.left,
        top: padRect.top,
        width: padRect.width,
        height: padRect.height
      }),
      contentRect: Object.freeze({
        left: contentRect.left,
        top: contentRect.top,
        width: contentRect.width,
        height: contentRect.height
      }),
      activeProjectionViewport
    });
  }

  function setDevelopmentTeslaDriving(enabled) {
    stopDevelopmentTeslaCycle();
    developmentViewportMode = 'manual';
    return applyDevelopmentTeslaDriving(enabled);
  }

  function installDevelopmentViewport() {
    if (!NAVONWEB_DEVELOPMENT_VIEWPORT_ENABLED) return;
    let requestedMode = '';
    try {
      requestedMode = new URLSearchParams(window.location.search).get(DEVELOPMENT_VIEWPORT_QUERY) || '';
    } catch (_) {
      return;
    }
    if (requestedMode !== DEVELOPMENT_TESLA_DRIVING_MODE &&
        requestedMode !== DEVELOPMENT_TESLA_CYCLE_MODE) return;
    developmentViewportMode = requestedMode;
    applyDevelopmentTeslaDriving(true);
    if (requestedMode === DEVELOPMENT_TESLA_CYCLE_MODE) startDevelopmentTeslaCycle();
    window.__navOnWebDevelopmentViewport = Object.freeze({
      snapshot: developmentViewportSnapshot,
      setTeslaDriving: setDevelopmentTeslaDriving
    });
  }

  function installViewportObservers() {
    if (typeof ResizeObserver === 'function') {
      viewportResizeObserver = new ResizeObserver(() => scheduleViewportLayoutSync());
      viewportResizeObserver.observe(main);
      viewportResizeObserver.observe(viewerControls);
    }
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', scheduleViewportLayoutSync);
      window.visualViewport.addEventListener('scroll', scheduleViewportLayoutSync);
    }
    scheduleViewportLayoutSync();
  }

  function normalizeLocalNetworkPermissionState(value) {
    const normalized = String(value || '').toLowerCase();
    if (normalized === 'granted' || normalized === 'allowed') return 'granted';
    if (normalized === 'prompt' || normalized === 'denied') return normalized;
    if (normalized === 'checking' || normalized === 'not_applicable') return normalized;
    return 'unsupported';
  }

  function localNetworkPermissionAllowsWebRtc(_userInitiated) {
    if (!CLOUD_RELAY_MODE) return true;
    // A prompt is not a denial. Starting ICE lets the browser surface its native
    // one-time permission when required; blocking here would require our button
    // again after every page or service resume and prevent automatic recovery.
    return localNetworkPermissionState === 'granted' ||
      localNetworkPermissionState === 'unsupported' ||
      localNetworkPermissionState === 'prompt';
  }

  function syncLocalNetworkPermissionPanel() {
    if (!localNetworkPanel || !localNetworkMessage || !localNetworkAllow) return;
    const actionable = CLOUD_RELAY_MODE && Boolean(browserCredential) && androidAutoInteractive &&
      localNetworkPermissionState === 'denied';
    localNetworkPanel.hidden = !actionable;
    if (!actionable) {
      syncMediaPermissionPanel();
      return;
    }
    const denied = localNetworkPermissionState === 'denied';
    localNetworkMessage.textContent = t(denied ? 'localNetworkDenied' : 'localNetworkPrompt');
    localNetworkAllow.textContent = t(denied ? 'localNetworkRetry' : 'localNetworkAllow');
    localNetworkAllow.disabled = localNetworkPermissionRequestInFlight;
    syncMediaPermissionPanel();
  }

  function syncMediaPermissionPanel() {
    if (!mediaPermissionPanel || !mediaPermissionMessage || !mediaPermissionAllow) return;
    const microphoneDenied = microphoneState === 'permission_denied';
    const localNetworkPromptVisible = localNetworkPanel && !localNetworkPanel.hidden;
    const actionable = Boolean(browserCredential) && androidAutoInteractive &&
      (!audioUnlocked || microphoneDenied) &&
      !localNetworkPromptVisible;
    mediaPermissionPanel.hidden = !actionable;
    if (!actionable) return;
    const messageKey = microphoneDenied ? 'mediaPermissionDenied' : 'mediaPermissionPrompt';
    mediaPermissionMessage.textContent = t(messageKey);
    mediaPermissionAllow.textContent = t('mediaPermissionAllow');
  }

  function setLocalNetworkPermissionState(value) {
    localNetworkPermissionState = normalizeLocalNetworkPermissionState(value);
    pad.dataset.navonwebLocalNetworkPermission = localNetworkPermissionState;
    syncLocalNetworkPermissionPanel();
  }

  function handleLocalNetworkPermissionChange() {
    const nextState = normalizeLocalNetworkPermissionState(
      localNetworkPermissionStatus && localNetworkPermissionStatus.state
    );
    setLocalNetworkPermissionState(nextState);
    if (!localNetworkPermissionAllowsWebRtc(false)) {
      cancelWebRtcRecovery();
      if (webRtcPeer || webRtcStarting) resetWebRtc(true);
      return;
    }
    if (browserCredential && androidAutoInteractive && !document.hidden) {
      cancelWebRtcRecovery(true);
      scheduleWebRtcRecovery();
    }
  }

  function refreshLocalNetworkPermission() {
    if (!CLOUD_RELAY_MODE) {
      setLocalNetworkPermissionState('not_applicable');
      return Promise.resolve(localNetworkPermissionState);
    }
    if (localNetworkPermissionQuery) return localNetworkPermissionQuery;
    localNetworkPermissionQuery = (async () => {
      if (!window.isSecureContext || !navigator.permissions || !navigator.permissions.query) {
        setLocalNetworkPermissionState('unsupported');
        return localNetworkPermissionState;
      }
      let status = null;
      try {
        status = await navigator.permissions.query({name: 'local-network'});
      } catch (_) {
        try {
          status = await navigator.permissions.query({name: 'local-network-access'});
        } catch (_) {
          setLocalNetworkPermissionState('unsupported');
          return localNetworkPermissionState;
        }
      }
      if (localNetworkPermissionStatus && localNetworkPermissionStatus.removeEventListener) {
        localNetworkPermissionStatus.removeEventListener('change', handleLocalNetworkPermissionChange);
      } else if (localNetworkPermissionStatus &&
          localNetworkPermissionStatus.onchange === handleLocalNetworkPermissionChange) {
        localNetworkPermissionStatus.onchange = null;
      }
      localNetworkPermissionStatus = status;
      if (status && status.addEventListener) {
        status.addEventListener('change', handleLocalNetworkPermissionChange);
      } else if (status && 'onchange' in status) {
        status.onchange = handleLocalNetworkPermissionChange;
      }
      setLocalNetworkPermissionState(status && status.state);
      return localNetworkPermissionState;
    })().finally(() => {
      localNetworkPermissionQuery = null;
    });
    return localNetworkPermissionQuery;
  }

  async function requestLocalNetworkAccess() {
    if (!CLOUD_RELAY_MODE || localNetworkPermissionRequestInFlight) return;
    localNetworkPermissionRequestInFlight = true;
    syncLocalNetworkPermissionPanel();
    try {
      await refreshLocalNetworkPermission();
      if (localNetworkPermissionState === 'denied') return;
      cancelWebRtcRecovery(true);
      await startWebRtc(true);
      await refreshLocalNetworkPermission();
    } finally {
      localNetworkPermissionRequestInFlight = false;
      syncLocalNetworkPermissionPanel();
    }
  }

  function showAuthenticatedView(authenticated) {
    pairingPanel.hidden = authenticated;
    viewer.hidden = !authenticated;
    document.body.classList.toggle(AUTHENTICATED_BODY_CLASS, authenticated);
    if (authenticated) {
      scheduleViewportLayoutSync();
      if (noticesLoadedCredential === browserCredential) noticePanel.hidden = false;
    } else {
      cancelRepairPairingCountdown();
      noticePanel.hidden = true;
      hidePremiumPrompt();
    }
    syncLocalNetworkPermissionPanel();
  }

  function projectionSourceAspectRatio() {
    return activeProjectionViewport.contentWidth / activeProjectionViewport.contentHeight;
  }

  function normalizeProjectionProfile(value) {
    if (!value || typeof value !== 'object') return null;
    const id = typeof value.id === 'string' ? value.id.trim() : '';
    const integers = [
      value.width,
      value.height,
      value.androidAutoFramesPerSecond,
      value.webRtcFramesPerSecond,
      value.densityDpi,
      value.sourceAspectWidth,
      value.sourceAspectHeight
    ];
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(id) ||
        !integers.every(Number.isInteger) ||
        value.width < 1 || value.width > 8192 ||
        value.height < 1 || value.height > 8192 ||
        value.androidAutoFramesPerSecond < 1 || value.androidAutoFramesPerSecond > 240 ||
        value.webRtcFramesPerSecond < 1 || value.webRtcFramesPerSecond > 240 ||
        value.densityDpi < 72 || value.densityDpi > 140 ||
        value.sourceAspectWidth < 1 || value.sourceAspectWidth > 8192 ||
        value.sourceAspectHeight < 1 || value.sourceAspectHeight > 8192) return null;
    return {
      id,
      width: value.width,
      height: value.height,
      androidAutoFramesPerSecond: value.androidAutoFramesPerSecond,
      webRtcFramesPerSecond: value.webRtcFramesPerSecond,
      densityDpi: value.densityDpi,
      sourceAspectWidth: value.sourceAspectWidth,
      sourceAspectHeight: value.sourceAspectHeight
    };
  }

  function sameProjectionProfile(left, right) {
    return left.id === right.id && left.width === right.width && left.height === right.height &&
      left.androidAutoFramesPerSecond === right.androidAutoFramesPerSecond &&
      left.webRtcFramesPerSecond === right.webRtcFramesPerSecond &&
      left.densityDpi === right.densityDpi &&
      left.sourceAspectWidth === right.sourceAspectWidth &&
      left.sourceAspectHeight === right.sourceAspectHeight;
  }

  function normalizeProjectionViewport(value) {
    if (!value || typeof value !== 'object') return null;
    const fields = [
      value.encodedWidth,
      value.encodedHeight,
      value.totalMarginWidth,
      value.totalMarginHeight,
      value.contentLeft,
      value.contentTop,
      value.contentWidth,
      value.contentHeight,
      value.densityDpi
    ];
    if (!fields.every(Number.isInteger) ||
        value.encodedWidth < 1 || value.encodedWidth > 8192 ||
        value.encodedHeight < 1 || value.encodedHeight > 8192 ||
        value.totalMarginWidth < 0 || value.totalMarginHeight < 0 ||
        value.totalMarginWidth % 2 !== 0 || value.totalMarginHeight % 2 !== 0 ||
        value.contentLeft !== value.totalMarginWidth / 2 ||
        value.contentTop !== value.totalMarginHeight / 2 ||
        value.contentWidth !== value.encodedWidth - value.totalMarginWidth ||
        value.contentHeight !== value.encodedHeight - value.totalMarginHeight ||
        value.contentWidth < 1 || value.contentHeight < 1 ||
        value.densityDpi < 72 || value.densityDpi > 140) return null;
    return Object.freeze({
      encodedWidth: value.encodedWidth,
      encodedHeight: value.encodedHeight,
      totalMarginWidth: value.totalMarginWidth,
      totalMarginHeight: value.totalMarginHeight,
      contentLeft: value.contentLeft,
      contentTop: value.contentTop,
      contentWidth: value.contentWidth,
      contentHeight: value.contentHeight,
      densityDpi: value.densityDpi
    });
  }

  function sameProjectionViewport(left, right) {
    return left.encodedWidth === right.encodedWidth &&
      left.encodedHeight === right.encodedHeight &&
      left.totalMarginWidth === right.totalMarginWidth &&
      left.totalMarginHeight === right.totalMarginHeight &&
      left.densityDpi === right.densityDpi;
  }

  function zeroProjectionViewport(profile = activeProjectionProfile) {
    return Object.freeze({
      encodedWidth: profile.width,
      encodedHeight: profile.height,
      totalMarginWidth: 0,
      totalMarginHeight: 0,
      contentLeft: 0,
      contentTop: 0,
      contentWidth: profile.width,
      contentHeight: profile.height,
      densityDpi: profile.densityDpi
    });
  }

  function syncProjectionMediaCrop() {
    const value = activeProjectionViewport;
    const mediaWidthPercent = value.encodedWidth / value.contentWidth * 100;
    const mediaHeightPercent = value.encodedHeight / value.contentHeight * 100;
    const mediaLeftPercent = -value.contentLeft / value.contentWidth * 100;
    const mediaTopPercent = -value.contentTop / value.contentHeight * 100;
    for (const media of [frame, webRtcVideo]) {
      media.style.left = `${mediaLeftPercent}%`;
      media.style.top = `${mediaTopPercent}%`;
      media.style.width = `${mediaWidthPercent}%`;
      media.style.height = `${mediaHeightPercent}%`;
    }
  }

  function applyProjectionViewportGeometry(value) {
    const next = normalizeProjectionViewport(value);
    if (!next || next.encodedWidth !== activeProjectionProfile.width ||
        next.encodedHeight !== activeProjectionProfile.height ||
        sameProjectionViewport(next, activeProjectionViewport)) return false;
    activeProjectionViewport = next;
    setDevelopmentViewportDiagnostic(
      'viewport-active',
      `${next.contentWidth}x${next.contentHeight}+${next.contentLeft}+${next.contentTop}` +
        `@${next.densityDpi}dpi`
    );
    pad.style.setProperty(
      '--projection-aspect-ratio',
      `${activeProjectionProfile.sourceAspectWidth} / ${activeProjectionProfile.sourceAspectHeight}`
    );
    cancelPointerInteraction();
    syncProjectionMediaCrop();
    scheduleViewportLayoutSync();
    return true;
  }

  function syncProjectionContentLayout() {
    const containerWidth = pad.clientWidth;
    const containerHeight = pad.clientHeight;
    if (containerWidth <= 0 || containerHeight <= 0) return;
    const aspectRatio = projectionSourceAspectRatio();
    let width = containerWidth;
    let height = width / aspectRatio;
    if (height > containerHeight) {
      height = containerHeight;
      width = height * aspectRatio;
    }
    projectionContent.style.width = `${width}px`;
    projectionContent.style.height = `${height}px`;
  }

  function applyProjectionGeometry(value) {
    const next = normalizeProjectionProfile(value) || LEGACY_PROJECTION_PROFILE;
    if (sameProjectionProfile(next, activeProjectionProfile)) return false;
    activeProjectionProfile = next;
    activeProjectionViewport = zeroProjectionViewport(next);
    projectionProfileRevision += 1;
    pad.style.setProperty(
      '--projection-aspect-ratio',
      `${next.sourceAspectWidth} / ${next.sourceAspectHeight}`
    );
    cancelPointerInteraction();
    clearFrame();
    webRtcCapabilitiesPromise = null;
    webRtcServerCapabilities = null;
    if (webRtcPeer || webRtcStarting) resetWebRtc(true);
    syncProjectionMediaCrop();
    lastViewportReportKey = '';
    scheduleViewportLayoutSync();
    return true;
  }

  function clearFrame() {
    if (frameObjectUrl) URL.revokeObjectURL(frameObjectUrl);
    frameObjectUrl = '';
    frameVersion = 0;
    frame.removeAttribute('src');
    pad.classList.remove('frame-ready');
    if (!pad.classList.contains('webrtc-ready')) streamState.textContent = t('androidAutoWaiting');
  }

  function stopFramePolling(clear = false) {
    framePolling = false;
    if (frameTimer) clearTimeout(frameTimer);
    frameTimer = 0;
    if (frameAbortController) frameAbortController.abort();
    frameAbortController = null;
    if (clear) clearFrame();
  }

  function startFramePolling() {
    // The cloud relay intentionally refuses JPEG frames. Cloud mode is WebRTC-only.
    if (CLOUD_RELAY_MODE || framePolling || !browserCredential || document.hidden ||
        pad.classList.contains('webrtc-ready')) return;
    framePolling = true;
    pollFrame();
  }

  function scheduleFramePoll(startedAt, retryDelay = 0) {
    if (!framePolling || !browserCredential) return;
    const elapsed = performance.now() - startedAt;
    const delayMillis = Math.max(retryDelay, FRAME_INTERVAL_MILLIS - elapsed, 0);
    frameTimer = setTimeout(pollFrame, delayMillis);
  }

  async function pollFrame() {
    if (!framePolling || !browserCredential) return;
    const startedAt = performance.now();
    const requestedCredential = browserCredential;
    const controller = new AbortController();
    frameAbortController = controller;
    let retryDelay = 0;
    try {
      const response = await api(
        `/api/frame.jpg?after=${encodeURIComponent(String(frameVersion))}`,
        {headers: {'Accept': 'image/jpeg'}, signal: controller.signal},
        requestedCredential
      );
      if (browserCredential !== requestedCredential || !framePolling) return;
      if (response.status === 401) {
        invalidateCredential(t('connectionExpired'));
        return;
      }
      if (response.status === 204) return;
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const contentType = (response.headers.get('Content-Type') || '').split(';', 1)[0].toLowerCase();
      const nextVersion = Number(response.headers.get('X-Frame-Version'));
      if (contentType !== 'image/jpeg' || !Number.isSafeInteger(nextVersion) || nextVersion <= 0) {
        throw new Error('invalid frame response');
      }
      const blob = await response.blob();
      if (blob.size <= 0 || blob.size > MAX_FRAME_BYTES) throw new Error('invalid frame size');
      if (browserCredential !== requestedCredential || !framePolling) return;
      const nextObjectUrl = URL.createObjectURL(blob);
      const previousObjectUrl = frameObjectUrl;
      frameObjectUrl = nextObjectUrl;
      frameVersion = nextVersion;
      frame.onload = () => {
        if (frameObjectUrl === nextObjectUrl) {
          pad.classList.add('frame-ready');
          streamState.textContent = '';
        }
      };
      frame.src = nextObjectUrl;
      if (previousObjectUrl) URL.revokeObjectURL(previousObjectUrl);
    } catch (error) {
      if (error.name !== 'AbortError' && browserCredential === requestedCredential) {
        if (!pad.classList.contains('frame-ready')) streamState.textContent = t('videoWaiting');
        retryDelay = 1000;
      }
    } finally {
      if (frameAbortController === controller) frameAbortController = null;
      scheduleFramePoll(startedAt, retryDelay);
    }
  }

  function viewerOwnsFullscreen() {
    return document.fullscreenElement === viewer;
  }

  function expandedViewActive() {
    return viewerOwnsFullscreen() || theaterMode;
  }

  function hideFullscreenHint() {
    if (fullscreenHintTimer) window.clearTimeout(fullscreenHintTimer);
    fullscreenHintTimer = 0;
    fullscreenHint.hidden = true;
    fullscreenHint.textContent = '';
  }

  function showFullscreenHint() {
    hideFullscreenHint();
    fullscreenHint.textContent = t(WINDOWS_PLATFORM ? 'fullscreenHintWindows' : 'fullscreenHintTouch');
    fullscreenHint.hidden = false;
    fullscreenHintTimer = window.setTimeout(() => {
      fullscreenHintTimer = 0;
      fullscreenHint.hidden = true;
      fullscreenHint.textContent = '';
    }, FULLSCREEN_HINT_DURATION_MILLIS);
  }

  function syncFullscreenState() {
    const nativeFullscreen = viewerOwnsFullscreen();
    const expanded = nativeFullscreen || theaterMode;
    fullscreenButton.hidden = expanded;
    viewerControls.hidden = expanded;
    fullscreenButton.setAttribute('aria-pressed', String(expanded));
    fullscreenButton.setAttribute(
      'aria-label',
      expanded ? t('fullscreenExitLabel') : t('fullscreenEnterLabel')
    );
    fullscreenButton.textContent = t('fullscreenEnter');
    fullscreenState.textContent = nativeFullscreen
      ? t('fullscreenViewState')
      : theaterMode ? t('theaterViewState') : t('normalViewState');
    if (expanded && !expandedViewWasActive) showFullscreenHint();
    if (!expanded) hideFullscreenHint();
    expandedViewWasActive = expanded;
    scheduleViewportLayoutSync();
  }

  function setTheaterMode(enabled) {
    theaterMode = Boolean(enabled);
    document.body.classList.toggle('theater-mode', theaterMode);
    syncFullscreenState();
  }

  async function setExpandedView(enabled) {
    if (fullscreenEntryPendingGeneration !== 0) return;
    const generation = ++expandedViewRequestGeneration;
    cancelActivePointer();
    if (!enabled) {
      if (theaterMode) setTheaterMode(false);
      if (viewerOwnsFullscreen()) await document.exitFullscreen().catch(() => null);
      if (generation === expandedViewRequestGeneration) syncFullscreenState();
      return;
    }
    if (expandedViewActive()) return;
    if (typeof viewer.requestFullscreen === 'function' && document.fullscreenEnabled !== false) {
      fullscreenEntryPendingGeneration = generation;
      try {
        await viewer.requestFullscreen();
        if (generation !== expandedViewRequestGeneration) {
          if (viewerOwnsFullscreen()) await document.exitFullscreen().catch(() => null);
          return;
        }
        if (viewerOwnsFullscreen()) {
          syncFullscreenState();
          return;
        }
      } catch (_) {
        // 일부 차량 브라우저는 API를 노출하면서도 실제 전환을 거부합니다.
      } finally {
        if (generation === expandedViewRequestGeneration) fullscreenEntryPendingGeneration = 0;
      }
    }
    if (generation === expandedViewRequestGeneration && !viewerOwnsFullscreen()) {
      setTheaterMode(true);
    }
  }

  async function toggleFullscreen() {
    await setExpandedView(!expandedViewActive());
  }

  function cloudRelayReconnectDelayMillis(attempt, randomSample = Math.random()) {
    const maximumDelay = Math.min(1000 * (2 ** Math.min(Math.max(0, attempt), 4)), 15000);
    const boundedSample = Number.isFinite(randomSample)
      ? Math.min(1, Math.max(0, randomSample))
      : 0;
    return Math.max(1, Math.round(maximumDelay * (0.5 + boundedSample * 0.5)));
  }

  class CloudRelayTransport {
    constructor(config) {
      this.config = config;
      this.socket = null;
      this.connectPromise = null;
      this.pending = new Map();
      this.reconnectAttempt = 0;
      this.nextConnectAt = 0;
    }

    async request(path, options) {
      const signal = options.signal;
      if (signal && signal.aborted) throw abortError();
      await this.connect(signal);
      if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
        throw new Error('Cloud relay is not connected');
      }

      const method = String(options.method || 'GET').toUpperCase();
      const headers = relayHeaders(options.headers);
      const body = relayRequestBody(options.body);
      if (body.byteLength > CLOUD_RELAY_MAX_BODY_BYTES) {
        throw new Error('Cloud relay request body is too large');
      }
      const requestId = relayRequestId();
      const envelope = JSON.stringify({
        type: 'rpc_request',
        requestId,
        method,
        target: path,
        headers,
        bodyBase64: base64NoWrap(body)
      });

      return new Promise((resolve, reject) => {
        let timeout = 0;
        const finish = (action, value) => {
          const pending = this.pending.get(requestId);
          if (!pending) return;
          this.pending.delete(requestId);
          window.clearTimeout(timeout);
          if (signal) signal.removeEventListener('abort', onAbort);
          action(value);
        };
        const onAbort = () => finish(reject, abortError());
        timeout = window.setTimeout(
          () => finish(reject, new Error('Cloud relay request timed out')),
          CLOUD_RELAY_REQUEST_TIMEOUT_MILLIS
        );
        this.pending.set(requestId, {
          resolve: response => finish(resolve, response),
          reject: error => finish(reject, error)
        });
        if (signal) signal.addEventListener('abort', onAbort, {once: true});
        try {
          this.socket.send(envelope);
        } catch (error) {
          finish(reject, error);
        }
      });
    }

    async connect(signal) {
      if (this.socket && this.socket.readyState === WebSocket.OPEN) return Promise.resolve();
      const waitMillis = Math.max(0, this.nextConnectAt - Date.now());
      if (waitMillis > 0) {
        await raceAbort(new Promise(resolve => window.setTimeout(resolve, waitMillis)), signal);
      }
      if (!this.connectPromise) this.connectPromise = this.openSocket();
      return raceAbort(this.connectPromise, signal);
    }

    openSocket() {
      return new Promise((resolve, reject) => {
        const socket = new WebSocket(this.config.webSocketUrl);
        this.socket = socket;
        let settled = false;
        const timeout = window.setTimeout(() => {
          if (settled) return;
          settled = true;
          socket.close(1000, 'connect_timeout');
          this.clearSocket(socket, new Error('Cloud relay connection timed out'));
          reject(new Error('Cloud relay connection timed out'));
        }, CLOUD_RELAY_CONNECT_TIMEOUT_MILLIS);
        const settle = action => {
          if (settled) return;
          settled = true;
          window.clearTimeout(timeout);
          action();
        };
        socket.addEventListener('open', () => settle(() => {
          this.reconnectAttempt = 0;
          this.nextConnectAt = 0;
          resolve();
        }));
        socket.addEventListener('message', event => this.receive(socket, event.data));
        socket.addEventListener('error', () => {
          const error = new Error('Cloud relay connection failed');
          this.clearSocket(socket, error);
          settle(() => reject(error));
        });
        socket.addEventListener('close', event => {
          const error = new Error(`Cloud relay closed (${event.code})`);
          settle(() => reject(error));
          this.clearSocket(socket, error);
        });
      }).finally(() => {
        this.connectPromise = null;
      });
    }

    receive(socket, text) {
      if (socket !== this.socket || typeof text !== 'string') return;
      let envelope;
      try {
        envelope = JSON.parse(text);
      } catch (_) {
        return;
      }
      if (!envelope || envelope.type !== 'rpc_response' ||
          !CLOUD_RELAY_REQUEST_ID_PATTERN.test(String(envelope.requestId || ''))) return;
      const pending = this.pending.get(envelope.requestId);
      if (!pending) return;
      try {
        const status = Number(envelope.status);
        const contentType = String(envelope.contentType || 'application/octet-stream');
        const bytes = decodeBase64(String(envelope.bodyBase64 || ''));
        if (!Number.isInteger(status) || status < 100 || status > 599 ||
            contentType.length > 160 || bytes.byteLength > CLOUD_RELAY_MAX_RESPONSE_BYTES) {
          throw new Error('Invalid cloud relay response');
        }
        const body = status === 204 || status === 205 || status === 304 ? null : bytes;
        pending.resolve(new Response(body, {
          status,
          headers: {'Content-Type': contentType, 'Cache-Control': 'no-store'}
        }));
      } catch (error) {
        pending.reject(error);
      }
    }

    clearSocket(socket, error) {
      if (socket !== this.socket) return;
      this.socket = null;
      // Equal jitter prevents a relay outage from synchronizing every active browser onto the
      // same 1/2/4/8/15-second reconnect boundaries while retaining a bounded recovery delay.
      const delayMillis = cloudRelayReconnectDelayMillis(this.reconnectAttempt);
      this.reconnectAttempt += 1;
      this.nextConnectAt = Date.now() + delayMillis;
      for (const pending of this.pending.values()) pending.reject(error);
    }

    close() {
      const socket = this.socket;
      this.socket = null;
      if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, 'page_hidden');
      const error = new Error('Cloud relay closed');
      for (const pending of this.pending.values()) pending.reject(error);
    }
  }

  class WebRtcControlTransport {
    constructor(channel, generation) {
      this.channel = channel;
      this.generation = generation;
      this.pending = new Map();
      this.closed = false;
      channel.addEventListener('open', () => {
        if (this.isCurrent()) pad.dataset.navonwebControlTransport = 'direct';
      });
      channel.addEventListener('message', event => this.receive(event.data));
      channel.addEventListener('error', () => this.fail(new Error('WebRTC control channel failed')));
      channel.addEventListener('close', () => this.fail(new Error('WebRTC control channel closed')));
    }

    isCurrent() {
      return !this.closed && this.generation === webRtcGeneration &&
        webRtcControlTransport === this;
    }

    isOpen() {
      return this.isCurrent() && this.channel.readyState === 'open';
    }

    request(path, options) {
      if (!this.isOpen()) return null;
      if (this.pending.size >= CONTROL_WEBRTC_MAX_IN_FLIGHT_REQUESTS) {
        // No bytes were handed to send(), so the caller may safely use cloud relay.
        return null;
      }
      const signal = options.signal;
      if (signal && signal.aborted) return Promise.reject(abortError());
      const body = relayRequestBody(options.body);
      if (body.byteLength > CLOUD_RELAY_MAX_BODY_BYTES) {
        return Promise.reject(new Error('WebRTC control request body is too large'));
      }
      const requestId = relayRequestId();
      const envelope = JSON.stringify({
        type: 'rpc_request',
        requestId,
        method: String(options.method || 'GET').toUpperCase(),
        target: path,
        headers: relayHeaders(options.headers),
        bodyBase64: base64NoWrap(body)
      });
      if (new TextEncoder().encode(envelope).byteLength > CONTROL_WEBRTC_MAX_MESSAGE_BYTES) {
        return Promise.reject(new Error('WebRTC control request is too large'));
      }
      if (this.channel.bufferedAmount > CONTROL_WEBRTC_MAX_MESSAGE_BYTES) {
        // No bytes were handed to send(), so the caller may safely use cloud relay.
        return null;
      }

      return new Promise((resolve, reject) => {
        let timeout = 0;
        const finish = (action, value) => {
          if (!this.pending.has(requestId)) return;
          this.pending.delete(requestId);
          window.clearTimeout(timeout);
          if (signal) signal.removeEventListener('abort', onAbort);
          action(value);
        };
        const onAbort = () => finish(reject, abortError());
        timeout = window.setTimeout(
          () => finish(reject, new Error('WebRTC control request timed out')),
          CONTROL_WEBRTC_REQUEST_TIMEOUT_MILLIS
        );
        this.pending.set(requestId, {
          resolve: response => finish(resolve, response),
          reject: error => finish(reject, error)
        });
        if (signal) signal.addEventListener('abort', onAbort, {once: true});
        try {
          this.channel.send(envelope);
        } catch (error) {
          finish(reject, error);
        }
      });
    }

    receive(data) {
      if (!this.isCurrent() || typeof data !== 'string' ||
          new TextEncoder().encode(data).byteLength > CONTROL_WEBRTC_MAX_MESSAGE_BYTES) return;
      let envelope;
      try {
        envelope = JSON.parse(data);
      } catch (_) {
        return;
      }
      if (!envelope || envelope.type !== 'rpc_response' ||
          !CLOUD_RELAY_REQUEST_ID_PATTERN.test(String(envelope.requestId || ''))) return;
      const pending = this.pending.get(envelope.requestId);
      if (!pending) return;
      try {
        const status = Number(envelope.status);
        const contentType = String(envelope.contentType || 'application/octet-stream');
        const bytes = decodeBase64(String(envelope.bodyBase64 || ''));
        if (!Number.isInteger(status) || status < 100 || status > 599 ||
            contentType.length > 160 || bytes.byteLength > CLOUD_RELAY_MAX_RESPONSE_BYTES) {
          throw new Error('Invalid WebRTC control response');
        }
        const body = status === 204 || status === 205 || status === 304 ? null : bytes;
        pending.resolve(new Response(body, {
          status,
          headers: {'Content-Type': contentType, 'Cache-Control': 'no-store'}
        }));
      } catch (error) {
        pending.reject(error);
      }
    }

    fail(error) {
      if (this.closed) return;
      this.closed = true;
      if (webRtcControlTransport === this) {
        webRtcControlTransport = null;
        pad.dataset.navonwebControlTransport = 'cloud_fallback';
      }
      for (const pending of this.pending.values()) pending.reject(error);
    }

    close() {
      if (this.closed) return;
      try { this.channel.close(); } catch (_) { /* already closed */ }
      this.fail(new Error('WebRTC control channel closed'));
    }
  }

  function relayHeaders(value) {
    const allowed = new Set([
      'accept',
      'content-type',
      'x-browser-credential',
      'x-pairing-code',
      'x-viewport-client-id'
    ]);
    const result = {};
    const headers = new Headers(value || {});
    headers.forEach((headerValue, rawName) => {
      const name = rawName.toLowerCase();
      if (allowed.has(name)) result[name] = headerValue;
    });
    return result;
  }

  function relayRequestBody(value) {
    if (value === undefined || value === null) return new Uint8Array(0);
    if (typeof value === 'string') return new TextEncoder().encode(value);
    if (value instanceof ArrayBuffer) return new Uint8Array(value);
    if (ArrayBuffer.isView(value)) {
      return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    }
    throw new TypeError('Unsupported cloud relay request body');
  }

  function relayRequestId() {
    const bytes = new Uint8Array(18);
    crypto.getRandomValues(bytes);
    return base64NoWrap(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  function decodeBase64(value) {
    if (!value) return new Uint8Array(0);
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) {
      bytes[index] = binary.charCodeAt(index);
    }
    return bytes;
  }

  function abortError() {
    return new DOMException('The operation was aborted', 'AbortError');
  }

  function raceAbort(promise, signal) {
    if (!signal) return promise;
    if (signal.aborted) return Promise.reject(abortError());
    return new Promise((resolve, reject) => {
      const onAbort = () => reject(abortError());
      signal.addEventListener('abort', onAbort, {once: true});
      promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', onAbort));
    });
  }

  function isDirectWebRtcControlRequest(path, options) {
    const method = String(options.method || 'GET').toUpperCase();
    const pathname = String(path || '').split('?')[0];
    return (method === 'GET' && (
      pathname === '/api/status' ||
      pathname === '/api/notices' ||
      pathname === '/api/projection/profile' ||
      pathname === '/api/projection/viewport'
    )) || (method === 'POST' && (
      pathname === '/api/projection/viewport' ||
      pathname === '/api/touch'
    ));
  }

  async function api(path, options = {}, credential = browserCredential) {
    const headers = Object.assign({
      'X-Browser-Credential': credential,
      'X-Viewport-Client-Id': VIEWPORT_CLIENT_ID
    }, options.headers || {});
    const requestOptions = Object.assign({}, options, {headers, cache: 'no-store'});
    if (CLOUD_RELAY_MODE) {
      const directTransport = webRtcControlTransport;
      if (directTransport && isDirectWebRtcControlRequest(path, requestOptions)) {
        const directRequest = directTransport.request(path, requestOptions);
        if (directRequest) return directRequest;
      }
      if (!cloudRelayTransport) cloudRelayTransport = new CloudRelayTransport(CLOUD_RELAY_CONFIG);
      return cloudRelayTransport.request(path, requestOptions);
    }
    return fetch(path, requestOptions);
  }

  function updateMicrophoneState(nextState) {
    microphoneState = nextState;
    pad.dataset.navonwebMicrophoneState = nextState;
    syncMediaPermissionPanel();
  }

  function stopMicrophoneCapture(reason = 'stopped') {
    cancelMicrophoneRecovery();
    microphoneGeneration += 1;
    updateMicrophoneState(reason);

    const retainPermissionObserver = reason === 'permission_denied' || reason === 'ready' ||
      reason === 'visibility_hidden' || reason === 'offline' ||
      reason === 'android_auto_unavailable';
    if (microphonePermissionStatus && !retainPermissionObserver) {
      microphonePermissionStatus.onchange = null;
    }
    if (!retainPermissionObserver) microphonePermissionStatus = null;
    if (reason === 'permission_denied' || reason === 'pagehide') {
      microphonePermissionPrimed = false;
    }

    if (microphoneAbortController) microphoneAbortController.abort();
    microphoneAbortController = null;
    microphoneUploadActiveGeneration = 0;
    microphoneQueue.length = 0;
    if (microphoneReadyHeartbeatTimer) window.clearTimeout(microphoneReadyHeartbeatTimer);
    microphoneReadyHeartbeatTimer = 0;

    if (microphoneProcessorNode) {
      microphoneProcessorNode.onaudioprocess = null;
      try { microphoneProcessorNode.disconnect(); } catch (_) { /* already disconnected */ }
    }
    if (microphoneSourceNode) {
      try { microphoneSourceNode.disconnect(); } catch (_) { /* already disconnected */ }
    }
    if (microphoneSilenceNode) {
      try { microphoneSilenceNode.disconnect(); } catch (_) { /* already disconnected */ }
    }
    microphoneProcessorNode = null;
    microphoneSourceNode = null;
    microphoneSilenceNode = null;

    if (microphoneTrack) microphoneTrack.onended = null;
    microphoneTrack = null;
    if (microphoneMediaStream) {
      for (const track of microphoneMediaStream.getTracks()) track.stop();
    }
    microphoneMediaStream = null;

    const context = microphoneAudioContext;
    microphoneAudioContext = null;
    if (context && context.state !== 'closed') context.close().catch(() => null);
  }

  function cancelMicrophoneRecovery(resetAttempts = false) {
    if (microphoneRecoveryTimer) window.clearTimeout(microphoneRecoveryTimer);
    microphoneRecoveryTimer = 0;
    if (resetAttempts) microphoneRecoveryAttempts = 0;
  }

  function microphoneCaptureEligible() {
    return Boolean(browserCredential) && androidAutoInteractive && pageActive && !document.hidden;
  }

  function microphoneCaptureIsBusy() {
    return microphoneState === 'starting' || microphoneState === 'checking_permission' ||
      microphoneState === 'requesting' || microphoneState === 'capturing' ||
      microphoneState === 'suspended' || microphoneState === 'retry_wait';
  }

  function microphoneCaptureIsTerminal() {
    return microphoneState === 'blocked_insecure_context' ||
      microphoneState === 'media_devices_unavailable' ||
      microphoneState === 'permission_denied' ||
      microphoneState === 'audio_context_unavailable';
  }

  function ensureMicrophoneCapture() {
    if (!microphoneCaptureEligible() || microphoneCaptureIsBusy() || microphoneCaptureIsTerminal()) return;
    if (!microphoneCaptureRequested && microphonePermissionPrimed) {
      updateMicrophoneState('ready');
      enqueueMicrophoneReadyHeartbeat();
      scheduleMicrophoneReadyHeartbeat();
      return;
    }
    prepareMicrophoneCapture();
  }

  function scheduleMicrophoneRecovery() {
    if (!microphoneCaptureEligible() || microphoneCaptureIsTerminal() || microphoneRecoveryTimer) return;
    const delay = Math.min(
      MICROPHONE_RECOVERY_MAX_DELAY_MILLIS,
      MICROPHONE_RECOVERY_BASE_DELAY_MILLIS * (2 ** Math.min(microphoneRecoveryAttempts, 4))
    );
    microphoneRecoveryAttempts += 1;
    updateMicrophoneState('retry_wait');
    microphoneRecoveryTimer = window.setTimeout(() => {
      microphoneRecoveryTimer = 0;
      if (microphoneCaptureEligible()) prepareMicrophoneCapture();
    }, delay);
  }

  function microphonePcm16Le(inputBuffer) {
    const channelCount = Math.max(1, inputBuffer.numberOfChannels);
    const frameCount = Math.min(inputBuffer.length, MICROPHONE_MAX_RAW_BYTES / 2);
    const channels = [];
    for (let channel = 0; channel < channelCount; channel += 1) {
      channels.push(inputBuffer.getChannelData(channel));
    }
    const bytes = new Uint8Array(frameCount * 2);
    const view = new DataView(bytes.buffer);
    for (let frameIndex = 0; frameIndex < frameCount; frameIndex += 1) {
      let mono = 0;
      for (const samples of channels) mono += samples[frameIndex] || 0;
      mono = Math.max(-1, Math.min(1, mono / channelCount));
      const sample = mono < 0 ? Math.round(mono * 32768) : Math.round(mono * 32767);
      view.setInt16(frameIndex * 2, sample, true);
    }
    return bytes;
  }

  function base64NoWrap(bytes) {
    let binary = '';
    for (let index = 0; index < bytes.byteLength; index += 1) {
      binary += String.fromCharCode(bytes[index]);
    }
    return window.btoa(binary);
  }

  function buildMicrophoneWebRtcFrame(bytes, sampleRate) {
    if (!(bytes instanceof Uint8Array) || !bytes.byteLength ||
        bytes.byteLength > MICROPHONE_MAX_RAW_BYTES || bytes.byteLength % 2 !== 0 ||
        !Number.isInteger(sampleRate) || sampleRate < MICROPHONE_MIN_SAMPLE_RATE_HZ ||
        sampleRate > MICROPHONE_MAX_SAMPLE_RATE_HZ) return null;
    const frame = new ArrayBuffer(MICROPHONE_WEBRTC_HEADER_BYTES + bytes.byteLength);
    const view = new DataView(frame);
    view.setUint8(0, 0x4e); // N
    view.setUint8(1, 0x57); // W
    view.setUint8(2, 0x4d); // M
    view.setUint8(3, 0x31); // 1
    view.setUint8(4, 0); // flags
    view.setUint8(5, 1); // mono
    view.setUint16(6, 0, false); // reserved
    view.setUint32(8, sampleRate, false);
    new Uint8Array(frame, MICROPHONE_WEBRTC_HEADER_BYTES).set(bytes);
    return frame;
  }

  function microphoneWebRtcFrameFits(bufferedAmount, frameByteLength) {
    return Number.isFinite(bufferedAmount) && bufferedAmount >= 0 &&
      Number.isInteger(frameByteLength) && frameByteLength > MICROPHONE_WEBRTC_HEADER_BYTES &&
      frameByteLength <= MICROPHONE_WEBRTC_HEADER_BYTES + MICROPHONE_MAX_RAW_BYTES &&
      bufferedAmount + frameByteLength <= MICROPHONE_WEBRTC_MAX_BUFFERED_AMOUNT;
  }

  function currentMicrophoneWebRtcChannel() {
    const channel = microphoneWebRtcChannel;
    return channel && microphoneWebRtcChannelGeneration === webRtcGeneration &&
      channel.readyState === 'open' ? channel : null;
  }

  function disposeMicrophoneWebRtcChannel(expectedChannel = microphoneWebRtcChannel) {
    if (!expectedChannel || microphoneWebRtcChannel !== expectedChannel) return;
    microphoneWebRtcChannel = null;
    microphoneWebRtcChannelGeneration = 0;
    microphoneWebRtcPendingFrame = null;
    expectedChannel.onopen = null;
    expectedChannel.onclose = null;
    expectedChannel.onerror = null;
    expectedChannel.onbufferedamountlow = null;
    try { expectedChannel.close(); } catch (_) { /* already closed */ }
  }

  function cancelMicrophoneHttpTransportForWebRtc() {
    microphoneQueue.length = 0;
    if (microphoneAbortController) microphoneAbortController.abort();
    microphoneAbortController = null;
    microphoneUploadActiveGeneration = 0;
  }

  function failMicrophoneWebRtcChannel(channel, generation, reason) {
    if (channel !== microphoneWebRtcChannel || generation !== microphoneWebRtcChannelGeneration) return;
    const peerStillStarting = CLOUD_RELAY_MODE && generation === webRtcGeneration && webRtcStarting;
    console.warn(
      peerStillStarting
        ? `WebRTC microphone channel ${reason} while the peer connection is still starting.`
        : `WebRTC microphone channel ${reason}; ` +
          (CLOUD_RELAY_MODE ? 'cloud mode will not relay microphone audio.' : 'using HTTP fallback.')
    );
    disposeMicrophoneWebRtcChannel(channel);
    if (CLOUD_RELAY_MODE && generation === webRtcGeneration) {
      // The main startup await/catch owns recovery until the peer has connected. Letting the
      // data-channel close path reset the same peer here races getStats() and loses the useful
      // ICE failure counters.
      if (peerStillStarting) return;
      // Cloud mode has no PCM-over-HTTP fallback. Re-negotiate the bounded peer connection so
      // voice input can recover instead of remaining silently unavailable for this session.
      window.setTimeout(() => {
        if (generation === webRtcGeneration && (webRtcPeer || webRtcStarting)) failWebRtc();
      }, 0);
      return;
    }
    if (generation === webRtcGeneration) enqueueMicrophoneReadyHeartbeat();
  }

  function flushMicrophoneWebRtcPendingFrame(channel, generation) {
    if (channel !== currentMicrophoneWebRtcChannel() ||
        generation !== microphoneWebRtcChannelGeneration || !microphoneWebRtcPendingFrame) return;
    const frame = microphoneWebRtcPendingFrame;
    if (!microphoneWebRtcFrameFits(channel.bufferedAmount, frame.byteLength)) return;
    microphoneWebRtcPendingFrame = null;
    try {
      channel.send(frame);
    } catch (_) {
      failMicrophoneWebRtcChannel(channel, generation, 'send failed');
    }
  }

  function sendMicrophoneWebRtcFrame(bytes, sampleRate) {
    const channel = currentMicrophoneWebRtcChannel();
    if (!channel) return false;
    const frame = buildMicrophoneWebRtcFrame(bytes, sampleRate);
    if (!frame) return false;
    const generation = microphoneWebRtcChannelGeneration;
    if (!microphoneWebRtcFrameFits(channel.bufferedAmount, frame.byteLength)) {
      // The browser-owned SCTP queue cannot remove data already accepted by send().
      // Keep only the newest not-yet-sent PCM frame so stale speech is never queued here.
      microphoneWebRtcPendingFrame = frame;
      return true;
    }
    microphoneWebRtcPendingFrame = null;
    try {
      channel.send(frame);
      return true;
    } catch (_) {
      failMicrophoneWebRtcChannel(channel, generation, 'send failed');
      return false;
    }
  }

  function createMicrophoneWebRtcChannel(peer, generation) {
    let channel;
    try {
      channel = peer.createDataChannel(MICROPHONE_WEBRTC_CHANNEL_LABEL, {
        ordered: false,
        maxRetransmits: 0
      });
      channel.binaryType = 'arraybuffer';
      channel.bufferedAmountLowThreshold = MICROPHONE_WEBRTC_BUFFERED_AMOUNT_LOW_THRESHOLD;
    } catch (error) {
      if (channel) {
        try { channel.close(); } catch (_) { /* channel setup did not complete */ }
      }
      console.warn(
        CLOUD_RELAY_MODE
          ? 'WebRTC microphone channel is unavailable; cloud mode will not relay microphone audio.'
          : 'WebRTC microphone channel is unavailable; using HTTP fallback.',
        error
      );
      return null;
    }
    disposeMicrophoneWebRtcChannel();
    microphoneWebRtcChannel = channel;
    microphoneWebRtcChannelGeneration = generation;
    microphoneWebRtcPendingFrame = null;
    channel.onopen = () => {
      if (channel !== microphoneWebRtcChannel || generation !== webRtcGeneration) {
        disposeMicrophoneWebRtcChannel(channel);
        return;
      }
      cancelMicrophoneHttpTransportForWebRtc();
      flushMicrophoneWebRtcPendingFrame(channel, generation);
      enqueueMicrophoneReadyHeartbeat();
    };
    channel.onbufferedamountlow = () => flushMicrophoneWebRtcPendingFrame(channel, generation);
    channel.onclose = () => failMicrophoneWebRtcChannel(channel, generation, 'closed');
    channel.onerror = () => failMicrophoneWebRtcChannel(channel, generation, 'failed');
    return channel;
  }

  async function drainMicrophoneQueue(generation) {
    let uploadTimedOut = false;
    try {
      while (generation === microphoneGeneration && browserCredential) {
        const chunk = microphoneQueue.shift();
        if (!chunk) return;
        if (chunk.credential !== browserCredential || chunk.generation !== generation) continue;

        const controller = new AbortController();
        microphoneAbortController = controller;
        uploadTimedOut = false;
        const timeout = window.setTimeout(() => {
          uploadTimedOut = true;
          controller.abort();
        }, MICROPHONE_UPLOAD_TIMEOUT_MILLIS);
        let response;
        try {
          response = await api(MICROPHONE_ENDPOINT, {
            method: 'POST',
            headers: {
              'Content-Type': 'text/plain;charset=UTF-8',
              'X-Audio-Codec': 'pcm-s16le',
              'X-Audio-Sample-Rate': String(chunk.sampleRate),
              'X-Audio-Channels': '1'
            },
            body: chunk.body,
            signal: controller.signal
          }, chunk.credential);
        } finally {
          window.clearTimeout(timeout);
        }
        if (response.body && typeof response.body.cancel === 'function') {
          await response.body.cancel().catch(() => null);
        }
        if (generation !== microphoneGeneration || chunk.credential !== browserCredential) return;
        microphoneAbortController = null;
        if (response.status === 401) {
          invalidateCredential(t('connectionExpired'));
          return;
        }
        if (!response.ok) throw new Error(`microphone HTTP ${response.status}`);
      }
    } catch (error) {
      if (generation === microphoneGeneration &&
          (uploadTimedOut || !(error && error.name === 'AbortError'))) {
        console.warn('Microphone upload stopped after a network or server error.', error);
        stopMicrophoneCapture('upload_failed');
        scheduleMicrophoneRecovery();
      }
    } finally {
      if (microphoneUploadActiveGeneration === generation) {
        microphoneUploadActiveGeneration = 0;
        microphoneAbortController = null;
      }
    }
  }

  function enqueueMicrophoneChunk(bytes, sampleRate, generation, credential) {
    if (!bytes.byteLength || bytes.byteLength > MICROPHONE_MAX_RAW_BYTES ||
        generation !== microphoneGeneration || credential !== browserCredential) return;
    if (sendMicrophoneWebRtcFrame(bytes, sampleRate)) return;
    // The Cloudflare connection is signaling/control only. Never send microphone PCM through it.
    if (CLOUD_RELAY_MODE) return;
    if (microphoneQueue.length >= MICROPHONE_MAX_QUEUED_CHUNKS) microphoneQueue.shift();
    microphoneQueue.push({
      body: base64NoWrap(bytes),
      credential,
      generation,
      sampleRate
    });
    if (microphoneUploadActiveGeneration !== generation) {
      microphoneUploadActiveGeneration = generation;
      drainMicrophoneQueue(generation);
    }
  }

  function enqueueMicrophoneReadyHeartbeat() {
    if (!microphonePermissionPrimed || !browserCredential || microphoneCaptureRequested ||
        !microphoneCaptureEligible()) return;
    enqueueMicrophoneChunk(
      MICROPHONE_IDLE_HEARTBEAT_BYTES,
      microphoneInputSampleRateHz,
      microphoneGeneration,
      browserCredential
    );
  }

  function scheduleMicrophoneReadyHeartbeat() {
    if (microphoneReadyHeartbeatTimer || !microphonePermissionPrimed ||
        microphoneCaptureRequested || !microphoneCaptureEligible()) return;
    const generation = microphoneGeneration;
    const credential = browserCredential;
    microphoneReadyHeartbeatTimer = window.setTimeout(() => {
      microphoneReadyHeartbeatTimer = 0;
      if (generation !== microphoneGeneration || credential !== browserCredential) return;
      enqueueMicrophoneReadyHeartbeat();
      scheduleMicrophoneReadyHeartbeat();
    }, MICROPHONE_READY_HEARTBEAT_INTERVAL_MILLIS);
  }

  async function prepareMicrophoneCapture() {
    stopMicrophoneCapture('starting');
    const generation = microphoneGeneration;
    const credential = browserCredential;
    if (!credential || !pageActive || document.hidden || !androidAutoInteractive) {
      updateMicrophoneState('inactive');
      return;
    }
    if (!window.isSecureContext) {
      updateMicrophoneState('blocked_insecure_context');
      console.warn('Microphone capture is disabled because this page is not a secure context.');
      return;
    }
    if (!navigator.mediaDevices || typeof navigator.mediaDevices.getUserMedia !== 'function') {
      updateMicrophoneState('media_devices_unavailable');
      console.warn('Microphone capture is unavailable in this browser.');
      return;
    }

    updateMicrophoneState('checking_permission');
    let permissionStatus = null;
    if (navigator.permissions && typeof navigator.permissions.query === 'function') {
      try {
        permissionStatus = await navigator.permissions.query({name: 'microphone'});
      } catch (error) {
        console.warn('The browser could not report microphone permission state.', error);
      }
    } else {
      console.warn('The Permissions API is unavailable; the getUserMedia prompt will be authoritative.');
    }
    if (generation !== microphoneGeneration || credential !== browserCredential) return;
    if (permissionStatus) {
      microphonePermissionStatus = permissionStatus;
      permissionStatus.onchange = () => {
        if (microphonePermissionStatus !== permissionStatus) return;
        if (permissionStatus.state === 'denied') {
          console.warn('Microphone permission was revoked.');
          stopMicrophoneCapture('permission_denied');
        } else if (permissionStatus.state === 'granted' && microphoneState === 'permission_denied') {
          updateMicrophoneState('permission_changed');
          ensureMicrophoneCapture();
        }
      };
      if (permissionStatus.state === 'denied') {
        console.warn('Microphone permission is denied.');
        stopMicrophoneCapture('permission_denied');
        return;
      }
    }

    updateMicrophoneState('requesting');
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: {ideal: 1}
        },
        video: false
      });
    } catch (error) {
      if (generation !== microphoneGeneration || credential !== browserCredential) return;
      const denied = error && (error.name === 'NotAllowedError' || error.name === 'SecurityError');
      console.warn(denied ? 'Microphone permission was denied.' : 'Microphone capture failed.', error);
      stopMicrophoneCapture(denied ? 'permission_denied' : 'capture_failed');
      if (!denied) scheduleMicrophoneRecovery();
      return;
    }
    if (generation !== microphoneGeneration || credential !== browserCredential ||
        !pageActive || document.hidden) {
      for (const track of stream.getTracks()) track.stop();
      return;
    }

    const track = stream.getAudioTracks()[0];
    const MicrophoneAudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!track || !MicrophoneAudioContextClass) {
      for (const mediaTrack of stream.getTracks()) mediaTrack.stop();
      console.warn('Microphone PCM conversion is unavailable in this browser.');
      stopMicrophoneCapture('audio_context_unavailable');
      return;
    }

    microphonePermissionPrimed = true;
    const reportedSampleRate = Number(
      typeof track.getSettings === 'function' && track.getSettings().sampleRate
    );
    if (Number.isInteger(reportedSampleRate) &&
        reportedSampleRate >= MICROPHONE_MIN_SAMPLE_RATE_HZ &&
        reportedSampleRate <= MICROPHONE_MAX_SAMPLE_RATE_HZ) {
      microphoneInputSampleRateHz = reportedSampleRate;
    }
    if (!microphoneCaptureRequested) {
      for (const mediaTrack of stream.getTracks()) mediaTrack.stop();
      updateMicrophoneState('ready');
      cancelMicrophoneRecovery(true);
      enqueueMicrophoneReadyHeartbeat();
      scheduleMicrophoneReadyHeartbeat();
      return;
    }

    try {
      const context = new MicrophoneAudioContextClass({latencyHint: 'interactive'});
      microphoneInputSampleRateHz = context.sampleRate;
      microphoneAudioContext = context;
      const source = context.createMediaStreamSource(stream);
      const processor = context.createScriptProcessor(MICROPHONE_SCRIPT_BUFFER_SIZE, 1, 1);
      const silence = context.createGain();
      silence.gain.value = 0;

      microphoneMediaStream = stream;
      microphoneTrack = track;
      microphoneSourceNode = source;
      microphoneProcessorNode = processor;
      microphoneSilenceNode = silence;
      track.onended = () => {
        if (generation === microphoneGeneration && track === microphoneTrack) {
          console.warn('Microphone track ended.');
          stopMicrophoneCapture('track_ended');
          scheduleMicrophoneRecovery();
        }
      };
      processor.onaudioprocess = event => {
        if (generation !== microphoneGeneration || credential !== browserCredential) return;
        if (!microphoneCaptureRequested) return;
        enqueueMicrophoneChunk(
          microphonePcm16Le(event.inputBuffer),
          context.sampleRate,
          generation,
          credential
        );
      };
      source.connect(processor);
      processor.connect(silence);
      silence.connect(context.destination);
      updateMicrophoneState('capturing');
      enqueueMicrophoneReadyHeartbeat();
      scheduleMicrophoneReadyHeartbeat();
      context.resume().then(() => {
        if (generation === microphoneGeneration && context === microphoneAudioContext) {
          updateMicrophoneState('capturing');
          cancelMicrophoneRecovery(true);
        }
      }).catch(error => {
        if (generation === microphoneGeneration && context === microphoneAudioContext) {
          updateMicrophoneState('suspended');
          console.warn('Microphone AudioContext could not start yet.', error);
        }
      });
    } catch (error) {
      for (const mediaTrack of stream.getTracks()) mediaTrack.stop();
      console.warn('Microphone PCM conversion failed.', error);
      stopMicrophoneCapture('audio_context_failed');
      scheduleMicrophoneRecovery();
    }
  }

  function ensureAudioContext() {
    if (audioContext && audioContext.state !== 'closed') return audioContext;
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) return null;
    try {
      audioContext = new AudioContextClass({latencyHint: 'interactive'});
      return audioContext;
    } catch (_) {
      audioContext = null;
      return null;
    }
  }

  function unlockAudio() {
    const context = ensureAudioContext();
    audioUnlocked = Boolean(context && context.state === 'running');
    syncMediaPermissionPanel();
    const outputResume = context
      ? context.resume().then(() => {
          if (context !== audioContext || context.state === 'closed') return false;
          audioUnlocked = context.state === 'running';
          syncMediaPermissionPanel();
          if (audioUnlocked && browserCredential && androidAutoInteractive && !document.hidden) {
            startAudioStreams();
          }
          return audioUnlocked;
        }).catch(() => {
          if (context === audioContext) {
            audioUnlocked = false;
            syncMediaPermissionPanel();
          }
          return false;
        })
      : Promise.resolve(false);
    if (microphoneAudioContext && microphoneAudioContext.state !== 'closed') {
      const microphoneContext = microphoneAudioContext;
      const generation = microphoneGeneration;
      // Refresh the server-side ready lease before forwarding the same user
      // gesture to AA, so an assistant OPEN cannot outrun AudioContext resume.
      enqueueMicrophoneReadyHeartbeat();
      scheduleMicrophoneReadyHeartbeat();
      microphoneContext.resume().then(() => {
        if (generation === microphoneGeneration && microphoneContext === microphoneAudioContext) {
          updateMicrophoneState('capturing');
          cancelMicrophoneRecovery(true);
        }
      }).catch(() => null);
    } else {
      ensureMicrophoneCapture();
    }
    if (audioUnlocked && browserCredential && androidAutoInteractive && !document.hidden) {
      startAudioStreams();
    }
    return outputResume;
  }

  function cancelAudioRecovery(resetAttempts = false) {
    if (audioRecoveryTimer) clearTimeout(audioRecoveryTimer);
    audioRecoveryTimer = 0;
    if (resetAttempts) audioRecoveryAttempts = 0;
  }

  function stopAudioStreams(resetAttempts = false) {
    audioGeneration += 1;
    cancelAudioRecovery(resetAttempts);
    for (const stream of audioStreams.values()) {
      if (stream.controller) stream.controller.abort();
      for (const source of stream.sources) {
        try { source.stop(); } catch (_) { /* 이미 끝난 source입니다. */ }
      }
      stream.sources.clear();
    }
    audioStreams.clear();
  }

  function scheduleAudioRecovery() {
    if (!audioUnlocked || !pageActive || !browserCredential || !androidAutoInteractive ||
        document.hidden || audioRecoveryTimer || audioStreams.size) return;
    const requestedCredential = browserCredential;
    const exponent = Math.min(audioRecoveryAttempts, 4);
    const delayMillis = Math.min(
      AUDIO_RECOVERY_BASE_DELAY_MILLIS * (2 ** exponent),
      AUDIO_RECOVERY_MAX_DELAY_MILLIS
    );
    audioRecoveryTimer = setTimeout(() => {
      audioRecoveryTimer = 0;
      if (browserCredential !== requestedCredential) return;
      audioRecoveryAttempts += 1;
      startAudioStreams();
    }, delayMillis);
  }

  function failAudioStreams(generation) {
    if (generation !== audioGeneration) return;
    stopAudioStreams(false);
    scheduleAudioRecovery();
  }

  function parseAudioFormat(response) {
    const codec = String(response.headers.get('X-Audio-Codec') || '').toLowerCase();
    const sampleRate = Number(response.headers.get('X-Audio-Sample-Rate'));
    const channels = Number(response.headers.get('X-Audio-Channels'));
    if (codec !== 'pcm-s16le' || !Number.isInteger(sampleRate) ||
        sampleRate < 8000 || sampleRate > 192000 || ![1, 2].includes(channels)) {
      throw new Error('invalid audio stream format');
    }
    return {sampleRate, channels};
  }

  function schedulePcmChunk(stream, pcmBytes) {
    const context = audioContext;
    if (!context || context.state !== 'running') return;
    const frameBytes = stream.channels * 2;
    const frameCount = Math.floor(pcmBytes.byteLength / frameBytes);
    if (!frameCount) return;
    const buffer = context.createBuffer(stream.channels, frameCount, stream.sampleRate);
    const samples = new DataView(pcmBytes.buffer, pcmBytes.byteOffset, frameCount * frameBytes);
    for (let channel = 0; channel < stream.channels; channel += 1) {
      const output = buffer.getChannelData(channel);
      for (let frameIndex = 0; frameIndex < frameCount; frameIndex += 1) {
        const offset = (frameIndex * stream.channels + channel) * 2;
        output[frameIndex] = samples.getInt16(offset, true) / 32768;
      }
    }

    if (stream.nextStartTime > context.currentTime + AUDIO_MAX_SCHEDULE_AHEAD_SECONDS) {
      for (const source of stream.sources) {
        try { source.stop(); } catch (_) { /* 이미 끝난 source입니다. */ }
      }
      stream.sources.clear();
      stream.nextStartTime = context.currentTime + AUDIO_START_AHEAD_SECONDS;
    } else if (stream.nextStartTime < context.currentTime) {
      stream.nextStartTime = context.currentTime + AUDIO_START_AHEAD_SECONDS;
    }

    const source = context.createBufferSource();
    source.buffer = buffer;
    source.connect(context.destination);
    stream.sources.add(source);
    source.addEventListener('ended', () => stream.sources.delete(source), {once: true});
    source.start(stream.nextStartTime);
    stream.nextStartTime += frameCount / stream.sampleRate;
  }

  function audioWebRtcChannelLabel(track) {
    return `${AUDIO_WEBRTC_CHANNEL_PREFIX}${track}${AUDIO_WEBRTC_CHANNEL_SUFFIX}`;
  }

  function disposeOutputAudioWebRtcChannels() {
    for (const timeout of webRtcAudioOpenTimers.values()) window.clearTimeout(timeout);
    webRtcAudioOpenTimers.clear();
    for (const channel of webRtcAudioChannels.values()) {
      channel.onopen = null;
      channel.onclose = null;
      channel.onerror = null;
      channel.onmessage = null;
      try { channel.close(); } catch (_) { /* already closed */ }
    }
    webRtcAudioChannels.clear();
  }

  function failOutputAudioWebRtcChannel(channel, generation, track, reason) {
    if (webRtcAudioChannels.get(track) !== channel || generation !== webRtcGeneration) return;
    channel.onopen = null;
    channel.onclose = null;
    channel.onerror = null;
    channel.onmessage = null;
    webRtcAudioChannels.delete(track);
    const openTimer = webRtcAudioOpenTimers.get(track);
    if (openTimer !== undefined) window.clearTimeout(openTimer);
    webRtcAudioOpenTimers.delete(track);
    try { channel.close(); } catch (_) { /* already closed */ }
    const stream = audioStreams.get(track);
    if (stream && stream.transport === 'webrtc') {
      for (const source of stream.sources) {
        try { source.stop(); } catch (_) { /* already stopped */ }
      }
      stream.sources.clear();
      audioStreams.delete(track);
    }
    outputAudioWebRtcRecoveryRequired = true;
    pad.dataset.navonwebOutputAudioState = `${track}_${reason}`;
    scheduleOutputAudioWebRtcRecovery();
    console.warn(
      `WebRTC ${track} audio channel ${reason}; video and microphone remain connected while ` +
        'audio recovery waits for its backoff.'
    );
  }

  function cancelOutputAudioWebRtcRecovery(resetAttempts = false) {
    if (outputAudioWebRtcRecoveryTimer) window.clearTimeout(outputAudioWebRtcRecoveryTimer);
    outputAudioWebRtcRecoveryTimer = 0;
    if (resetAttempts) outputAudioWebRtcRecoveryAttempts = 0;
  }

  function armOutputAudioWebRtcOpenTimers(generation) {
    for (const [track, channel] of webRtcAudioChannels.entries()) {
      if (channel.readyState === 'open' || webRtcAudioOpenTimers.has(track)) continue;
      webRtcAudioOpenTimers.set(track, window.setTimeout(() => {
        if (generation === webRtcGeneration && webRtcAudioChannels.get(track) === channel &&
            channel.readyState !== 'open') {
          failOutputAudioWebRtcChannel(channel, generation, track, 'open_timeout');
        }
      }, AUDIO_WEBRTC_OPEN_TIMEOUT_MILLIS));
    }
  }

  function scheduleOutputAudioWebRtcRecovery() {
    if (!CLOUD_RELAY_MODE || !outputAudioWebRtcRecoveryRequired || outputAudioWebRtcUnsupported ||
        outputAudioWebRtcRecoveryTimer || !pageActive || document.hidden || !browserCredential ||
        !androidAutoInteractive) return;
    const exponent = Math.min(outputAudioWebRtcRecoveryAttempts, 4);
    const delayMillis = Math.min(
      AUDIO_WEBRTC_RECOVERY_BASE_DELAY_MILLIS * (2 ** exponent),
      AUDIO_WEBRTC_RECOVERY_MAX_DELAY_MILLIS
    );
    outputAudioWebRtcRecoveryTimer = window.setTimeout(() => {
      outputAudioWebRtcRecoveryTimer = 0;
      if (!outputAudioWebRtcRecoveryRequired || outputAudioWebRtcUnsupported ||
          !pageActive || document.hidden || !browserCredential || !androidAutoInteractive) return;
      outputAudioWebRtcRecoveryAttempts += 1;
      if (webRtcPeer || webRtcStarting) failWebRtc();
      else scheduleWebRtcRecovery();
    }, delayMillis);
  }

  function confirmOutputAudioWebRtcChannelsOpen() {
    if (!webRtcAudioChannels.size ||
        [...webRtcAudioChannels.values()].some(channel => channel.readyState !== 'open')) return;
    outputAudioWebRtcRecoveryRequired = false;
    cancelOutputAudioWebRtcRecovery(true);
    delete pad.dataset.navonwebOutputAudioState;
  }

  function parseOutputAudioWebRtcFrame(value) {
    let bytes;
    if (value instanceof ArrayBuffer) {
      bytes = new Uint8Array(value);
    } else if (ArrayBuffer.isView(value)) {
      bytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    } else {
      return null;
    }
    if (bytes.byteLength <= AUDIO_WEBRTC_HEADER_BYTES ||
        bytes.byteLength > AUDIO_WEBRTC_HEADER_BYTES + AUDIO_WEBRTC_MAX_PCM_BYTES) return null;
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (view.getUint8(0) !== 0x4e || view.getUint8(1) !== 0x57 ||
        view.getUint8(2) !== 0x41 || view.getUint8(3) !== 0x31 ||
        view.getUint8(4) !== 0) return null;
    const channels = view.getUint8(5);
    if (view.getUint16(6, false) !== 0) return null;
    const sampleRate = view.getUint32(8, false);
    const pcmBytes = bytes.subarray(AUDIO_WEBRTC_HEADER_BYTES);
    if (![1, 2].includes(channels) || !Number.isInteger(sampleRate) ||
        sampleRate < 8000 || sampleRate > 192000 ||
        pcmBytes.byteLength % (channels * 2) !== 0) return null;
    return {channels, sampleRate, pcmBytes};
  }

  function outputAudioWebRtcStream(track, frame) {
    let stream = audioStreams.get(track);
    if (stream && (stream.transport !== 'webrtc' || stream.sampleRate !== frame.sampleRate ||
        stream.channels !== frame.channels)) {
      if (stream.controller) stream.controller.abort();
      for (const source of stream.sources) {
        try { source.stop(); } catch (_) { /* already stopped */ }
      }
      stream.sources.clear();
      audioStreams.delete(track);
      stream = null;
    }
    if (!stream) {
      const context = ensureAudioContext();
      if (!context) return null;
      stream = {
        transport: 'webrtc',
        controller: null,
        sampleRate: frame.sampleRate,
        channels: frame.channels,
        nextStartTime: Math.max(context.currentTime, 0) + AUDIO_START_AHEAD_SECONDS,
        sources: new Set()
      };
      audioStreams.set(track, stream);
    }
    return stream;
  }

  function handleOutputAudioWebRtcMessage(track, channel, generation, event) {
    if (generation !== webRtcGeneration || webRtcAudioChannels.get(track) !== channel) return;
    const frame = parseOutputAudioWebRtcFrame(event.data);
    if (!frame || !audioUnlocked || !pageActive || document.hidden || !androidAutoInteractive) return;
    const context = ensureAudioContext();
    if (!context || context.state !== 'running') {
      audioUnlocked = false;
      syncMediaPermissionPanel();
      return;
    }
    const stream = outputAudioWebRtcStream(track, frame);
    if (!stream) return;
    schedulePcmChunk(stream, frame.pcmBytes);
  }

  function createOutputAudioWebRtcChannels(peer, generation, supportedTracks) {
    disposeOutputAudioWebRtcChannels();
    try {
      for (const track of supportedTracks) {
        const channel = peer.createDataChannel(audioWebRtcChannelLabel(track), {
          // Output PCM must remain frame-ordered. Reliable ordered DCEP has the
          // broadest interoperability with Android libwebrtc; the native 48 KiB
          // backpressure cap still prevents an unbounded stale-audio queue.
          ordered: true
        });
        channel.binaryType = 'arraybuffer';
        channel.onmessage = event => handleOutputAudioWebRtcMessage(track, channel, generation, event);
        channel.onclose = () => failOutputAudioWebRtcChannel(channel, generation, track, 'closed');
        channel.onerror = () => failOutputAudioWebRtcChannel(channel, generation, track, 'failed');
        channel.onopen = () => {
          const openTimer = webRtcAudioOpenTimers.get(track);
          if (openTimer !== undefined) window.clearTimeout(openTimer);
          webRtcAudioOpenTimers.delete(track);
          if (generation !== webRtcGeneration || webRtcAudioChannels.get(track) !== channel) {
            try { channel.close(); } catch (_) { /* stale negotiation */ }
            return;
          }
          confirmOutputAudioWebRtcChannelsOpen();
        };
        webRtcAudioChannels.set(track, channel);
      }
      outputAudioWebRtcUnsupported = false;
      return webRtcAudioChannels.size === supportedTracks.length;
    } catch (error) {
      disposeOutputAudioWebRtcChannels();
      console.warn('WebRTC output audio channels are unavailable.', error);
      return false;
    }
  }

  async function consumeAudioStream(stream, response, generation) {
    const reader = response.body && response.body.getReader ? response.body.getReader() : null;
    if (!reader) throw new Error('streaming response body unavailable');
    let remainder = new Uint8Array(0);
    while (generation === audioGeneration) {
      const result = await reader.read();
      if (result.done) throw new Error('audio stream ended');
      const incoming = result.value || new Uint8Array(0);
      const combined = new Uint8Array(remainder.byteLength + incoming.byteLength);
      combined.set(remainder, 0);
      combined.set(incoming, remainder.byteLength);
      const frameBytes = stream.channels * 2;
      const alignedLength = combined.byteLength - combined.byteLength % frameBytes;
      if (alignedLength > 0) schedulePcmChunk(stream, combined.subarray(0, alignedLength));
      remainder = combined.slice(alignedLength);
    }
  }

  async function openAudioTrack(track, stream, generation, credential) {
    try {
      const response = await api(`/api/audio/${track}`, {
        headers: {'Accept': 'application/octet-stream'},
        signal: stream.controller.signal
      }, credential);
      if (generation !== audioGeneration || credential !== browserCredential) return;
      if (response.status === 401) {
        invalidateCredential(t('connectionExpired'));
        return;
      }
      if (!response.ok) throw new Error(`audio HTTP ${response.status}`);
      const format = parseAudioFormat(response);
      stream.sampleRate = format.sampleRate;
      stream.channels = format.channels;
      stream.nextStartTime = Math.max(audioContext.currentTime, 0) + AUDIO_START_AHEAD_SECONDS;
      await consumeAudioStream(stream, response, generation);
    } catch (error) {
      if (generation === audioGeneration && !stream.controller.signal.aborted) {
        failAudioStreams(generation);
      }
    }
  }

  function startAudioStreams() {
    // Output-audio is still an HTTP stream and is not tunneled through the cloud relay.
    if (CLOUD_RELAY_MODE || !audioUnlocked || !pageActive || !browserCredential || !androidAutoInteractive ||
        document.hidden || audioStreams.size) return;
    const context = ensureAudioContext();
    if (!context) return;
    context.resume().catch(() => null);
    cancelAudioRecovery();
    const generation = ++audioGeneration;
    const credential = browserCredential;
    for (const track of AUDIO_TRACKS) {
      const stream = {
        transport: 'http',
        controller: new AbortController(),
        sampleRate: 0,
        channels: 0,
        nextStartTime: 0,
        sources: new Set()
      };
      audioStreams.set(track, stream);
      openAudioTrack(track, stream, generation, credential);
    }
  }

  function normalizeIceServers(value) {
    if (!Array.isArray(value)) return [];
    return value.map(server => {
      const urls = Array.isArray(server && server.urls)
        ? server.urls.filter(url => typeof url === 'string' && /^(stun|turn|turns):/.test(url))
        : [];
      if (!urls.length) return null;
      const normalized = {urls};
      if (typeof server.username === 'string') normalized.username = server.username;
      if (typeof server.credential === 'string') normalized.credential = server.credential;
      return normalized;
    }).filter(Boolean);
  }

  function cancelWebRtcRecovery(resetAttempts = false) {
    if (webRtcRecoveryTimer) clearTimeout(webRtcRecoveryTimer);
    webRtcRecoveryTimer = 0;
    if (resetAttempts) webRtcRecoveryAttempts = 0;
  }

  function scheduleWebRtcRecovery() {
    if (!pageActive || !browserCredential || !androidAutoInteractive || document.hidden ||
        !localNetworkPermissionAllowsWebRtc(false) ||
        webRtcRecoveryTimer || webRtcRecoveryInFlight || webRtcStarting ||
        webRtcPeer && isWebRtcConnected(webRtcPeer)) return;
    const requestedCredential = browserCredential;
    const exponent = Math.min(webRtcRecoveryAttempts, 4);
    const delayMillis = Math.min(
      WEBRTC_RECOVERY_BASE_DELAY_MILLIS * (2 ** exponent),
      WEBRTC_RECOVERY_MAX_DELAY_MILLIS
    );
    webRtcRecoveryTimer = setTimeout(async () => {
      webRtcRecoveryTimer = 0;
      if (!pageActive || browserCredential !== requestedCredential || !androidAutoInteractive ||
          document.hidden || !localNetworkPermissionAllowsWebRtc(false) ||
          webRtcStarting || webRtcRecoveryInFlight) return;
      webRtcRecoveryAttempts += 1;
      webRtcRecoveryInFlight = true;
      try {
        if (webRtcPeer) {
          if (isWebRtcConnected(webRtcPeer)) {
            cancelWebRtcRecovery(true);
            return;
          }
          resetWebRtc(true);
          await delay(WEBRTC_RECOVERY_CLOSE_GRACE_MILLIS);
        }
        if (pageActive && browserCredential === requestedCredential &&
            androidAutoInteractive && !document.hidden) await startWebRtc();
      } finally {
        webRtcRecoveryInFlight = false;
        if (!webRtcPeer && browserCredential === requestedCredential && androidAutoInteractive) {
          scheduleWebRtcRecovery();
        }
      }
    }, delayMillis);
  }

  async function ensureWebRtcCapabilities(force = false) {
    if (!browserCredential || typeof RTCPeerConnection === 'undefined') return null;
    if (force) webRtcCapabilitiesPromise = null;
    if (webRtcCapabilitiesPromise) return webRtcCapabilitiesPromise;
    const requestedCredential = browserCredential;
    const requestedProjectionRevision = projectionProfileRevision;
    const requestIsCurrent = () => browserCredential === requestedCredential &&
      projectionProfileRevision === requestedProjectionRevision;
    webRtcCapabilitiesPromise = (async () => {
      try {
        const response = await api('/api/webrtc/capabilities', {}, requestedCredential);
        if (!requestIsCurrent()) return null;
        if (response.status === 401) {
          invalidateCredential(t('connectionExpired'));
          return null;
        }
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();
        if (!requestIsCurrent()) return null;
        const nativeCodecs = Array.isArray(data.codecs)
          ? data.codecs.map(name => String(name).toLowerCase()).filter(name => CODEC_NAMES.includes(name))
          : [];
        const outputAudioDataChannelsV1 = Array.isArray(data.outputAudioDataChannelsV1)
          ? data.outputAudioDataChannelsV1
              .map(track => String(track).toLowerCase())
              .filter(track => AUDIO_TRACKS.includes(track))
          : [];
        webRtcServerCapabilities = {
          available: data.available === true,
          codecs: [...new Set(nativeCodecs)],
          iceServers: normalizeIceServers(data.iceServers),
          outputAudioDataChannelsV1: [...new Set(outputAudioDataChannelsV1)],
          controlDataChannelV1: data.controlDataChannelV1 === true
        };
        outputAudioWebRtcUnsupported = CLOUD_RELAY_MODE && webRtcServerCapabilities.available &&
          webRtcServerCapabilities.outputAudioDataChannelsV1.length === 0;
        pad.dataset.navonwebOutputAudioCapability = outputAudioWebRtcUnsupported
          ? 'unsupported'
          : 'available';
        if (outputAudioWebRtcUnsupported) {
          outputAudioWebRtcRecoveryRequired = false;
          cancelOutputAudioWebRtcRecovery(true);
        }
        pad.dataset.navonwebIceServerCount = String(webRtcServerCapabilities.iceServers.length);
        pad.dataset.navonwebControlCapability = webRtcServerCapabilities.controlDataChannelV1
          ? 'available'
          : 'unavailable';
        const compatible = webRtcServerCapabilities.codecs.some(name => browserCodecCapabilities.has(name));
        if (webRtcServerCapabilities.available && compatible &&
            localNetworkPermissionAllowsWebRtc(false) && !webRtcPeer && !webRtcStarting &&
            !webRtcRecoveryTimer && !webRtcRecoveryInFlight) {
          queueMicrotask(() => startWebRtc());
        }
        return webRtcServerCapabilities;
      } catch (_) {
        if (requestIsCurrent()) {
          webRtcServerCapabilities = null;
          pad.dataset.navonwebWebRtcFailureStage = 'capabilities';
        }
        return null;
      }
    })();
    return webRtcCapabilitiesPromise;
  }

  function createControlWebRtcChannel(peer, generation) {
    if (!CLOUD_RELAY_MODE) return null;
    let channel = null;
    try {
      channel = peer.createDataChannel(CONTROL_WEBRTC_CHANNEL_LABEL, {ordered: true});
      const transport = new WebRtcControlTransport(channel, generation);
      const previous = webRtcControlTransport;
      webRtcControlTransport = transport;
      if (previous) previous.close();
      pad.dataset.navonwebControlTransport = 'negotiating';
      return transport;
    } catch (error) {
      if (channel) {
        try { channel.close(); } catch (_) { /* setup did not complete */ }
      }
      pad.dataset.navonwebControlTransport = 'cloud_fallback';
      console.warn('WebRTC control channel is unavailable; using cloud fallback.', error);
      return null;
    }
  }

  function disposeControlWebRtcChannel() {
    const transport = webRtcControlTransport;
    webRtcControlTransport = null;
    if (transport) transport.close();
    if (CLOUD_RELAY_MODE) pad.dataset.navonwebControlTransport = 'cloud_fallback';
  }

  function resetWebRtc(notifyServer = true) {
    cancelWebRtcRecovery();
    cancelOutputAudioWebRtcRecovery(false);
    const closingSessionId = webRtcSessionId;
    const closingCredential = browserCredential;
    const closingPeer = webRtcPeer;
    webRtcGeneration += 1;
    disposeControlWebRtcChannel();
    disposeMicrophoneWebRtcChannel();
    disposeOutputAudioWebRtcChannels();
    if (CLOUD_RELAY_MODE) stopAudioStreams(true);
    if (webRtcConnectionWaitCancel) webRtcConnectionWaitCancel();
    webRtcConnectionWaitCancel = null;
    webRtcSessionId = '';
    webRtcPeer = null;
    webRtcStarting = false;
    pad.classList.remove('webrtc-ready');
    webRtcVideo.pause();
    webRtcVideo.srcObject = null;
    if (closingPeer) closingPeer.close();
    if (notifyServer && WEBRTC_SESSION_PATTERN.test(closingSessionId) && closingCredential) {
      api(
        `/api/webrtc/session/${encodeURIComponent(closingSessionId)}`,
        {method: 'DELETE'},
        closingCredential
      ).catch(() => null);
    }
    if (browserCredential) startFramePolling();
  }

  function failWebRtc() {
    resetWebRtc(true);
    // The phone's LAN address and ephemeral STUN port can change whenever the
    // service or network restarts. Never carry a stale ICE server into the
    // bounded recovery attempt.
    webRtcCapabilitiesPromise = null;
    webRtcServerCapabilities = null;
    scheduleWebRtcRecovery();
  }

  function applyCodecPreference(transceiver) {
    if (!transceiver.setCodecPreferences || typeof RTCRtpReceiver === 'undefined') return;
    const receiverCapabilities = RTCRtpReceiver.getCapabilities('video');
    const available = (receiverCapabilities && receiverCapabilities.codecs) || [];
    const preferred = [];
    for (const codecName of (webRtcServerCapabilities && webRtcServerCapabilities.codecs) || []) {
      for (const entry of available) {
        if (String(entry.mimeType || '').toLowerCase() === `video/${codecName}` &&
            !preferred.includes(entry)) preferred.push(entry);
      }
    }
    const primaryPayloadTypes = new Set(preferred
      .map(entry => Number(entry.preferredPayloadType))
      .filter(Number.isInteger));
    const recoveryMimeTypes = new Set([
      'video/red',
      'video/ulpfec',
      'video/flexfec-03'
    ]);
    for (const entry of available) {
      const mimeType = String(entry.mimeType || '').toLowerCase();
      if (mimeType === 'video/rtx') {
        const aptMatch = /(?:^|;)\s*apt=(\d+)(?:;|$)/i.exec(String(entry.sdpFmtpLine || ''));
        const apt = aptMatch ? Number(aptMatch[1]) : NaN;
        if (Number.isInteger(apt) && primaryPayloadTypes.has(apt) &&
            !preferred.includes(entry)) preferred.push(entry);
      } else if (recoveryMimeTypes.has(mimeType) &&
          !preferred.includes(entry)) preferred.push(entry);
    }
    if (preferred.length) transceiver.setCodecPreferences(preferred);
  }

  function delay(milliseconds) {
    return new Promise(resolve => setTimeout(resolve, milliseconds));
  }

  function webRtcConnectionState(peer) {
    return peer.connectionState || peer.iceConnectionState || 'new';
  }

  function isWebRtcConnected(peer) {
    const state = webRtcConnectionState(peer);
    return state === 'connected' || state === 'completed';
  }

  async function publishSelectedIcePair(peer, generation) {
    if (generation !== webRtcGeneration || peer !== webRtcPeer ||
        webRtcIcePairPublishedGeneration === generation || typeof peer.getStats !== 'function') return;
    try {
      const report = await peer.getStats();
      let selectedPair = null;
      report.forEach(entry => {
        if (!selectedPair && entry.type === 'transport' && entry.selectedCandidatePairId) {
          selectedPair = report.get(entry.selectedCandidatePairId) || null;
        }
      });
      if (!selectedPair) {
        report.forEach(entry => {
          if (!selectedPair && entry.type === 'candidate-pair' && entry.state === 'succeeded' &&
              (entry.nominated || entry.selected)) selectedPair = entry;
        });
      }
      if (!selectedPair || generation !== webRtcGeneration || peer !== webRtcPeer) return;
      const local = report.get(selectedPair.localCandidateId);
      const remote = report.get(selectedPair.remoteCandidateId);
      const localType = String(local && local.candidateType || 'unknown');
      const remoteType = String(remote && remote.candidateType || 'unknown');
      const protocol = String(local && local.protocol || remote && remote.protocol || 'unknown');
      webRtcIcePairPublishedGeneration = generation;
      pad.dataset.navonwebIceLocalType = localType;
      pad.dataset.navonwebIceRemoteType = remoteType;
      pad.dataset.navonwebIceProtocol = protocol;
      pad.dataset.navonwebIceRelayed = String(localType === 'relay' || remoteType === 'relay');
      console.info(
        `NAVONWEB_ICE_SELECTED local=${localType} remote=${remoteType} ` +
          `protocol=${protocol} relayed=${pad.dataset.navonwebIceRelayed}`
      );
    } catch (_) {
      // Stats are diagnostic only and must never disrupt an established media path.
    }
  }

  function clearIceFailureDiagnostics() {
    delete pad.dataset.navonwebIceFailurePairState;
    delete pad.dataset.navonwebIceRequestsSent;
    delete pad.dataset.navonwebIceResponsesReceived;
    delete pad.dataset.navonwebIceRequestsReceived;
    delete pad.dataset.navonwebIceResponsesSent;
  }

  function webRtcFailureReason(error) {
    const message = String(error && error.message || '').toLowerCase();
    if (message.includes('timeout')) return 'timeout';
    if (message.includes('failed')) return 'failed';
    if (message.includes('cancel')) return 'cancelled';
    if (message.includes('closed')) return 'closed';
    return 'error';
  }

  async function publishIceFailure(peer, generation, stage, reason) {
    if (!peer || generation !== webRtcGeneration || peer !== webRtcPeer ||
        typeof peer.getStats !== 'function') return;
    try {
      const report = await peer.getStats();
      let pair = null;
      let pairScore = -1;
      report.forEach(entry => {
        if (entry.type === 'transport' && entry.selectedCandidatePairId) {
          const selected = report.get(entry.selectedCandidatePairId);
          if (selected) {
            pair = selected;
            pairScore = Number.MAX_SAFE_INTEGER;
          }
        }
      });
      if (pairScore !== Number.MAX_SAFE_INTEGER) {
        report.forEach(entry => {
          if (entry.type !== 'candidate-pair') return;
          const requestsSent = Number(entry.requestsSent) || 0;
          const responsesReceived = Number(entry.responsesReceived) || 0;
          const score = (entry.nominated || entry.selected ? 1_000_000 : 0) +
            (entry.state === 'succeeded' ? 100_000 : entry.state === 'in-progress' ? 10_000 : 0) +
            Math.min(requestsSent + responsesReceived, 9_999);
          if (score > pairScore) {
            pair = entry;
            pairScore = score;
          }
        });
      }
      if (!pair || generation !== webRtcGeneration || peer !== webRtcPeer) {
        console.warn(`NAVONWEB_ICE_FAILED stage=${stage} reason=${reason} pair=none`);
        return;
      }
      const local = report.get(pair.localCandidateId);
      const remote = report.get(pair.remoteCandidateId);
      const localType = String(local && local.candidateType || 'unknown');
      const remoteType = String(remote && remote.candidateType || 'unknown');
      const protocol = String(local && local.protocol || remote && remote.protocol || 'unknown');
      const pairState = String(pair.state || 'unknown');
      const requestsSent = Math.max(0, Number(pair.requestsSent) || 0);
      const responsesReceived = Math.max(0, Number(pair.responsesReceived) || 0);
      const requestsReceived = Math.max(0, Number(pair.requestsReceived) || 0);
      const responsesSent = Math.max(0, Number(pair.responsesSent) || 0);
      pad.dataset.navonwebIceFailurePairState = pairState;
      pad.dataset.navonwebIceRequestsSent = String(requestsSent);
      pad.dataset.navonwebIceResponsesReceived = String(responsesReceived);
      pad.dataset.navonwebIceRequestsReceived = String(requestsReceived);
      pad.dataset.navonwebIceResponsesSent = String(responsesSent);
      console.warn(
        `NAVONWEB_ICE_FAILED stage=${stage} reason=${reason} state=${pairState} ` +
          `local=${localType} remote=${remoteType} protocol=${protocol} ` +
          `requestsSent=${requestsSent} responsesReceived=${responsesReceived} ` +
          `requestsReceived=${requestsReceived} responsesSent=${responsesSent}`
      );
    } catch (_) {
      console.warn(`NAVONWEB_ICE_FAILED stage=${stage} reason=${reason} stats=unavailable`);
    }
  }

  function waitForIceGatheringComplete(peer) {
    if (peer.iceGatheringState === 'complete') return Promise.resolve();
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        peer.removeEventListener('icegatheringstatechange', onStateChange);
        const hasCandidate = /(?:^|\r?\n)a=candidate:/m.test(peer.localDescription && peer.localDescription.sdp || '');
        if (hasCandidate) {
          pad.dataset.navonwebIceGathering = 'partial';
          resolve();
        } else {
          reject(new Error('ICE timeout'));
        }
      }, WEBRTC_ICE_TIMEOUT_MILLIS);
      function onStateChange() {
        if (peer.iceGatheringState !== 'complete') return;
        clearTimeout(timeout);
        peer.removeEventListener('icegatheringstatechange', onStateChange);
        resolve();
      }
      peer.addEventListener('icegatheringstatechange', onStateChange);
    });
  }

  function waitForWebRtcConnection(peer, generation) {
    return new Promise((resolve, reject) => {
      let settled = false;
      const finish = (action, value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        peer.removeEventListener('connectionstatechange', onStateChange);
        peer.removeEventListener('iceconnectionstatechange', onStateChange);
        if (webRtcConnectionWaitCancel === cancel) webRtcConnectionWaitCancel = null;
        action(value);
      };
      const cancel = () => finish(reject, new Error('WebRTC cancelled'));
      const onStateChange = () => {
        if (generation !== webRtcGeneration || peer !== webRtcPeer) {
          cancel();
          return;
        }
        const state = webRtcConnectionState(peer);
        if (state === 'connected' || state === 'completed') {
          cancelWebRtcRecovery(true);
          finish(resolve);
        } else if (state === 'failed' || state === 'closed') {
          finish(reject, new Error(`WebRTC ${state}`));
        }
      };
      const timeout = setTimeout(
        () => finish(reject, new Error('WebRTC timeout')),
        WEBRTC_CONNECTION_TIMEOUT_MILLIS
      );
      webRtcConnectionWaitCancel = cancel;
      peer.addEventListener('connectionstatechange', onStateChange);
      peer.addEventListener('iceconnectionstatechange', onStateChange);
      onStateChange();
    });
  }

  async function waitForWebRtcAnswer(initial, generation) {
    const deadline = performance.now() + WEBRTC_ANSWER_TIMEOUT_MILLIS;
    let current = initial;
    while (generation === webRtcGeneration && webRtcPeer) {
      if (current.state === 'ready') {
        if (typeof current.answerSdp !== 'string' || !current.answerSdp.includes('m=video ')) {
          throw new Error('invalid WebRTC answer');
        }
        return current;
      }
      if (current.state === 'failed' || current.state === 'closed') {
        throw new Error(String(current.detail || `session ${current.state}`));
      }
      if (performance.now() >= deadline) throw new Error('WebRTC answer timeout');
      await delay(WEBRTC_ANSWER_POLL_MILLIS);
      const response = await api(`/api/webrtc/session/${encodeURIComponent(webRtcSessionId)}`);
      if (response.status === 401) throw new Error('browser credential expired');
      if (!response.ok) throw new Error(`signaling HTTP ${response.status}`);
      current = await response.json();
    }
    throw new Error('WebRTC cancelled');
  }

  async function startWebRtc(userInitiated = false) {
    if (!pageActive || document.hidden || !browserCredential || !androidAutoInteractive ||
        webRtcStarting || webRtcPeer || !localNetworkPermissionAllowsWebRtc(userInitiated)) {
      syncLocalNetworkPermissionPanel();
      return;
    }
    let startupStage = 'capabilities';
    const capabilities = await ensureWebRtcCapabilities();
    if (!pageActive || document.hidden || !capabilities || !capabilities.available ||
        !androidAutoInteractive || webRtcStarting || webRtcPeer ||
        !localNetworkPermissionAllowsWebRtc(userInitiated)) {
      if (!capabilities) pad.dataset.navonwebWebRtcFailureStage = 'capabilities';
      else if (!capabilities.available) pad.dataset.navonwebWebRtcFailureStage = 'unavailable';
      return;
    }
    const compatible = capabilities.codecs.some(name => browserCodecCapabilities.has(name));
    if (!compatible) {
      pad.dataset.navonwebWebRtcFailureStage = 'no_compatible_codec';
      return;
    }
    webRtcStarting = true;
    const generation = ++webRtcGeneration;
    let peer = null;
    clearIceFailureDiagnostics();
    try {
      startupStage = 'peer';
      peer = new RTCPeerConnection({iceServers: capabilities.iceServers});
      webRtcPeer = peer;
      peer.addEventListener('icecandidateerror', event => {
        if (generation !== webRtcGeneration || peer !== webRtcPeer) return;
        const code = Number.isInteger(event.errorCode) ? event.errorCode : 0;
        const schemeMatch = /^([a-z][a-z0-9+.-]*):/i.exec(String(event.url || ''));
        const scheme = schemeMatch ? schemeMatch[1].toLowerCase() : 'unknown';
        pad.dataset.navonwebIceCandidateErrorCode = String(code);
        pad.dataset.navonwebIceCandidateErrorScheme = scheme;
        console.warn(`NAVONWEB_ICE_CANDIDATE_ERROR code=${code} scheme=${scheme}`);
      });
      let peerFailureHandling = false;
      if (capabilities.controlDataChannelV1) createControlWebRtcChannel(peer, generation);
      const microphoneChannel = createMicrophoneWebRtcChannel(peer, generation);
      if (CLOUD_RELAY_MODE && !microphoneChannel) {
        throw new Error('WebRTC microphone data channel is required in cloud mode');
      }
      const outputAudioTracks = capabilities.outputAudioDataChannelsV1 || [];
      if (CLOUD_RELAY_MODE && outputAudioTracks.length > 0 &&
          !createOutputAudioWebRtcChannels(peer, generation, outputAudioTracks)) {
        throw new Error('WebRTC output audio data channels are required in cloud mode');
      }
      const transceiver = peer.addTransceiver('video', {direction: 'recvonly'});
      applyCodecPreference(transceiver);
      peer.addEventListener('track', event => {
        if (generation !== webRtcGeneration || peer !== webRtcPeer || event.track.kind !== 'video') return;
        webRtcVideo.srcObject = event.streams[0] || new MediaStream([event.track]);
        webRtcVideo.play().catch(() => null);
      });
      const updateConnectionState = () => {
        if (generation !== webRtcGeneration || peer !== webRtcPeer) return;
        const state = webRtcConnectionState(peer);
        if (state === 'connected' || state === 'completed') {
          cancelWebRtcRecovery(true);
          armOutputAudioWebRtcOpenTimers(generation);
          pad.classList.add('webrtc-ready');
          stopFramePolling(false);
          webRtcStarting = false;
          publishSelectedIcePair(peer, generation);
        } else if (state === 'failed' || state === 'closed') {
          if (webRtcStarting || peerFailureHandling) return;
          peerFailureHandling = true;
          publishIceFailure(peer, generation, 'established', state).finally(() => {
            if (generation === webRtcGeneration && peer === webRtcPeer) failWebRtc();
          });
        } else if (state === 'disconnected') {
          scheduleWebRtcRecovery();
        }
      };
      peer.addEventListener('connectionstatechange', updateConnectionState);
      peer.addEventListener('iceconnectionstatechange', updateConnectionState);
      startupStage = 'offer';
      const offer = await peer.createOffer();
      await peer.setLocalDescription(offer);
      startupStage = 'ice_gathering';
      await waitForIceGatheringComplete(peer);
      if (generation !== webRtcGeneration || peer !== webRtcPeer) return;
      startupStage = 'session_open';
      const response = await api('/api/webrtc/session?codec=auto', {
        method: 'POST',
        headers: {'Content-Type': 'application/sdp', 'Accept': 'application/json'},
        body: peer.localDescription.sdp
      });
      if (response.status === 401) {
        invalidateCredential(t('connectionExpired'));
        return;
      }
      if (!response.ok) throw new Error(`signaling HTTP ${response.status}`);
      const opened = await response.json();
      if (!WEBRTC_SESSION_PATTERN.test(String(opened.sessionId || ''))) {
        throw new Error('invalid WebRTC session');
      }
      webRtcSessionId = opened.sessionId;
      startupStage = 'answer';
      const answer = await waitForWebRtcAnswer(opened, generation);
      if (generation !== webRtcGeneration || peer !== webRtcPeer) return;
      await peer.setRemoteDescription({type: 'answer', sdp: answer.answerSdp});
      if (generation !== webRtcGeneration || peer !== webRtcPeer) return;
      startupStage = 'connection';
      await waitForWebRtcConnection(peer, generation);
      if (generation === webRtcGeneration && peer === webRtcPeer) {
        delete pad.dataset.navonwebWebRtcFailureStage;
        updateConnectionState();
      }
    } catch (error) {
      if (generation === webRtcGeneration) {
        const failureReason = webRtcFailureReason(error);
        await publishIceFailure(peer, generation, startupStage, failureReason);
        pad.dataset.navonwebWebRtcFailureStage = startupStage;
        console.warn(
          `NAVONWEB_WEBRTC_START_FAILED stage=${startupStage} ` +
            `reason=${failureReason} error=${error && error.name || 'Error'}`
        );
        failWebRtc();
      }
    } finally {
      if (generation === webRtcGeneration) webRtcStarting = false;
    }
  }

  function renderAndroidAutoStatus(connection, projection = null) {
    const wasInteractive = androidAutoInteractive;
    const wasTouchReady = androidAutoTouchReady;
    const state = connection && String(connection.state || '').toUpperCase();
    androidAutoInteractive = state === 'CONNECTED';
    androidAutoTouchReady = androidAutoInteractive && Boolean(connection && connection.touchReady);
    if (wasTouchReady && !androidAutoTouchReady) resetTouchTransport(false, true);
    if (!wasTouchReady && androidAutoTouchReady) enqueueRecoveryCancelIfNeeded();
    pad.classList.toggle('aa-unavailable', !androidAutoInteractive);
    if (!androidAutoInteractive) {
      hidePremiumPrompt();
      const transportReconnectInProgress = state === 'RECONNECTING';
      microphoneCaptureRequested = false;
      if (!microphoneCaptureIsTerminal() && microphoneState !== 'android_auto_unavailable') {
        stopMicrophoneCapture('android_auto_unavailable');
      }
      stopAudioStreams(true);
      cancelWebRtcRecovery();
      cancelOutputAudioWebRtcRecovery(false);
      // The coded frame size is unchanged for a viewport-margin reconnect, so
      // preserve the active WebRTC session while Android Auto renegotiates.
      if (!transportReconnectInProgress && (webRtcPeer || webRtcStarting)) resetWebRtc(true);
      cancelPointerInteraction();
      streamState.textContent = state === 'RECONNECTING'
        ? t('androidAutoReconnecting')
        : t('androidAutoWaiting');
      syncLocalNetworkPermissionPanel();
      return;
    }
    if (!pad.classList.contains('frame-ready') && !pad.classList.contains('webrtc-ready')) {
      streamState.textContent = t('videoWaiting');
    }
    if (!wasInteractive) cancelWebRtcRecovery(true);
    if (!webRtcPeer && !webRtcStarting) scheduleWebRtcRecovery();
    scheduleOutputAudioWebRtcRecovery();
    if (audioUnlocked) startAudioStreams();
    ensureMicrophoneCapture();
    maybeShowPremiumPrompt(wasInteractive, projection);
    syncLocalNetworkPermissionPanel();
  }

  function updateMicrophoneCaptureRequest(microphone) {
    const requested = Boolean(microphone && microphone.captureRequested);
    if (requested !== microphoneCaptureRequested) {
      // Discard audio queued for the previous AA microphone state. The one
      // request already in flight may finish, then the latest state wins.
      microphoneQueue.length = 0;
    }
    microphoneCaptureRequested = requested;
    if (!requested) {
      if (microphoneMediaStream || microphoneTrack || microphoneAudioContext) {
        stopMicrophoneCapture('ready');
      } else if (microphonePermissionPrimed) {
        updateMicrophoneState('ready');
      }
      enqueueMicrophoneReadyHeartbeat();
      scheduleMicrophoneReadyHeartbeat();
    } else {
      if (microphoneReadyHeartbeatTimer) window.clearTimeout(microphoneReadyHeartbeatTimer);
      microphoneReadyHeartbeatTimer = 0;
      if (microphoneAudioContext && microphoneAudioContext.state === 'suspended') {
        microphoneAudioContext.resume().catch(() => null);
      } else {
        ensureMicrophoneCapture();
      }
    }
  }

  function statusPollingEligible() {
    return Boolean(browserCredential) && pageActive && !document.hidden;
  }

  function statusPollDelayMillis(failureCount, randomSample = Math.random()) {
    const failures = Number.isFinite(failureCount)
      ? Math.max(0, Math.floor(failureCount))
      : 0;
    const boundedSample = Number.isFinite(randomSample)
      ? Math.min(1, Math.max(0, randomSample))
      : 0;
    if (failures === 0) {
      return Math.round(
        STATUS_HEALTHY_MIN_INTERVAL_MILLIS +
          (STATUS_HEALTHY_MAX_INTERVAL_MILLIS - STATUS_HEALTHY_MIN_INTERVAL_MILLIS) *
            boundedSample
      );
    }
    const maximumDelay = Math.min(
      STATUS_FAILURE_BASE_INTERVAL_MILLIS * (2 ** Math.min(failures, 4)),
      STATUS_FAILURE_MAX_INTERVAL_MILLIS
    );
    return Math.max(1, Math.round(maximumDelay * (0.5 + boundedSample * 0.5)));
  }

  function startStatusPolling() {
    if (!statusPollingEligible()) return;
    let started = false;
    if (!statusPolling) {
      statusPolling = true;
      statusGeneration += 1;
      statusFailureCount = 0;
      started = true;
    }
    if (started) {
      const startedGeneration = statusGeneration;
      // Probe immediately so the independent ten-second repair countdown starts at page resume,
      // not after the healthy polling jitter. pollStatus coalesces concurrent callers.
      pollStatus().finally(() => {
        if (statusPolling && startedGeneration === statusGeneration) scheduleStatusPoll();
      });
      return;
    }
    scheduleStatusPoll();
  }

  function scheduleStatusPoll() {
    if (!statusPolling || statusTimer || statusPollTask || !statusPollingEligible()) return;
    const scheduledGeneration = statusGeneration;
    statusTimer = window.setTimeout(async () => {
      statusTimer = 0;
      if (!statusPolling || scheduledGeneration !== statusGeneration || !statusPollingEligible()) return;
      await pollStatus();
      if (statusPolling && scheduledGeneration === statusGeneration) scheduleStatusPoll();
    }, statusPollDelayMillis(statusFailureCount));
  }

  function stopStatusPolling() {
    statusPolling = false;
    statusGeneration += 1;
    statusFailureCount = 0;
    if (statusTimer) window.clearTimeout(statusTimer);
    statusTimer = 0;
    const task = statusPollTask;
    statusPollTask = null;
    if (task) task.controller.abort();
  }

  function pollStatus(options = {}) {
    if (!browserCredential) return Promise.resolve(false);
    const credential = browserCredential;
    const generation = statusGeneration;
    if (statusPollTask &&
        statusPollTask.credential === credential &&
        statusPollTask.generation === generation) return statusPollTask.promise;

    const task = {
      credential,
      generation,
      controller: new AbortController(),
      promise: null
    };
    statusPollTask = task;
    task.promise = performStatusPoll(options, task).finally(() => {
      if (statusPollTask === task) statusPollTask = null;
    });
    return task.promise;
  }

  function statusPollTaskIsCurrent(task) {
    return statusPollTask === task &&
      statusGeneration === task.generation &&
      browserCredential === task.credential;
  }

  async function performStatusPoll(options, task) {
    // Arm at the beginning of the request so its own timeout is included in the ten-second
    // recovery window. Repeated polls cannot reset an already running countdown.
    scheduleRepairPairingAction();
    const timeout = window.setTimeout(() => task.controller.abort(), STATUS_REQUEST_TIMEOUT_MILLIS);
    try {
      const response = await api('/api/status', {signal: task.controller.signal}, task.credential);
      if (!statusPollTaskIsCurrent(task)) return false;
      if (response.status === 401) {
        invalidateCredential(options.automatic
          ? t('savedConnectionExpired')
          : t('connectionExpiredPhone'));
        return false;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      // A valid status response proves that the remembered route still reaches the phone.
      // Always disarm the manual repair action before processing optional response fields.
      cancelRepairPairingCountdown();
      const data = await response.json();
      if (!statusPollTaskIsCurrent(task)) return false;
      const recoveredFromStatusFailure = statusFailureCount > 0;
      statusFailureCount = 0;
      applyDynamicAspectEntitlement(data.projection);
      const activeProfile = data.projection && normalizeProjectionProfile(data.projection.activeProfile);
      if (activeProfile) applyProjectionGeometry(activeProfile);
      const activeViewport = data.projection && data.projection.viewport &&
        normalizeProjectionViewport(data.projection.viewport.activeLayout);
      if (activeViewport) applyProjectionViewportGeometry(activeViewport);
      // A status mismatch can be caused by another trusted browser or by AA reconnecting.
      // Resize/fullscreen observers are the sole source of viewport requests, preventing a
      // status poll from turning that temporary mismatch into an endless reconnect loop.
      updateMicrophoneCaptureRequest(data.microphone);
      renderAndroidAutoStatus(data.androidAuto, data.projection);
      if (recoveredFromStatusFailure) requestViewportControlReclaim();
      showAuthenticatedView(true);
      ensureNoticesLoaded();
      startFramePolling();
      ensureWebRtcCapabilities();
      if (androidAutoInteractive && !webRtcPeer && !webRtcStarting) scheduleWebRtcRecovery();
      return true;
    } catch (_) {
      if (!statusPollTaskIsCurrent(task)) return false;
      showAuthenticatedView(true);
      statusFailureCount += 1;
      scheduleRepairPairingAction();
      // Keep the established media pipeline through one transient request
      // failure. Two consecutive failures expose the disconnected state.
      if (statusFailureCount >= 2) renderAndroidAutoStatus(null);
      streamState.textContent = t('serverWaiting');
      return false;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function invalidateCredential(message) {
    resetPremiumPromptSession();
    resetNoticeSession();
    stopMicrophoneCapture('credential_invalid');
    stopAudioStreams(true);
    resetWebRtc(false);
    outputAudioWebRtcRecoveryRequired = false;
    outputAudioWebRtcUnsupported = false;
    cancelOutputAudioWebRtcRecovery(true);
    browserCredential = '';
    webRtcCapabilitiesPromise = null;
    webRtcServerCapabilities = null;
    forgetCredential();
    stopStatusPolling();
    stopFramePolling(true);
    resetTouchTransport(false);
    touchRecoveryCancelPending = false;
    touchRecoveryCancelInFlight = false;
    androidAutoInteractive = false;
    androidAutoTouchReady = false;
    microphoneCaptureRequested = false;
    applyDynamicAspectEntitlement(null);
    pad.classList.add('aa-unavailable');
    code.value = '';
    pair.disabled = false;
    setPairStatus(message, true);
    setTheaterMode(false);
    if (viewerOwnsFullscreen()) document.exitFullscreen().catch(() => null);
    showAuthenticatedView(false);
    code.focus();
  }

  function resetCloudRelayRouteStateForRepair() {
    // The secure route cookie is HttpOnly and cannot be erased by page script. Closing the
    // socket prevents further use of the old route, and requiring a successful bootstrap
    // makes the next code overwrite that cookie before any phone API request is sent.
    setFreshCloudRelayRouteRequirement(true);
    if (cloudRelayTransport) cloudRelayTransport.close();
    cloudRelayTransport = null;
  }

  function restartPairingFlow() {
    resetCloudRelayRouteStateForRepair();
    invalidateCredential(t('repairPairingHint'));
  }

  async function bootstrapCloudRelayRoute(pairingCode) {
    if (!CLOUD_RELAY_MODE || CLOUD_RELAY_CONFIG.roomId) return null;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), CLOUD_RELAY_REQUEST_TIMEOUT_MILLIS);
    try {
      const response = await fetch(CLOUD_RELAY_CONFIG.bootstrapPairUrl, {
        method: 'POST',
        mode: 'cors',
        credentials: 'include',
        cache: 'no-store',
        headers: {'Content-Type': 'text/plain;charset=UTF-8'},
        body: pairingCode,
        signal: controller.signal
      });
      if (response.ok) {
        setFreshCloudRelayRouteRequirement(false);
        if (cloudRelayTransport) cloudRelayTransport.close();
        cloudRelayTransport = null;
      }
      return response;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  async function pairWithCode(candidate) {
    const pairingCode = candidate.trim();
    if (!/^\d{8}$/.test(pairingCode)) {
      setPairStatus(t('eightDigitRequired'), true);
      code.focus();
      return;
    }
    if (pairingInFlight) return;
    pairingInFlight = true;
    pair.disabled = true;
    setPairStatus(t('connecting'));
    let bootstrapRouteMissing = false;
    let bootstrapRouteExpired = false;
    let pairingFailureStage = 'bootstrap';
    try {
      const bootstrapResponse = await bootstrapCloudRelayRoute(pairingCode);
      if (bootstrapResponse && bootstrapResponse.status === 404) {
        // A route cookie may already have been issued before a transient failure between
        // bootstrap and /api/pair. Try that HttpOnly route before rejecting the code.
        bootstrapRouteMissing = true;
        console.warn('NAVONWEB_PAIR_ROUTE_MISSING status=404');
      }
      if (bootstrapResponse && bootstrapResponse.status === 410) {
        bootstrapRouteExpired = true;
        console.warn('NAVONWEB_PAIR_ROUTE_EXPIRED status=410');
      }
      if (bootstrapResponse && freshCloudRelayRouteRequired &&
          (bootstrapRouteMissing || bootstrapRouteExpired)) {
        setPairStatus(bootstrapRouteExpired ? t('expiredCode') : t('invalidCode'), true);
        return;
      }
      if (bootstrapResponse && bootstrapResponse.status === 429) {
        setPairStatus(t('retryLater'), true);
        return;
      }
      if (bootstrapResponse && !bootstrapResponse.ok &&
          !bootstrapRouteMissing && !bootstrapRouteExpired) {
        console.warn(`NAVONWEB_PAIR_FAILED stage=bootstrap status=${bootstrapResponse.status}`);
        throw new Error(`bootstrap HTTP ${bootstrapResponse.status}`);
      }
      pairingFailureStage = 'pair';
      const response = await api('/api/pair', {
        method: 'POST',
        headers: {'X-Pairing-Code': pairingCode},
        cache: 'no-store'
      }, '');
      if (response.status === 401) {
        console.warn('NAVONWEB_PAIR_FAILED stage=pair status=401');
        setPairStatus(t('invalidCode'), true);
        return;
      }
      if (response.status === 410) {
        console.warn('NAVONWEB_PAIR_FAILED stage=pair status=410');
        setPairStatus(t('expiredCode'), true);
        return;
      }
      if (response.status === 429) {
        console.warn('NAVONWEB_PAIR_FAILED stage=pair status=429');
        setPairStatus(t('retryLater'), true);
        return;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      const issuedCredential = String(data.browserCredential || '');
      if (!CREDENTIAL_PATTERN.test(issuedCredential)) throw new Error('invalid browser credential');
      stopAudioStreams(true);
      resetWebRtc(true);
      stopFramePolling(true);
      resetPremiumPromptSession();
      resetNoticeSession();
      browserCredential = issuedCredential;
      cancelWebRtcRecovery(true);
      webRtcCapabilitiesPromise = null;
      webRtcServerCapabilities = null;
      rememberCredential(issuedCredential);
      code.value = '';
      showAuthenticatedView(true);
      startStatusPolling();
      await pollStatus();
    } catch (error) {
      const errorName = error && typeof error.name === 'string' ? error.name : 'Error';
      console.warn(`NAVONWEB_PAIR_FAILED stage=${pairingFailureStage} error=${errorName}`);
      setPairStatus(
        bootstrapRouteExpired
          ? t('expiredCode')
          : bootstrapRouteMissing ? t('invalidCode') : t('unableToConnect'),
        true
      );
    } finally {
      pairingInFlight = false;
      pair.disabled = false;
    }
  }

  async function connectRemembered(credential) {
    resetPremiumPromptSession();
    resetNoticeSession();
    browserCredential = credential;
    showAuthenticatedView(true);
    if (!statusPollingEligible()) return;
    startStatusPolling();
    await pollStatus({automatic: true});
  }

  function projectionContentRect() {
    let rect = projectionContent.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) {
      syncProjectionContentLayout();
      rect = projectionContent.getBoundingClientRect();
    }
    return rect;
  }

  function point(event, clampToContent = true) {
    const rect = projectionContentRect();
    if (rect.width <= 0 || rect.height <= 0) return null;
    if (!clampToContent && (
      event.clientX < rect.left || event.clientX > rect.left + rect.width ||
      event.clientY < rect.top || event.clientY > rect.top + rect.height
    )) return null;
    return {
      x: Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)),
      y: Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height))
    };
  }

  function enqueueTouch(phase, position, options = {}) {
    if (!browserCredential || !androidAutoInteractive || !androidAutoTouchReady) return;
    const request = Object.freeze({
      phase,
      position: Object.freeze({x: position.x, y: position.y}),
      credential: browserCredential,
      generation: touchQueueGeneration,
      recoveryCancel: Boolean(options.recoveryCancel)
    });
    if (phase === 'move') {
      // A slow LAN must not let old MOVE events delay the final UP/CANCEL indefinitely.
      pendingMoveTouch = request;
    } else {
      if (phase === 'up' || phase === 'cancel') pendingMoveTouch = null;
      touchControlQueue.push(request);
    }
    pumpTouchQueue();
  }

  function nextTouchRequest() {
    if (touchControlQueue.length > 0) return touchControlQueue.shift();
    const request = pendingMoveTouch;
    pendingMoveTouch = null;
    return request;
  }

  function pumpTouchQueue() {
    const generation = touchQueueGeneration;
    if (touchPumpGeneration === generation) return;
    touchPumpGeneration = generation;
    (async () => {
      while (generation === touchQueueGeneration) {
        const request = nextTouchRequest();
        if (!request) return;
        await sendTouchRequest(request);
      }
    })().catch(() => null).finally(() => {
      if (touchPumpGeneration !== generation) return;
      touchPumpGeneration = null;
      if (generation === touchQueueGeneration &&
          (touchControlQueue.length > 0 || pendingMoveTouch)) pumpTouchQueue();
    });
  }

  async function sendTouchRequest(request) {
    if (request.generation !== touchQueueGeneration ||
        !request.credential || browserCredential !== request.credential ||
        !androidAutoInteractive || !androidAutoTouchReady) return;
    const query = new URLSearchParams({
      phase: request.phase,
      x: String(request.position.x),
      y: String(request.position.y),
      pointerId: '0',
      // point() is relative to the cropped, visible content. OpenAutoCoordinator performs the
      // only conversion from this space through the negotiated AA margins.
      coordinateSpace: 'content'
    });
    const controller = new AbortController();
    activeTouchAbortController = controller;
    const timeout = window.setTimeout(() => controller.abort(), TOUCH_REQUEST_TIMEOUT_MILLIS);
    let response = null;
    try {
      response = await api(
        `/api/touch?${query}`,
        {method: 'POST', signal: controller.signal},
        request.credential
      );
    } catch (_) {
      response = null;
    } finally {
      window.clearTimeout(timeout);
      if (activeTouchAbortController === controller) activeTouchAbortController = null;
    }
    if (request.generation !== touchQueueGeneration) return;
    if (!response) {
      // The peer may have accepted the event before the response was lost. Reset the
      // bounded queue and send one CANCEL after status confirms input is ready again.
      if (!request.recoveryCancel) touchRecoveryCancelPending = true;
      resetTouchTransport(true);
      return;
    }
    if (response.status === 202) {
      if (request.recoveryCancel) {
        touchRecoveryCancelPending = false;
        touchRecoveryCancelInFlight = false;
      }
      return;
    }
    if (response.status === 401 && browserCredential === request.credential) {
      invalidateCredential(t('connectionExpiredPhone'));
      return;
    }
    if (response.status === 409) {
      const rejected = await response.json().catch(() => ({}));
      const reason = typeof rejected.reason === 'string' ? rejected.reason : 'unknown';
      setDevelopmentViewportDiagnostic('touch-rejection', `${reason}:${request.phase}`);
    }
    // A video frame can precede AA input binding by a few status polls after a viewport
    // reconnect. For a gesture that may already have reached AA, retain recovery CANCEL
    // until the server explicitly accepts it.
    if (request.phase !== 'down' && !request.recoveryCancel) touchRecoveryCancelPending = true;
    androidAutoTouchReady = false;
    resetTouchTransport(false);
  }

  function enqueueRecoveryCancelIfNeeded() {
    if (!touchRecoveryCancelPending || touchRecoveryCancelInFlight ||
        !androidAutoInteractive || !androidAutoTouchReady) return;
    touchRecoveryCancelInFlight = true;
    enqueueTouch('cancel', lastPointerPosition, {recoveryCancel: true});
  }

  function updatePointer(phase, position) {
    if (!position) return;
    lastPointerPosition = position;
    enqueueTouch(phase, position);
  }

  function cancelActivePointer() {
    if (activePointerId === null) return;
    activePointerId = null;
    updatePointer('cancel', lastPointerPosition);
  }

  function clearPinchPointers() {
    touchPointers.clear();
    pinchGesture = null;
    suppressAndroidAutoTouch = false;
  }

  function cancelPointerInteraction() {
    cancelActivePointer();
    clearPinchPointers();
  }

  function resetTouchTransport(awaitFreshStatus, recoverActivePointer = false) {
    if (recoverActivePointer && activePointerId !== null) touchRecoveryCancelPending = true;
    touchQueueGeneration += 1;
    if (activeTouchAbortController) activeTouchAbortController.abort();
    activeTouchAbortController = null;
    touchControlQueue.length = 0;
    pendingMoveTouch = null;
    touchPumpGeneration = null;
    touchRecoveryCancelInFlight = false;
    activePointerId = null;
    clearPinchPointers();
    if (awaitFreshStatus) androidAutoTouchReady = false;
  }

  function touchPoint(event) {
    return {x: event.clientX, y: event.clientY};
  }

  function pointerDistance(left, right) {
    return Math.hypot(left.x - right.x, left.y - right.y);
  }

  function beginPinchGesture() {
    const entries = Array.from(touchPointers.entries()).slice(0, 2);
    if (entries.length !== 2) return;
    const distance = pointerDistance(entries[0][1], entries[1][1]);
    if (!Number.isFinite(distance) || distance < 1) return;
    cancelActivePointer();
    suppressAndroidAutoTouch = true;
    pinchGesture = {
      pointerIds: [entries[0][0], entries[1][0]],
      initialDistance: distance,
      initiallyExpanded: expandedViewActive(),
      intent: null,
      applied: false
    };
  }

  function updatePinchGesture() {
    if (!pinchGesture || pinchGesture.applied) return;
    const left = touchPointers.get(pinchGesture.pointerIds[0]);
    const right = touchPointers.get(pinchGesture.pointerIds[1]);
    if (!left || !right) return;
    const scale = pointerDistance(left, right) / pinchGesture.initialDistance;
    if (!pinchGesture.initiallyExpanded && scale >= PINCH_EXPAND_SCALE) {
      pinchGesture.intent = 'expand';
    } else if (pinchGesture.initiallyExpanded && scale <= PINCH_COLLAPSE_SCALE) {
      pinchGesture.intent = 'collapse';
    }
  }

  function applyPinchIntentFromGesture() {
    if (!pinchGesture || pinchGesture.applied || !pinchGesture.intent) return;
    pinchGesture.applied = true;
    setExpandedView(pinchGesture.intent === 'expand').catch(() => null);
  }

  function finishGesturePointer(event, cancelled) {
    if (!touchPointers.has(event.pointerId)) return false;
    const consumed = suppressAndroidAutoTouch;
    if (consumed && !cancelled) {
      touchPointers.set(event.pointerId, touchPoint(event));
      updatePinchGesture();
      applyPinchIntentFromGesture();
    }
    touchPointers.delete(event.pointerId);
    if (touchPointers.size < 2) pinchGesture = null;
    if (touchPointers.size === 0) suppressAndroidAutoTouch = false;
    return consumed;
  }

  pairingForm.addEventListener('submit', event => {
    event.preventDefault();
    unlockAudio();
    pairWithCode(code.value);
  });
  fullscreenButton.addEventListener('click', () => {
    unlockAudio();
    toggleFullscreen();
  });
  localNetworkAllow.addEventListener('click', () => {
    unlockAudio();
    requestLocalNetworkAccess().catch(() => syncLocalNetworkPermissionPanel());
  });
  mediaPermissionAllow.addEventListener('click', () => {
    if (microphoneState === 'permission_denied') updateMicrophoneState('permission_retry');
    unlockAudio();
    ensureMicrophoneCapture();
  });
  repairPairingButton.addEventListener('click', () => {
    unlockAudio();
    restartPairingFlow();
  });
  premiumPromptConfirm.addEventListener('click', () => {
    unlockAudio();
    const dismissPermanently = premiumPromptDismiss.checked;
    hidePremiumPrompt();
    if (dismissPermanently) rememberPremiumPromptDismissal();
  });

  pad.addEventListener('pointerdown', event => {
    unlockAudio();
    if (event.pointerType === 'touch') {
      touchPointers.set(event.pointerId, touchPoint(event));
      try { pad.setPointerCapture(event.pointerId); } catch (_) { /* capture is best effort */ }
      if (touchPointers.size >= 2) {
        event.preventDefault();
        if (!pinchGesture) beginPinchGesture();
        return;
      }
      if (suppressAndroidAutoTouch) {
        event.preventDefault();
        return;
      }
    }
    if (!browserCredential || !androidAutoInteractive || !androidAutoTouchReady ||
        activePointerId !== null) return;
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    const position = point(event, false);
    if (!position) return;
    event.preventDefault();
    activePointerId = event.pointerId;
    try { pad.setPointerCapture(event.pointerId); } catch (_) { /* capture is best effort */ }
    updatePointer('down', position);
  });
  pad.addEventListener('pointermove', event => {
    if (event.pointerType === 'touch' && touchPointers.has(event.pointerId)) {
      touchPointers.set(event.pointerId, touchPoint(event));
      if (suppressAndroidAutoTouch) {
        event.preventDefault();
        updatePinchGesture();
        return;
      }
    }
    if (event.pointerId !== activePointerId) return;
    event.preventDefault();
    if (performance.now() - lastMove < 24) return;
    lastMove = performance.now();
    updatePointer('move', point(event));
  });
  pad.addEventListener('pointerup', event => {
    if (finishGesturePointer(event, false)) {
      event.preventDefault();
      return;
    }
    if (event.pointerId !== activePointerId) return;
    event.preventDefault();
    updatePointer('up', point(event));
    activePointerId = null;
  });
  pad.addEventListener('pointercancel', event => {
    if (finishGesturePointer(event, true)) {
      event.preventDefault();
      return;
    }
    if (event.pointerId !== activePointerId) return;
    event.preventDefault();
    updatePointer('cancel', point(event));
    activePointerId = null;
  });
  pad.addEventListener('lostpointercapture', event => {
    if (touchPointers.has(event.pointerId)) {
      touchPointers.delete(event.pointerId);
      if (touchPointers.size < 2) pinchGesture = null;
      if (touchPointers.size === 0) suppressAndroidAutoTouch = false;
    }
    if (event.pointerId === activePointerId) cancelActivePointer();
  });
  window.addEventListener('blur', cancelPointerInteraction);
  window.addEventListener('resize', () => {
    cancelActivePointer();
    scheduleViewportLayoutSync();
  });
  document.addEventListener('fullscreenchange', () => {
    cancelActivePointer();
    if (viewerOwnsFullscreen() && theaterMode) {
      theaterMode = false;
      document.body.classList.remove('theater-mode');
    }
    syncFullscreenState();
  });
  document.addEventListener('fullscreenerror', () => {
    const generation = fullscreenEntryPendingGeneration;
    if (generation !== 0 && generation === expandedViewRequestGeneration && !viewerOwnsFullscreen()) {
      fullscreenEntryPendingGeneration = 0;
      setTheaterMode(true);
    }
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && theaterMode) {
      event.preventDefault();
      setTheaterMode(false);
    }
  });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      cancelRepairPairingCountdown();
      stopStatusPolling();
      if (!microphoneCaptureIsTerminal()) stopMicrophoneCapture('visibility_hidden');
      stopAudioStreams(false);
      resetTouchTransport(true, true);
      cancelWebRtcRecovery();
      cancelOutputAudioWebRtcRecovery(false);
      stopFramePolling(false);
    } else if (statusPollingEligible()) {
      requestViewportControlReclaim();
      startStatusPolling();
      refreshLocalNetworkPermission().then(() => {
        if (androidAutoInteractive) scheduleWebRtcRecovery();
      });
      if (audioContext && audioContext.state !== 'closed') {
        const visibleAudioContext = audioContext;
        visibleAudioContext.resume().then(() => {
          if (visibleAudioContext === audioContext) {
            audioUnlocked = visibleAudioContext.state === 'running';
            syncMediaPermissionPanel();
          }
        }).catch(() => {
          if (visibleAudioContext === audioContext) {
            audioUnlocked = false;
            syncMediaPermissionPanel();
          }
        });
      }
      startFramePolling();
      pollStatus().then(connected => {
        if (connected && androidAutoInteractive) {
          scheduleWebRtcRecovery();
          scheduleOutputAudioWebRtcRecovery();
        }
      });
    }
  });
  function resumeSameOriginConnection() {
    if (!statusPollingEligible()) return;
    scheduleViewportLayoutSync();
    requestViewportControlReclaim();
    startStatusPolling();
    cancelWebRtcRecovery(true);
    pollStatus().then(connected => {
      if (connected && androidAutoInteractive) scheduleWebRtcRecovery();
    });
  }

  function suspendUnavailableSameOriginConnection() {
    stopViewportReporting();
    stopStatusPolling();
    if (!microphoneCaptureIsTerminal()) stopMicrophoneCapture('offline');
    stopAudioStreams(false);
    cancelWebRtcRecovery();
    cancelOutputAudioWebRtcRecovery(false);
    renderAndroidAutoStatus(null);
    streamState.textContent = t('serverWaiting');
    scheduleRepairPairingAction();
  }

  window.addEventListener('online', resumeSameOriginConnection);
  window.addEventListener('offline', () => {
    if (CLOUD_RELAY_MODE && webRtcPeer && isWebRtcConnected(webRtcPeer)) return;
    // Chromium may report "offline" when public Internet is unavailable even though the
    // phone-hosted LAN endpoint is healthy. Probe same-origin before suspending local media.
    api('/health', {cache: 'no-store'}, '')
      .then(response => {
        if (response.ok) resumeSameOriginConnection();
        else suspendUnavailableSameOriginConnection();
      })
      .catch(suspendUnavailableSameOriginConnection);
  });
  window.addEventListener('pagehide', () => {
    pageActive = false;
    cancelRepairPairingCountdown();
    hidePremiumPrompt();
    cancelNoticeRequestForRetry();
    resetTouchTransport(true, true);
    stopViewportReporting();
    stopDevelopmentTeslaCycle();
    stopStatusPolling();
    if (!microphoneCaptureIsTerminal()) stopMicrophoneCapture('pagehide');
    stopAudioStreams(true);
    if (audioContext && audioContext.state !== 'closed') audioContext.close().catch(() => null);
    audioContext = null;
    audioUnlocked = false;
    syncMediaPermissionPanel();
    hideFullscreenHint();
    setTheaterMode(false);
    cancelWebRtcRecovery();
    stopFramePolling(false);
    resetWebRtc(true);
    if (cloudRelayTransport) {
      cloudRelayTransport.close();
      cloudRelayTransport = null;
    }
  });
  window.addEventListener('pageshow', event => {
    pageActive = true;
    startDevelopmentTeslaCycle();
    syncFullscreenState();
    scheduleViewportLayoutSync();
    requestViewportControlReclaim();
    refreshLocalNetworkPermission().then(() => {
      if (statusPollingEligible() && androidAutoInteractive) {
        scheduleWebRtcRecovery();
        scheduleOutputAudioWebRtcRecovery();
      }
    });
    if (statusPollingEligible()) startStatusPolling();
    if (event.persisted && statusPollingEligible()) {
      pollStatus().then(connected => {
        if (connected && androidAutoInteractive) {
          scheduleWebRtcRecovery();
          scheduleOutputAudioWebRtcRecovery();
        }
      });
    }
  });

  applyDocumentLocale();
  pad.style.setProperty('--projection-aspect-ratio', '5 / 3');
  pad.classList.add('aa-unavailable');
  pad.dataset.navonwebRtcApi = typeof RTCPeerConnection === 'function' ? 'available' : 'unavailable';
  pad.dataset.navonwebBrowserCodecCount = String(browserCodecCapabilities.size);
  pad.dataset.navonwebLocalNetworkPermission = localNetworkPermissionState;
  installDevelopmentViewport();
  installViewportObservers();
  syncFullscreenState();
  refreshLocalNetworkPermission().then(() => {
    if (statusPollingEligible() && androidAutoInteractive) scheduleWebRtcRecovery();
  });
  const rememberedCredential = loadRememberedCredential();
  if (rememberedCredential) connectRemembered(rememberedCredential);
  else showAuthenticatedView(false);
})();
