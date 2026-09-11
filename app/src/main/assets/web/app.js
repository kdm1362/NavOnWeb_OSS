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
    : 'navonweb.browserCredential.v1';
  const LEGACY_STORAGE_KEY_SUFFIX = CLOUD_RELAY_MODE && CLOUD_RELAY_CONFIG.roomId
    ? `.browserCredential.v1.${CLOUD_RELAY_CONFIG.roomId}`
    : '.browserCredential.v1';
  const PREMIUM_PROMPT_DISMISSED_KEY = 'navonweb.premiumPromptDismissed.v1';
  const PRESENTATION_PREFERENCES_KEY = 'navonweb.presentationPreferences.v1';
  const PRESENTATION_GUIDE_DISMISSED_KEY = 'navonweb.presentationGuideDismissed.v1';
  const FRESH_CLOUD_ROUTE_REQUIRED_KEY = 'navonweb.freshCloudRouteRequired.v1';
  const CREDENTIAL_PATTERN = /^[A-Za-z0-9_-]{43}$/;
  const FRAME_INTERVAL_MILLIS = 200;
  // PCM16LE decode fast path. Practically every supported browser is little-endian, but the
  // DataView fallback below keeps a big-endian host correct.
  const PLATFORM_LITTLE_ENDIAN = new Uint8Array(new Uint16Array([1]).buffer)[0] === 1;
  const MAX_FRAME_BYTES = 2 * 1024 * 1024;
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
  const WEBRTC_VIDEO_STATS_INTERVAL_MILLIS = 5000;
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
  const CONTROL_WEBRTC_OPEN_TIMEOUT_MILLIS = AUDIO_WEBRTC_OPEN_TIMEOUT_MILLIS;
  const CONTROL_WEBRTC_REQUEST_TIMEOUT_MILLIS = 15000;
  const DEVELOPMENT_VIEWPORT_QUERY = 'navonweb-dev-viewport';
  const DEVELOPMENT_NARROW_DRIVING_MODE = 'narrow-driving';
  const DEVELOPMENT_NARROW_CYCLE_MODE = 'narrow-cycle';
  const DEVELOPMENT_NARROW_WIDTH_SCALE = 0.68;
  const DEVELOPMENT_NARROW_CYCLE_INTERVAL_MILLIS = 12000;
  const VIEWPORT_REPORT_SETTLE_MILLIS = 850;
  const VIEWPORT_REPORT_RETRY_MILLIS = 1500;
  const VIEWPORT_CONTROLLER_BUSY_RETRY_MILLIS = 5000;
  const VIEWPORT_REPORT_TIMEOUT_MILLIS = 5000;
  const VIEWPORT_REPORT_MAX_TIMEOUT_RETRIES = 2;
  const VIEWPORT_AUTHORITY_NOTICE_EXPANDED_MILLIS = 3000;
  const MIN_VIEWPORT_DEVICE_PIXEL_RATIO = 0.5;
  const MAX_VIEWPORT_DEVICE_PIXEL_RATIO = 8;
  const PINCH_EXPAND_SCALE = 1.18;
  const PINCH_COLLAPSE_SCALE = 0.82;
  const PRESENTATION_PAN_LOCK_CSS_PIXELS = 10;
  const PRESENTATION_PAN_DIRECTION_RATIO = 1.35;
  const PRESENTATION_SNAP_ENTER_CSS_PIXELS = 24;
  const PRESENTATION_SNAP_EXIT_CSS_PIXELS = 40;
  const TOUCH_GESTURE_DECISION_MILLIS = 90;
  const TOUCH_GESTURE_MOVE_CSS_PIXELS = 6;
  const FULLSCREEN_HINT_DURATION_MILLIS = 5000;
  const PRESENTATION_GUIDE_AUTO_DISMISS_MILLIS = 10000;
  const PRESENTATION_GUIDE_COUNTDOWN_INTERVAL_MILLIS = 250;
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
  const CLOUD_ROUTE_STATUS_TIMEOUT_MILLIS = 3000;
  const REPAIR_PAIRING_DELAY_MILLIS = 10000;
  const CLOUD_RELAY_MAX_BODY_BYTES = 128 * 1024;
  const CLOUD_RELAY_MAX_RESPONSE_BYTES = 160 * 1024;
  const CLOUD_RELAY_REQUEST_ID_PATTERN = /^[A-Za-z0-9_-]{16,64}$/;
  const SESSION_DEVICE_ID_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;
  const SESSION_TOUCH_MARKER_RELEASE_MILLIS = 700;
  const SESSION_TOUCH_MARKER_STALE_MILLIS = 12000;
  const DYNAMIC_ASPECT_BODY_CLASS = 'navonweb-dynamic-aspect';
  const AUTHENTICATED_BODY_CLASS = 'navonweb-authenticated';
  const DEVELOPMENT_NARROW_BODY_CLASS = 'navonweb-development-narrow-driving';
  const PRESENTATION_ORIENTATIONS = Object.freeze(['auto', 'landscape', 'portrait']);
  const PRESENTATION_ALIGNMENTS = Object.freeze([
    'center', 'top', 'bottom', 'left', 'right',
    'top-left', 'top-right', 'bottom-left', 'bottom-right', 'custom'
  ]);

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
        routeStatusUrl: `${location.origin}${pathPrefix}/bootstrap/route`,
        webSocketUrl: roomId
          ? `${origin.origin}${pathPrefix}/ws/browser/${roomId}`
          : `${origin.origin}${pathPrefix}/ws/browser`
      });
    } catch (_) {
      return null;
    }
  }
  // Every locale with a full dictionary in I18N. Browser language tags resolve to the base
  // language; Traditional Chinese regions are skipped because only Simplified is bundled.
  const SUPPORTED_LOCALES = Object.freeze([
    'en', 'ko', 'es', 'pt', 'ar', 'hi', 'id', 'de', 'fr', 'ja', 'zh', 'ru', 'tr',
  ]);
  const DATE_TIME_FALLBACK_LOCALES = Object.freeze({
    en: 'en-US', ko: 'ko-KR', es: 'es-ES', pt: 'pt-BR', ar: 'ar-EG', hi: 'hi-IN', id: 'id-ID',
    de: 'de-DE', fr: 'fr-FR', ja: 'ja-JP', zh: 'zh-CN', ru: 'ru-RU', tr: 'tr-TR',
  });

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
      fullscreenExit: 'Exit fullscreen',
      fullscreenEnterLabel: 'View Android Auto in fullscreen',
      fullscreenExitLabel: 'Exit Android Auto fullscreen',
      normalViewState: 'Showing standard view',
      fullscreenViewState: 'Showing fullscreen',
      theaterViewState: 'Showing theater mode',
      fullscreenHintWindows: 'Press Esc to exit fullscreen.',
      fullscreenHintTouch: 'Pinch out/in to toggle fullscreen.',
      presentationControlsLabel: 'Projection display controls',
      presentationOrientationLabel: 'Orientation',
      presentationOrientationAuto: 'Auto',
      presentationOrientationLandscape: 'Landscape',
      presentationOrientationPortrait: 'Portrait',
      presentationAlignmentLabel: 'Alignment',
      presentationAlignmentTrigger: 'Alignment: {alignment}. Open position picker.',
      presentationAlignCenter: 'Center',
      presentationAlignTop: 'Top',
      presentationAlignBottom: 'Bottom',
      presentationAlignLeft: 'Left',
      presentationAlignRight: 'Right',
      presentationAlignTopLeft: 'Top left',
      presentationAlignTopRight: 'Top right',
      presentationAlignBottomLeft: 'Bottom left',
      presentationAlignBottomRight: 'Bottom right',
      presentationAlignCustom: 'Custom',
      presentationGuideTitle: 'Fullscreen display controls',
      presentationGuideOrientation: 'Choose Auto, Landscape, or Portrait before entering fullscreen.',
      presentationGuideAlignment: 'Before entering fullscreen, choose where letterboxed video is aligned.',
      presentationGuideMove: 'Move two fingers together to reposition video inside the available margins.',
      presentationGuideExit: 'Before movement locks, a clear inward pinch exits fullscreen.',
      presentationGuideReset: 'Press with three fingers to reset the video position.',
      presentationGuideDoNotShowAgain: "Don't show again",
      presentationGuideDismiss: 'OK',
      presentationGuideAutoDismiss: 'This guide closes automatically in {seconds}s.',
      presentationGuideAutoDismissStopped: 'Automatic closing has been stopped.',
      presentationGuideCountdownLabel: 'Time until this guide closes automatically',
      presentationReset: 'The video position was reset.',
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
      videoWaitingElapsed: 'Waiting time {time}',
      mediaPermissionPrompt: 'Tap once to enable sound and the browser microphone.',
      mediaPermissionAllow: 'Enable sound and microphone',
      mediaPermissionDenied: 'Microphone access is blocked. Allow it in this site\'s permissions, then retry.',
      localNetworkPrompt: 'Allow local network access to connect directly to your phone.',
      localNetworkDenied: "The browser could not confirm local network access. If connection fails, check this site's permission and retry.",
      localNetworkAllow: 'Allow local network',
      localNetworkRetry: 'Retry',
      androidAutoReconnecting: 'Reconnecting to Android Auto',
      serverWaiting: 'Waiting for video',
      localControlRecovering: 'Recovering the local control connection.',
      viewportMainSessionOnly: 'This device is not the main session. The projection aspect ratio and viewport follow the main session selected on your phone.',
      eightDigitRequired: 'Enter an 8-digit number.',
      connecting: 'Connecting…',
      invalidCode: 'The code is not valid.',
      codeNotRegistered: 'The phone has not registered this code yet. Confirm the service is running and that the phone and browser are on the same network, then try again.',
      expiredCode: 'The code has expired. Check the app for a new code.',
      retryLater: 'Try again in a moment.',
      unableToConnect: 'Unable to connect. Try again in a moment.',
      repairPairing: 'Get a new pairing code',
      repairPairingLabel: 'Pair with a new code',
      repairPairingHint: 'Check your phone for a new pairing code, then enter it here.',
      landingEyebrow: 'Your phone. Your browser. One connected drive.',
      landingTitle: 'Bring Android Auto to a nearby browser.',
      landingLead: 'NavOnWeb connects over your local network so you can see, hear and control the projection from a compatible browser.',
      landingHighlightsLabel: 'NavOnWeb highlights',
      landingHighlightLocal: 'Local-first connection',
      landingHighlightMedia: 'Video, sound and touch',
      landingHighlightResponsive: 'Responsive browser view',
      landingPlayStoreLabel: 'Google Play availability',
      landingPlayStoreComingSoon: 'Coming soon on Google Play',
      landingPlayStoreCta: 'Get NavOnWeb on Google Play',
      landingPlayStoreHint: 'Needs an Android Auto phone and a browser on the same local network.',
      landingPeek: 'See how NavOnWeb works',
      landingBandTitle: "Your phone's Android Auto screen, in a browser.",
      landingPremiumScreenshotAlt: 'NavOnWeb Premium service running screen',
      landingBrowserEyebrow: 'The connected experience',
      landingBrowserTitle: 'Your projection, ready in the browser.',
      landingBrowserLead: 'Once paired, the live Android Auto screen fills the available browser space with video, sound and touch controls.',
      landingBrowserScreenshotAlt: 'NavOnWeb browser session illustration with AI-recreated map imagery',
      landingBrowserCaption: 'Connected session in a browser — map imagery re-created with AI for illustration',
      landingBenefitsEyebrow: 'Designed around the screen you already have',
      landingBenefitsTitle: 'A familiar drive, without another vehicle adapter.',
      landingBenefitsLead: 'Keep the supported projection session on your phone and bring it to a compatible vehicle, tablet or laptop browser on the same network.',
      landingLocalTitle: 'Connect locally',
      landingLocalBody: 'Use your phone\'s hotspot or the same Wi-Fi network. Projection media stays on the direct browser-to-phone path.',
      landingAdaptiveTitle: 'Fits changing screens',
      landingAdaptiveBody: 'The view adapts to wide, compact and portrait browser spaces while keeping touch coordinates aligned.',
      landingRememberedTitle: 'Pair once, return quickly',
      landingRememberedBody: 'Enter the one-time code to approve a browser. That browser is remembered for faster reconnection.',
      landingStepsEyebrow: 'How it works',
      landingStepsTitle: 'Ready in a few parked minutes.',
      landingStepInstallTitle: 'Install and prepare NavOnWeb',
      landingStepInstallBody: 'The first-use guide walks you through the required phone settings.',
      landingStepServerTitle: 'Start the Android Auto™ head unit server',
      landingStepServerBody: 'NavOnWeb can take you directly to the relevant Android Auto™ settings page.',
      landingStepPairTitle: 'Open navonweb.com and pair',
      landingStepPairBody: 'Connect the browser to the phone\'s network, enter the eight-digit code above and start viewing.',
      landingSafety: 'Complete setup only while safely parked. Do not operate a phone or vehicle screen while driving.',
      landingPrivacyPolicy: 'Privacy Policy',
      landingTrademark: 'Android Auto is a trademark of Google LLC. NavOnWeb is an independent product and is not affiliated with, endorsed by or sponsored by Google or any vehicle manufacturer.'
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
      fullscreenExit: '전체 화면 종료',
      fullscreenEnterLabel: 'Android Auto 전체 화면으로 보기',
      fullscreenExitLabel: 'Android Auto 전체 화면 종료',
      normalViewState: '기본 화면으로 표시 중',
      fullscreenViewState: '전체 화면으로 표시 중',
      theaterViewState: '극장 모드로 표시 중',
      fullscreenHintWindows: 'Esc로 전체화면 종료',
      fullscreenHintTouch: '핀치 줌아웃/인으로 전체화면을 전환할 수 있습니다',
      presentationControlsLabel: '프로젝션 화면 표시 설정',
      presentationOrientationLabel: '화면 방향',
      presentationOrientationAuto: '자동',
      presentationOrientationLandscape: '가로',
      presentationOrientationPortrait: '세로',
      presentationAlignmentLabel: '정렬',
      presentationAlignmentTrigger: '정렬: {alignment}. 위치 선택 열기.',
      presentationAlignCenter: '가운데',
      presentationAlignTop: '위',
      presentationAlignBottom: '아래',
      presentationAlignLeft: '왼쪽',
      presentationAlignRight: '오른쪽',
      presentationAlignTopLeft: '왼쪽 위',
      presentationAlignTopRight: '오른쪽 위',
      presentationAlignBottomLeft: '왼쪽 아래',
      presentationAlignBottomRight: '오른쪽 아래',
      presentationAlignCustom: '사용자 위치',
      presentationGuideTitle: '전체 화면 표시 안내',
      presentationGuideOrientation: '전체 화면으로 전환하기 전에 자동, 가로 또는 세로 방향을 선택할 수 있습니다.',
      presentationGuideAlignment: '전체 화면으로 전환하기 전에 여백 안에서 영상이 놓일 위치를 선택할 수 있습니다.',
      presentationGuideMove: '전체 화면에서 두 손가락을 나란히 움직이면 여백 안에서 영상 위치가 이동합니다.',
      presentationGuideExit: '이동이 시작되기 전에 두 손가락을 확실히 오므리면 전체 화면을 종료합니다.',
      presentationGuideReset: '세 손가락을 대면 영상 표시 위치가 가운데로 돌아갑니다.',
      presentationGuideDoNotShowAgain: '다시 보지 않기',
      presentationGuideDismiss: '확인',
      presentationGuideAutoDismiss: '{seconds}초 후 자동으로 닫힙니다.',
      presentationGuideAutoDismissStopped: '자동 닫힘이 중지되었습니다.',
      presentationGuideCountdownLabel: '안내가 자동으로 닫힐 때까지 남은 시간',
      presentationReset: '영상 표시 위치를 가운데로 되돌렸습니다.',
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
      videoWaitingElapsed: '대기 시간 {time}',
      mediaPermissionPrompt: '소리와 브라우저 마이크를 사용하려면 한 번 눌러 주세요.',
      mediaPermissionAllow: '소리 및 마이크 사용',
      mediaPermissionDenied: '마이크 접근이 차단되었습니다. 이 사이트의 권한에서 허용한 뒤 다시 시도하세요.',
      localNetworkPrompt: '휴대전화에 직접 연결하려면 로컬 네트워크 접근을 허용하세요.',
      localNetworkDenied: '브라우저가 로컬 네트워크 권한을 확인하지 못했습니다. 연결되지 않으면 사이트 권한을 확인한 뒤 다시 시도하세요.',
      localNetworkAllow: '로컬 네트워크 허용',
      localNetworkRetry: '다시 시도',
      androidAutoReconnecting: 'Android Auto 다시 연결 중',
      serverWaiting: '영상 연결 대기',
      localControlRecovering: '로컬 제어 연결을 복구하는 중입니다.',
      viewportMainSessionOnly: '이 기기는 메인 세션이 아닙니다. 프로젝션 종횡비와 화면 크기는 휴대전화에서 지정한 메인 세션을 따릅니다.',
      eightDigitRequired: '8자리 숫자를 입력하세요.',
      connecting: '연결 중…',
      invalidCode: '코드가 올바르지 않습니다.',
      codeNotRegistered: '휴대전화가 이 코드를 아직 등록하지 않았습니다. 서비스가 실행 중이고 휴대전화와 브라우저가 같은 네트워크에 연결되어 있는지 확인한 뒤 다시 시도하세요.',
      expiredCode: '코드가 만료되었습니다. 앱에서 새 코드를 확인하세요.',
      retryLater: '잠시 후 다시 시도하세요.',
      unableToConnect: '연결할 수 없습니다. 잠시 후 다시 시도하세요.',
      repairPairing: '페어링 코드 다시 받기',
      repairPairingLabel: '새 코드로 다시 페어링',
      repairPairingHint: '휴대전화 앱에서 새 페어링 코드를 확인한 뒤 입력하세요.',
      landingEyebrow: '휴대전화 하나로, 익숙한 화면을 더 크게',
      landingTitle: 'Android Auto 화면을 가까운 브라우저로',
      landingLead: 'NavOnWeb은 같은 로컬 네트워크에서 호환 브라우저로 프로젝션 화면을 보고, 듣고, 조작할 수 있도록 연결합니다.',
      landingHighlightsLabel: 'NavOnWeb 주요 특징',
      landingHighlightLocal: '로컬 중심 연결',
      landingHighlightMedia: '영상·소리·터치',
      landingHighlightResponsive: '화면에 맞는 반응형 보기',
      landingPlayStoreLabel: 'Google Play 출시 안내',
      landingPlayStoreComingSoon: 'Google Play 출시 준비 중',
      landingPlayStoreCta: 'Google Play에서 NavOnWeb 받기',
      landingPlayStoreHint: 'Android Auto 휴대전화와 같은 로컬 네트워크의 브라우저가 필요합니다.',
      landingPeek: '아래에서 NavOnWeb 이용 모습을 확인하세요',
      landingBandTitle: '휴대전화의 Android Auto 화면을 브라우저에서',
      landingPremiumScreenshotAlt: 'NavOnWeb 프리미엄 서비스 실행 화면',
      landingBrowserEyebrow: '브라우저 연결 모습',
      landingBrowserTitle: '연결되면 차량용 화면이 브라우저에 바로 표시됩니다',
      landingBrowserLead: '한 번 페어링하면 Android Auto 실시간 화면이 브라우저 공간에 맞춰 표시되고 영상·소리·터치 입력을 이용할 수 있습니다.',
      landingBrowserScreenshotAlt: 'NavOnWeb 브라우저 연결 화면 예시 (AI로 재구성한 지도 이미지)',
      landingBrowserCaption: '브라우저 연결 화면 예시 — 지도 등 화면 이미지는 AI로 재구성한 연출 이미지입니다',
      landingBenefitsEyebrow: '이미 가지고 있는 화면을 중심으로 설계했습니다',
      landingBenefitsTitle: '별도 차량용 어댑터 없이 익숙한 주행 화면을',
      landingBenefitsLead: '휴대전화에서 실행되는 지원 프로젝션 화면을 같은 네트워크의 호환 차량·태블릿·랩톱 브라우저로 가져옵니다.',
      landingLocalTitle: '가까운 네트워크에서 직접 연결',
      landingLocalBody: '휴대전화 핫스팟이나 같은 Wi-Fi를 이용합니다. 프로젝션 미디어는 브라우저와 휴대전화 사이의 직접 경로로 전송됩니다.',
      landingAdaptiveTitle: '달라지는 화면에도 알맞게',
      landingAdaptiveBody: '넓은 화면, 좁아진 화면, 세로 화면에 맞춰 표시하면서 터치 좌표가 어긋나지 않도록 조정합니다.',
      landingRememberedTitle: '한 번 승인하고 다음에는 빠르게',
      landingRememberedBody: '일회용 코드로 브라우저를 승인하면 다음 방문부터 더 빠르게 다시 연결할 수 있습니다.',
      landingStepsEyebrow: '이용 방법',
      landingStepsTitle: '주차한 상태에서 몇 분이면 준비됩니다',
      landingStepInstallTitle: 'NavOnWeb 설치 및 준비',
      landingStepInstallBody: '첫 사용 안내가 휴대전화의 필수 설정을 차례로 설명합니다.',
      landingStepServerTitle: 'Android Auto™ 헤드 유닛 서버 시작',
      landingStepServerBody: 'NavOnWeb에서 필요한 Android Auto™ 설정 화면을 바로 열 수 있습니다.',
      landingStepPairTitle: 'navonweb.com을 열고 페어링',
      landingStepPairBody: '브라우저를 휴대전화 네트워크에 연결하고 위 입력란에 8자리 코드를 입력하면 됩니다.',
      landingSafety: '모든 설정은 안전하게 주차한 상태에서 완료하세요. 운전 중에는 휴대전화나 차량 화면을 조작하지 마세요.',
      landingPrivacyPolicy: '개인정보 처리방침',
      landingTrademark: 'Android Auto는 Google LLC의 상표입니다. NavOnWeb은 독립 제품이며 Google 또는 차량 제조사와 제휴하거나 보증·후원을 받은 제품이 아닙니다.'
    }),
    es: Object.freeze({
      browserPairing: 'Vinculación del navegador',
      pairingCodeLabel: 'Código de vinculación de 8 dígitos que aparece en tu teléfono',
      connect: 'Conectar',
      pairingRememberedHint: 'Escribe el código una vez y este navegador se recordará.',
      androidAutoScreen: 'Pantalla de Android Auto',
      projectionInputArea: 'Área de video y entrada táctil de Android Auto',
      projectionFrameAlt: 'Video de proyección de Android Auto',
      androidAutoWaiting: 'Esperando a Android Auto',
      fullscreenEnter: 'Pantalla completa',
      fullscreenExit: 'Salir de pantalla completa',
      fullscreenEnterLabel: 'Ver Android Auto en pantalla completa',
      fullscreenExitLabel: 'Salir de la pantalla completa de Android Auto',
      normalViewState: 'Mostrando la vista estándar',
      fullscreenViewState: 'Mostrando en pantalla completa',
      theaterViewState: 'Mostrando en modo cine',
      fullscreenHintWindows: 'Presiona Esc para salir de la pantalla completa.',
      fullscreenHintTouch: 'Separa o junta dos dedos para alternar la pantalla completa.',
      presentationControlsLabel: 'Controles de visualización de la proyección',
      presentationOrientationLabel: 'Orientación',
      presentationOrientationAuto: 'Automática',
      presentationOrientationLandscape: 'Horizontal',
      presentationOrientationPortrait: 'Vertical',
      presentationAlignmentLabel: 'Alineación',
      presentationAlignmentTrigger: 'Alineación: {alignment}. Abrir el selector de posición.',
      presentationAlignCenter: 'Centro',
      presentationAlignTop: 'Arriba',
      presentationAlignBottom: 'Abajo',
      presentationAlignLeft: 'Izquierda',
      presentationAlignRight: 'Derecha',
      presentationAlignTopLeft: 'Arriba a la izquierda',
      presentationAlignTopRight: 'Arriba a la derecha',
      presentationAlignBottomLeft: 'Abajo a la izquierda',
      presentationAlignBottomRight: 'Abajo a la derecha',
      presentationAlignCustom: 'Personalizada',
      presentationGuideTitle: 'Controles de la pantalla completa',
      presentationGuideOrientation: 'Elige Automática, Horizontal o Vertical antes de entrar en pantalla completa.',
      presentationGuideAlignment: 'Antes de entrar en pantalla completa, elige dónde se alinea el video cuando quedan bandas negras.',
      presentationGuideMove: 'Mueve dos dedos a la vez para reubicar el video dentro de los márgenes disponibles.',
      presentationGuideExit: 'Antes de que se active el desplazamiento, junta los dedos con decisión para salir de la pantalla completa.',
      presentationGuideReset: 'Presiona con tres dedos para restablecer la posición del video.',
      presentationGuideDoNotShowAgain: 'No volver a mostrar',
      presentationGuideDismiss: 'Aceptar',
      presentationGuideAutoDismiss: 'Esta guía se cierra automáticamente en {seconds} s.',
      presentationGuideAutoDismissStopped: 'Se detuvo el cierre automático.',
      presentationGuideCountdownLabel: 'Tiempo restante hasta que esta guía se cierre automáticamente',
      presentationReset: 'Se restableció la posición del video.',
      announcements: 'Avisos',
      noticesLoading: 'Cargando avisos…',
      noAnnouncements: 'No hay avisos.',
      noticesUnavailable: 'Los avisos no están disponibles temporalmente.',
      noticesStale: 'Mostrando los avisos guardados.',
      announcementUntitled: 'Aviso',
      premiumUpgradeMessage: 'Compra el nivel Premium para usar video de alta resolución (1080p) y audio estéreo.',
      premiumDoNotShowAgain: 'No volver a mostrar',
      confirm: 'Aceptar',
      connectionExpiredPhone: 'La conexión del navegador caducó. Escribe el código nuevo que aparece en tu teléfono.',
      connectionExpired: 'La conexión del navegador caducó. Escribe un código nuevo.',
      savedConnectionExpired: 'La conexión guardada caducó. Escribe el código nuevo que aparece en tu teléfono.',
      videoWaiting: 'Esperando el video',
      videoWaitingElapsed: 'Tiempo de espera {time}',
      mediaPermissionPrompt: 'Toca una vez para activar el sonido y el micrófono del navegador.',
      mediaPermissionAllow: 'Activar sonido y micrófono',
      mediaPermissionDenied: 'El acceso al micrófono está bloqueado. Permítelo en los permisos de este sitio y vuelve a intentarlo.',
      localNetworkPrompt: 'Permite el acceso a la red local para conectarte directamente con tu teléfono.',
      localNetworkDenied: 'El navegador no pudo confirmar el acceso a la red local. Si la conexión falla, comprueba el permiso de este sitio y vuelve a intentarlo.',
      localNetworkAllow: 'Permitir red local',
      localNetworkRetry: 'Reintentar',
      androidAutoReconnecting: 'Reconectando con Android Auto',
      serverWaiting: 'Esperando el video',
      localControlRecovering: 'Recuperando la conexión de control local.',
      viewportMainSessionOnly: 'Este dispositivo no es la sesión principal. La relación de aspecto y el área de visualización de la proyección siguen la sesión principal seleccionada en tu teléfono.',
      eightDigitRequired: 'Escribe un número de 8 dígitos.',
      connecting: 'Conectando…',
      invalidCode: 'El código no es válido.',
      codeNotRegistered: 'El teléfono aún no ha registrado este código. Confirma que el servicio está en ejecución y que el teléfono y el navegador están en la misma red, y luego inténtalo de nuevo.',
      expiredCode: 'El código caducó. Consulta la app para obtener un código nuevo.',
      retryLater: 'Inténtalo de nuevo en un momento.',
      unableToConnect: 'No se pudo conectar. Inténtalo de nuevo en un momento.',
      repairPairing: 'Obtener un código de vinculación nuevo',
      repairPairingLabel: 'Vincular con un código nuevo',
      repairPairingHint: 'Busca el nuevo código de vinculación en tu teléfono y escríbelo aquí.',
      landingEyebrow: 'Tu teléfono. Tu navegador. Un viaje conectado.',
      landingTitle: 'Lleva Android Auto a un navegador cercano.',
      landingLead: 'NavOnWeb se conecta a través de tu red local para que puedas ver, oír y controlar la proyección desde un navegador compatible.',
      landingHighlightsLabel: 'Puntos destacados de NavOnWeb',
      landingHighlightLocal: 'Conexión local prioritaria',
      landingHighlightMedia: 'Video, sonido y control táctil',
      landingHighlightResponsive: 'Vista adaptable en el navegador',
      landingPlayStoreLabel: 'Disponibilidad en Google Play',
      landingPlayStoreComingSoon: 'Próximamente en Google Play',
      landingPlayStoreCta: 'Descarga NavOnWeb en Google Play',
      landingPlayStoreHint: 'Requiere un teléfono con Android Auto y un navegador en la misma red local.',
      landingPeek: 'Descubre cómo funciona NavOnWeb',
      landingBandTitle: 'La pantalla de Android Auto de tu teléfono, en un navegador.',
      landingPremiumScreenshotAlt: 'Pantalla del servicio NavOnWeb Premium en ejecución',
      landingBrowserEyebrow: 'La experiencia conectada',
      landingBrowserTitle: 'Tu proyección, lista en el navegador.',
      landingBrowserLead: 'Una vez vinculado, la pantalla en vivo de Android Auto ocupa el espacio disponible del navegador con video, sonido y controles táctiles.',
      landingBrowserScreenshotAlt: 'Ilustración de una sesión de NavOnWeb en el navegador con imágenes de mapa recreadas con IA',
      landingBrowserCaption: 'Sesión conectada en un navegador — imágenes de mapa recreadas con IA a modo de ilustración',
      landingBenefitsEyebrow: 'Diseñado en torno a la pantalla que ya tienes',
      landingBenefitsTitle: 'La conducción de siempre, sin otro adaptador para el vehículo.',
      landingBenefitsLead: 'Mantén la sesión de proyección compatible en tu teléfono y llévala al navegador de un vehículo, una tablet o un portátil compatibles en la misma red.',
      landingLocalTitle: 'Conéctate localmente',
      landingLocalBody: 'Usa el punto de acceso de tu teléfono o la misma red Wi-Fi. El contenido multimedia de la proyección se mantiene en la ruta directa entre el navegador y el teléfono.',
      landingAdaptiveTitle: 'Se adapta a cada pantalla',
      landingAdaptiveBody: 'La vista se adapta a espacios de navegador anchos, compactos y verticales, manteniendo alineadas las coordenadas táctiles.',
      landingRememberedTitle: 'Vincula una vez y vuelve rápido',
      landingRememberedBody: 'Escribe el código de un solo uso para aprobar un navegador. Ese navegador se recordará para reconectarse más rápido.',
      landingStepsEyebrow: 'Cómo funciona',
      landingStepsTitle: 'Listo en unos minutos con el vehículo estacionado.',
      landingStepInstallTitle: 'Instala y prepara NavOnWeb',
      landingStepInstallBody: 'La guía de primer uso te acompaña por la configuración necesaria del teléfono.',
      landingStepServerTitle: 'Inicia el servidor de unidad principal de Android Auto™',
      landingStepServerBody: 'NavOnWeb puede llevarte directamente a la página de configuración de Android Auto™ correspondiente.',
      landingStepPairTitle: 'Abre navonweb.com y vincula',
      landingStepPairBody: 'Conecta el navegador a la red del teléfono, escribe el código de ocho dígitos de arriba y empieza a ver la pantalla.',
      landingSafety: 'Completa la configuración solo con el vehículo estacionado de forma segura. No uses el teléfono ni la pantalla del vehículo mientras conduces.',
      landingPrivacyPolicy: 'Política de privacidad',
      landingTrademark: 'Android Auto es una marca comercial de Google LLC. NavOnWeb es un producto independiente y no está afiliado a Google ni a ningún fabricante de vehículos, ni cuenta con su respaldo o patrocinio.',
    }),
    pt: Object.freeze({
      browserPairing: 'Pareamento do navegador',
      pairingCodeLabel: 'Código de pareamento de 8 dígitos mostrado no seu celular',
      connect: 'Conectar',
      pairingRememberedHint: 'Digite o código uma vez e este navegador será lembrado.',
      androidAutoScreen: 'Tela do Android Auto',
      projectionInputArea: 'Área de vídeo e entrada de toque do Android Auto',
      projectionFrameAlt: 'Vídeo da projeção do Android Auto',
      androidAutoWaiting: 'Aguardando o Android Auto',
      fullscreenEnter: 'Tela cheia',
      fullscreenExit: 'Sair da tela cheia',
      fullscreenEnterLabel: 'Ver o Android Auto em tela cheia',
      fullscreenExitLabel: 'Sair da tela cheia do Android Auto',
      normalViewState: 'Mostrando visualização padrão',
      fullscreenViewState: 'Mostrando tela cheia',
      theaterViewState: 'Mostrando modo cinema',
      fullscreenHintWindows: 'Pressione Esc para sair da tela cheia.',
      fullscreenHintTouch: 'Afaste ou junte dois dedos para alternar a tela cheia.',
      presentationControlsLabel: 'Controles de exibição da projeção',
      presentationOrientationLabel: 'Orientação',
      presentationOrientationAuto: 'Automática',
      presentationOrientationLandscape: 'Paisagem',
      presentationOrientationPortrait: 'Retrato',
      presentationAlignmentLabel: 'Alinhamento',
      presentationAlignmentTrigger: 'Alinhamento: {alignment}. Abrir seletor de posição.',
      presentationAlignCenter: 'Centro',
      presentationAlignTop: 'Superior',
      presentationAlignBottom: 'Inferior',
      presentationAlignLeft: 'Esquerda',
      presentationAlignRight: 'Direita',
      presentationAlignTopLeft: 'Superior esquerdo',
      presentationAlignTopRight: 'Superior direito',
      presentationAlignBottomLeft: 'Inferior esquerdo',
      presentationAlignBottomRight: 'Inferior direito',
      presentationAlignCustom: 'Personalizado',
      presentationGuideTitle: 'Controles de exibição em tela cheia',
      presentationGuideOrientation: 'Escolha Automática, Paisagem ou Retrato antes de entrar em tela cheia.',
      presentationGuideAlignment: 'Antes de entrar em tela cheia, escolha onde o vídeo será alinhado quando houver faixas pretas.',
      presentationGuideMove: 'Mova dois dedos juntos para reposicionar o vídeo dentro das margens disponíveis.',
      presentationGuideExit: 'Antes que o movimento seja fixado, um gesto nítido de pinça para dentro sai da tela cheia.',
      presentationGuideReset: 'Pressione com três dedos para redefinir a posição do vídeo.',
      presentationGuideDoNotShowAgain: 'Não mostrar novamente',
      presentationGuideDismiss: 'OK',
      presentationGuideAutoDismiss: 'Este guia fecha automaticamente em {seconds}s.',
      presentationGuideAutoDismissStopped: 'O fechamento automático foi interrompido.',
      presentationGuideCountdownLabel: 'Tempo até este guia fechar automaticamente',
      presentationReset: 'A posição do vídeo foi redefinida.',
      announcements: 'Avisos',
      noticesLoading: 'Carregando avisos…',
      noAnnouncements: 'Não há avisos.',
      noticesUnavailable: 'Os avisos estão temporariamente indisponíveis.',
      noticesStale: 'Mostrando avisos salvos.',
      announcementUntitled: 'Aviso',
      premiumUpgradeMessage: 'Compre o nível Premium para usar vídeo em alta resolução (1080p) e áudio estéreo.',
      premiumDoNotShowAgain: 'Não mostrar novamente',
      confirm: 'OK',
      connectionExpiredPhone: 'A conexão do navegador expirou. Digite o novo código mostrado no seu celular.',
      connectionExpired: 'A conexão do navegador expirou. Digite um novo código.',
      savedConnectionExpired: 'A conexão salva expirou. Digite o novo código mostrado no seu celular.',
      videoWaiting: 'Aguardando vídeo',
      videoWaitingElapsed: 'Tempo de espera {time}',
      mediaPermissionPrompt: 'Toque uma vez para ativar o som e o microfone do navegador.',
      mediaPermissionAllow: 'Ativar som e microfone',
      mediaPermissionDenied: 'O acesso ao microfone está bloqueado. Permita nas permissões deste site e tente novamente.',
      localNetworkPrompt: 'Permita o acesso à rede local para conectar diretamente ao seu celular.',
      localNetworkDenied: 'O navegador não conseguiu confirmar o acesso à rede local. Se a conexão falhar, verifique a permissão deste site e tente novamente.',
      localNetworkAllow: 'Permitir rede local',
      localNetworkRetry: 'Tentar novamente',
      androidAutoReconnecting: 'Reconectando ao Android Auto',
      serverWaiting: 'Aguardando vídeo',
      localControlRecovering: 'Recuperando a conexão de controle local.',
      viewportMainSessionOnly: 'Este dispositivo não é a sessão principal. A proporção da projeção e a área de visualização seguem a sessão principal selecionada no seu celular.',
      eightDigitRequired: 'Digite um número de 8 dígitos.',
      connecting: 'Conectando…',
      invalidCode: 'O código não é válido.',
      codeNotRegistered: 'O celular ainda não registrou este código. Confirme que o serviço está em execução e que o celular e o navegador estão na mesma rede, depois tente novamente.',
      expiredCode: 'O código expirou. Verifique um novo código no app.',
      retryLater: 'Tente novamente em instantes.',
      unableToConnect: 'Não foi possível conectar. Tente novamente em instantes.',
      repairPairing: 'Obter um novo código de pareamento',
      repairPairingLabel: 'Parear com um novo código',
      repairPairingHint: 'Verifique um novo código de pareamento no seu celular e digite-o aqui.',
      landingEyebrow: 'Seu celular. Seu navegador. Uma só viagem conectada.',
      landingTitle: 'Leve o Android Auto a um navegador próximo.',
      landingLead: 'O NavOnWeb conecta pela sua rede local para que você veja, ouça e controle a projeção em um navegador compatível.',
      landingHighlightsLabel: 'Destaques do NavOnWeb',
      landingHighlightLocal: 'Conexão local em primeiro lugar',
      landingHighlightMedia: 'Vídeo, som e toque',
      landingHighlightResponsive: 'Visualização responsiva no navegador',
      landingPlayStoreLabel: 'Disponibilidade no Google Play',
      landingPlayStoreComingSoon: 'Em breve no Google Play',
      landingPlayStoreCta: 'Baixar o NavOnWeb no Google Play',
      landingPlayStoreHint: 'Requer um celular com Android Auto e um navegador na mesma rede local.',
      landingPeek: 'Veja como o NavOnWeb funciona',
      landingBandTitle: 'A tela do Android Auto do seu celular, em um navegador.',
      landingPremiumScreenshotAlt: 'Tela do serviço NavOnWeb Premium em execução',
      landingBrowserEyebrow: 'A experiência conectada',
      landingBrowserTitle: 'Sua projeção, pronta no navegador.',
      landingBrowserLead: 'Depois do pareamento, a tela ao vivo do Android Auto preenche o espaço disponível do navegador com vídeo, som e controles de toque.',
      landingBrowserScreenshotAlt: 'Ilustração de uma sessão do NavOnWeb no navegador com imagens de mapa recriadas por IA',
      landingBrowserCaption: 'Sessão conectada em um navegador — imagens de mapa recriadas com IA para ilustração',
      landingBenefitsEyebrow: 'Projetado em torno da tela que você já tem',
      landingBenefitsTitle: 'Uma viagem familiar, sem mais um adaptador para o veículo.',
      landingBenefitsLead: 'Mantenha a sessão de projeção compatível no seu celular e leve-a a um navegador compatível no veículo, no tablet ou no notebook na mesma rede.',
      landingLocalTitle: 'Conecte localmente',
      landingLocalBody: 'Use o ponto de acesso do seu celular ou a mesma rede Wi-Fi. A mídia da projeção permanece no caminho direto entre o navegador e o celular.',
      landingAdaptiveTitle: 'Adapta-se a telas variadas',
      landingAdaptiveBody: 'A visualização se adapta a espaços de navegador largos, compactos e em retrato, mantendo as coordenadas de toque alinhadas.',
      landingRememberedTitle: 'Pareie uma vez, volte rapidamente',
      landingRememberedBody: 'Digite o código de uso único para aprovar um navegador. Esse navegador é lembrado para reconectar mais rápido.',
      landingStepsEyebrow: 'Como funciona',
      landingStepsTitle: 'Pronto em poucos minutos, com o carro estacionado.',
      landingStepInstallTitle: 'Instale e prepare o NavOnWeb',
      landingStepInstallBody: 'O guia de primeiro uso mostra as configurações necessárias no celular.',
      landingStepServerTitle: 'Inicie o servidor da unidade principal do Android Auto™',
      landingStepServerBody: 'O NavOnWeb pode levar você diretamente à página de configurações relevante do Android Auto™.',
      landingStepPairTitle: 'Abra o navonweb.com e pareie',
      landingStepPairBody: 'Conecte o navegador à rede do celular, digite o código de oito dígitos acima e comece a visualizar.',
      landingSafety: 'Conclua a configuração somente com o veículo estacionado em segurança. Não use o celular nem a tela do veículo enquanto dirige.',
      landingPrivacyPolicy: 'Política de Privacidade',
      landingTrademark: 'Android Auto é uma marca registrada da Google LLC. O NavOnWeb é um produto independente e não é afiliado, endossado nem patrocinado pelo Google ou por qualquer fabricante de veículos.',
    }),
    ar: Object.freeze({
      browserPairing: 'إقران المتصفح',
      pairingCodeLabel: 'رمز الإقران المكوّن من 8 أرقام المعروض على هاتفك',
      connect: 'اتصال',
      pairingRememberedHint: 'أدخل الرمز مرة واحدة وسيتم تذكّر هذا المتصفح.',
      androidAutoScreen: 'شاشة Android Auto',
      projectionInputArea: 'منطقة فيديو Android Auto وإدخال اللمس',
      projectionFrameAlt: 'فيديو إسقاط Android Auto',
      androidAutoWaiting: 'في انتظار Android Auto',
      fullscreenEnter: 'ملء الشاشة',
      fullscreenExit: 'الخروج من ملء الشاشة',
      fullscreenEnterLabel: 'عرض Android Auto في وضع ملء الشاشة',
      fullscreenExitLabel: 'الخروج من وضع ملء الشاشة لـ Android Auto',
      normalViewState: 'يتم العرض في الوضع العادي',
      fullscreenViewState: 'يتم العرض في وضع ملء الشاشة',
      theaterViewState: 'يتم العرض في وضع المسرح',
      fullscreenHintWindows: 'اضغط على Esc للخروج من ملء الشاشة.',
      fullscreenHintTouch: 'باعد بين إصبعيك أو ضمّهما لتبديل وضع ملء الشاشة.',
      presentationControlsLabel: 'عناصر التحكم في شاشة الإسقاط',
      presentationOrientationLabel: 'الاتجاه',
      presentationOrientationAuto: 'تلقائي',
      presentationOrientationLandscape: 'أفقي',
      presentationOrientationPortrait: 'عمودي',
      presentationAlignmentLabel: 'المحاذاة',
      presentationAlignmentTrigger: 'المحاذاة: {alignment}. فتح أداة اختيار الموضع.',
      presentationAlignCenter: 'الوسط',
      presentationAlignTop: 'أعلى',
      presentationAlignBottom: 'أسفل',
      presentationAlignLeft: 'يسار',
      presentationAlignRight: 'يمين',
      presentationAlignTopLeft: 'أعلى اليسار',
      presentationAlignTopRight: 'أعلى اليمين',
      presentationAlignBottomLeft: 'أسفل اليسار',
      presentationAlignBottomRight: 'أسفل اليمين',
      presentationAlignCustom: 'مخصص',
      presentationGuideTitle: 'عناصر التحكم في عرض ملء الشاشة',
      presentationGuideOrientation: 'اختر تلقائي أو أفقي أو عمودي قبل الدخول في وضع ملء الشاشة.',
      presentationGuideAlignment: 'قبل الدخول في وضع ملء الشاشة، اختر موضع محاذاة الفيديو المحاط بأشرطة فارغة.',
      presentationGuideMove: 'حرّك إصبعين معًا لتغيير موضع الفيديو ضمن الهوامش المتاحة.',
      presentationGuideExit: 'قبل أن يُقفل التحريك، يؤدي ضم الإصبعين بوضوح إلى الخروج من ملء الشاشة.',
      presentationGuideReset: 'اضغط بثلاثة أصابع لإعادة ضبط موضع الفيديو.',
      presentationGuideDoNotShowAgain: 'عدم الإظهار مرة أخرى',
      presentationGuideDismiss: 'حسنًا',
      presentationGuideAutoDismiss: 'يُغلق هذا الدليل تلقائيًا خلال {seconds} ثانية.',
      presentationGuideAutoDismissStopped: 'تم إيقاف الإغلاق التلقائي.',
      presentationGuideCountdownLabel: 'الوقت المتبقي حتى يُغلق هذا الدليل تلقائيًا',
      presentationReset: 'تمت إعادة ضبط موضع الفيديو.',
      announcements: 'الإعلانات',
      noticesLoading: 'جارٍ تحميل الإعلانات…',
      noAnnouncements: 'لا توجد إعلانات.',
      noticesUnavailable: 'الإعلانات غير متاحة مؤقتًا.',
      noticesStale: 'يتم عرض الإعلانات المحفوظة.',
      announcementUntitled: 'إعلان',
      premiumUpgradeMessage: 'يُرجى شراء فئة Premium لاستخدام الفيديو عالي الدقة (1080p) والصوت الاستيريو.',
      premiumDoNotShowAgain: 'عدم الإظهار مرة أخرى',
      confirm: 'حسنًا',
      connectionExpiredPhone: 'انتهت صلاحية اتصال المتصفح. أدخل الرمز الجديد المعروض على هاتفك.',
      connectionExpired: 'انتهت صلاحية اتصال المتصفح. أدخل رمزًا جديدًا.',
      savedConnectionExpired: 'انتهت صلاحية الاتصال المحفوظ. أدخل الرمز الجديد المعروض على هاتفك.',
      videoWaiting: 'في انتظار الفيديو',
      videoWaitingElapsed: 'مدة الانتظار {time}',
      mediaPermissionPrompt: 'انقر مرة واحدة لتفعيل الصوت وميكروفون المتصفح.',
      mediaPermissionAllow: 'تفعيل الصوت والميكروفون',
      mediaPermissionDenied: 'الوصول إلى الميكروفون محظور. اسمح به في أذونات هذا الموقع، ثم أعد المحاولة.',
      localNetworkPrompt: 'اسمح بالوصول إلى الشبكة المحلية للاتصال بهاتفك مباشرةً.',
      localNetworkDenied: 'تعذّر على المتصفح تأكيد الوصول إلى الشبكة المحلية. إذا فشل الاتصال، تحقق من إذن هذا الموقع ثم أعد المحاولة.',
      localNetworkAllow: 'السماح بالشبكة المحلية',
      localNetworkRetry: 'إعادة المحاولة',
      androidAutoReconnecting: 'جارٍ إعادة الاتصال بـ Android Auto',
      serverWaiting: 'في انتظار الفيديو',
      localControlRecovering: 'جارٍ استعادة اتصال التحكم المحلي.',
      viewportMainSessionOnly: 'هذا الجهاز ليس الجلسة الرئيسية. تتبع نسبة العرض إلى الارتفاع للإسقاط ومنطقة العرض الجلسة الرئيسية المحددة على هاتفك.',
      eightDigitRequired: 'أدخل رقمًا مكوّنًا من 8 أرقام.',
      connecting: 'جارٍ الاتصال…',
      invalidCode: 'الرمز غير صالح.',
      codeNotRegistered: 'لم يسجّل الهاتف هذا الرمز بعد. تأكد من أن الخدمة قيد التشغيل وأن الهاتف والمتصفح على الشبكة نفسها، ثم أعد المحاولة.',
      expiredCode: 'انتهت صلاحية الرمز. تحقق من التطبيق للحصول على رمز جديد.',
      retryLater: 'أعد المحاولة بعد قليل.',
      unableToConnect: 'تعذّر الاتصال. أعد المحاولة بعد قليل.',
      repairPairing: 'الحصول على رمز إقران جديد',
      repairPairingLabel: 'الإقران برمز جديد',
      repairPairingHint: 'تحقق من هاتفك للحصول على رمز إقران جديد، ثم أدخله هنا.',
      landingEyebrow: 'هاتفك. متصفحك. رحلة واحدة متصلة.',
      landingTitle: 'انقل Android Auto إلى متصفح قريب.',
      landingLead: 'يتصل NavOnWeb عبر شبكتك المحلية لتتمكن من مشاهدة الإسقاط وسماعه والتحكم فيه من متصفح متوافق.',
      landingHighlightsLabel: 'أبرز ميزات NavOnWeb',
      landingHighlightLocal: 'اتصال محلي بالدرجة الأولى',
      landingHighlightMedia: 'فيديو وصوت ولمس',
      landingHighlightResponsive: 'عرض متجاوب في المتصفح',
      landingPlayStoreLabel: 'التوفر على Google Play',
      landingPlayStoreComingSoon: 'قريبًا على Google Play',
      landingPlayStoreCta: 'الحصول على NavOnWeb من Google Play',
      landingPlayStoreHint: 'يتطلب هاتفًا يدعم Android Auto ومتصفحًا على الشبكة المحلية نفسها.',
      landingPeek: 'تعرّف على طريقة عمل NavOnWeb',
      landingBandTitle: 'شاشة Android Auto من هاتفك، في متصفح.',
      landingPremiumScreenshotAlt: 'شاشة تشغيل خدمة NavOnWeb Premium',
      landingBrowserEyebrow: 'تجربة الاتصال',
      landingBrowserTitle: 'إسقاطك جاهز في المتصفح.',
      landingBrowserLead: 'بعد الإقران، تملأ شاشة Android Auto المباشرة مساحة المتصفح المتاحة بالفيديو والصوت وعناصر التحكم باللمس.',
      landingBrowserScreenshotAlt: 'رسم توضيحي لجلسة NavOnWeb في المتصفح مع صور خرائط أُعيد إنشاؤها بالذكاء الاصطناعي',
      landingBrowserCaption: 'جلسة متصلة في متصفح — أُعيد إنشاء صور الخرائط بالذكاء الاصطناعي لأغراض التوضيح',
      landingBenefitsEyebrow: 'مصمَّم حول الشاشة التي لديك بالفعل',
      landingBenefitsTitle: 'قيادة مألوفة، بدون محوّل إضافي للسيارة.',
      landingBenefitsLead: 'أبقِ جلسة الإسقاط المدعومة على هاتفك وانقلها إلى متصفح متوافق في السيارة أو الجهاز اللوحي أو الكمبيوتر المحمول على الشبكة نفسها.',
      landingLocalTitle: 'اتصال محلي',
      landingLocalBody: 'استخدم نقطة اتصال هاتفك أو شبكة Wi-Fi نفسها. تبقى وسائط الإسقاط على المسار المباشر بين المتصفح والهاتف.',
      landingAdaptiveTitle: 'يتلاءم مع الشاشات المتغيرة',
      landingAdaptiveBody: 'يتكيّف العرض مع مساحات المتصفح العريضة والمدمجة والعمودية مع الحفاظ على محاذاة إحداثيات اللمس.',
      landingRememberedTitle: 'أقرن مرة واحدة، وعُد بسرعة',
      landingRememberedBody: 'أدخل الرمز المخصص للاستخدام مرة واحدة لاعتماد متصفح. يتم تذكّر هذا المتصفح لإعادة اتصال أسرع.',
      landingStepsEyebrow: 'طريقة العمل',
      landingStepsTitle: 'جاهز خلال دقائق قليلة أثناء التوقف.',
      landingStepInstallTitle: 'ثبّت NavOnWeb وجهّزه',
      landingStepInstallBody: 'يرشدك دليل الاستخدام الأول عبر إعدادات الهاتف المطلوبة.',
      landingStepServerTitle: 'ابدأ خادم الوحدة الرئيسية في Android Auto™',
      landingStepServerBody: 'يمكن لـ NavOnWeb نقلك مباشرةً إلى صفحة إعدادات Android Auto™ ذات الصلة.',
      landingStepPairTitle: 'افتح navonweb.com وأقرن',
      landingStepPairBody: 'وصّل المتصفح بشبكة الهاتف، وأدخل الرمز المكوّن من ثمانية أرقام أعلاه، وابدأ المشاهدة.',
      landingSafety: 'أكمل الإعداد فقط أثناء التوقف بأمان. لا تستخدم الهاتف أو شاشة السيارة أثناء القيادة.',
      landingPrivacyPolicy: 'سياسة الخصوصية',
      landingTrademark: 'تُعدّ Android Auto علامة تجارية لشركة Google LLC. يُعدّ NavOnWeb منتجًا مستقلاً غير تابع لشركة Google أو لأي شركة مصنّعة للمركبات، ولا تصادق عليه أو ترعاه أي منهما.',
    }),
    hi: Object.freeze({
      browserPairing: 'ब्राउज़र पेयरिंग',
      pairingCodeLabel: 'आपके फ़ोन पर दिखाया गया 8 अंकों का पेयरिंग कोड',
      connect: 'कनेक्ट करें',
      pairingRememberedHint: 'कोड एक बार डालें, इसके बाद यह ब्राउज़र याद रखा जाएगा.',
      androidAutoScreen: 'Android Auto स्क्रीन',
      projectionInputArea: 'Android Auto वीडियो और टच इनपुट क्षेत्र',
      projectionFrameAlt: 'Android Auto प्रोजेक्शन वीडियो',
      androidAutoWaiting: 'Android Auto का इंतज़ार है',
      fullscreenEnter: 'फ़ुल स्क्रीन',
      fullscreenExit: 'फ़ुल स्क्रीन से बाहर निकलें',
      fullscreenEnterLabel: 'Android Auto को फ़ुल स्क्रीन में देखें',
      fullscreenExitLabel: 'Android Auto फ़ुल स्क्रीन से बाहर निकलें',
      normalViewState: 'सामान्य व्यू दिखाया जा रहा है',
      fullscreenViewState: 'फ़ुल स्क्रीन दिखाई जा रही है',
      theaterViewState: 'थिएटर मोड दिखाया जा रहा है',
      fullscreenHintWindows: 'फ़ुल स्क्रीन से बाहर निकलने के लिए Esc दबाएं.',
      fullscreenHintTouch: 'फ़ुल स्क्रीन चालू/बंद करने के लिए पिंच आउट/इन करें.',
      presentationControlsLabel: 'प्रोजेक्शन डिसप्ले कंट्रोल',
      presentationOrientationLabel: 'ओरिएंटेशन',
      presentationOrientationAuto: 'ऑटो',
      presentationOrientationLandscape: 'लैंडस्केप',
      presentationOrientationPortrait: 'पोर्ट्रेट',
      presentationAlignmentLabel: 'अलाइनमेंट',
      presentationAlignmentTrigger: 'अलाइनमेंट: {alignment}. पोज़िशन चुनने का विकल्प खोलें.',
      presentationAlignCenter: 'बीच में',
      presentationAlignTop: 'ऊपर',
      presentationAlignBottom: 'नीचे',
      presentationAlignLeft: 'बाएं',
      presentationAlignRight: 'दाएं',
      presentationAlignTopLeft: 'ऊपर बाएं',
      presentationAlignTopRight: 'ऊपर दाएं',
      presentationAlignBottomLeft: 'नीचे बाएं',
      presentationAlignBottomRight: 'नीचे दाएं',
      presentationAlignCustom: 'कस्टम',
      presentationGuideTitle: 'फ़ुल स्क्रीन डिसप्ले कंट्रोल',
      presentationGuideOrientation: 'फ़ुल स्क्रीन में जाने से पहले ऑटो, लैंडस्केप या पोर्ट्रेट चुनें.',
      presentationGuideAlignment: 'फ़ुल स्क्रीन में जाने से पहले चुनें कि लेटरबॉक्स वाला वीडियो कहां अलाइन किया जाए.',
      presentationGuideMove: 'उपलब्ध मार्जिन के अंदर वीडियो की जगह बदलने के लिए दो उंगलियां एक साथ रखकर खिसकाएं.',
      presentationGuideExit: 'खिसकाना लॉक होने से पहले, साफ़ तौर पर अंदर की ओर पिंच करने से फ़ुल स्क्रीन बंद हो जाती है.',
      presentationGuideReset: 'वीडियो की पोज़िशन रीसेट करने के लिए तीन उंगलियों से दबाएं.',
      presentationGuideDoNotShowAgain: 'फिर से न दिखाएं',
      presentationGuideDismiss: 'ठीक है',
      presentationGuideAutoDismiss: 'यह गाइड {seconds} सेकंड में अपने-आप बंद हो जाएगी.',
      presentationGuideAutoDismissStopped: 'अपने-आप बंद होना रोक दिया गया है.',
      presentationGuideCountdownLabel: 'इस गाइड के अपने-आप बंद होने में बचा समय',
      presentationReset: 'वीडियो की पोज़िशन रीसेट कर दी गई.',
      announcements: 'घोषणाएं',
      noticesLoading: 'घोषणाएं लोड हो रही हैं…',
      noAnnouncements: 'कोई घोषणा नहीं है.',
      noticesUnavailable: 'घोषणाएं कुछ समय के लिए उपलब्ध नहीं हैं.',
      noticesStale: 'सेव की गई घोषणाएं दिखाई जा रही हैं.',
      announcementUntitled: 'घोषणा',
      premiumUpgradeMessage: 'हाई-रिज़ॉल्यूशन (1080p) वीडियो और स्टीरियो ऑडियो इस्तेमाल करने के लिए कृपया प्रीमियम टियर खरीदें.',
      premiumDoNotShowAgain: 'फिर से न दिखाएं',
      confirm: 'ठीक है',
      connectionExpiredPhone: 'ब्राउज़र कनेक्शन की समयसीमा खत्म हो गई है. अपने फ़ोन पर दिखाया गया नया कोड डालें.',
      connectionExpired: 'ब्राउज़र कनेक्शन की समयसीमा खत्म हो गई है. नया कोड डालें.',
      savedConnectionExpired: 'सेव किए गए कनेक्शन की समयसीमा खत्म हो गई है. अपने फ़ोन पर दिखाया गया नया कोड डालें.',
      videoWaiting: 'वीडियो का इंतज़ार है',
      videoWaitingElapsed: 'इंतज़ार का समय {time}',
      mediaPermissionPrompt: 'आवाज़ और ब्राउज़र माइक्रोफ़ोन चालू करने के लिए एक बार टैप करें.',
      mediaPermissionAllow: 'आवाज़ और माइक्रोफ़ोन चालू करें',
      mediaPermissionDenied: 'माइक्रोफ़ोन का ऐक्सेस ब्लॉक है. इस साइट की अनुमतियों में इसे अनुमति दें, फिर से कोशिश करें.',
      localNetworkPrompt: 'अपने फ़ोन से सीधे कनेक्ट होने के लिए लोकल नेटवर्क ऐक्सेस की अनुमति दें.',
      localNetworkDenied: 'ब्राउज़र लोकल नेटवर्क ऐक्सेस की पुष्टि नहीं कर सका. अगर कनेक्शन न हो, तो इस साइट की अनुमति जांचकर फिर से कोशिश करें.',
      localNetworkAllow: 'लोकल नेटवर्क की अनुमति दें',
      localNetworkRetry: 'फिर से कोशिश करें',
      androidAutoReconnecting: 'Android Auto से फिर से कनेक्ट हो रहा है',
      serverWaiting: 'वीडियो का इंतज़ार है',
      localControlRecovering: 'लोकल कंट्रोल कनेक्शन वापस लाया जा रहा है.',
      viewportMainSessionOnly: 'यह डिवाइस मुख्य सेशन नहीं है. प्रोजेक्शन का आसपेक्ट रेशियो और व्यूपोर्ट आपके फ़ोन पर चुने गए मुख्य सेशन के हिसाब से तय होते हैं.',
      eightDigitRequired: '8 अंकों की संख्या डालें.',
      connecting: 'कनेक्ट हो रहा है…',
      invalidCode: 'कोड मान्य नहीं है.',
      codeNotRegistered: 'फ़ोन ने यह कोड अभी तक रजिस्टर नहीं किया है. पक्का करें कि सर्विस चल रही है और फ़ोन तथा ब्राउज़र एक ही नेटवर्क पर हैं, फिर से कोशिश करें.',
      expiredCode: 'कोड की समयसीमा खत्म हो गई है. नए कोड के लिए ऐप देखें.',
      retryLater: 'थोड़ी देर बाद फिर से कोशिश करें.',
      unableToConnect: 'कनेक्ट नहीं हो सका. थोड़ी देर बाद फिर से कोशिश करें.',
      repairPairing: 'नया पेयरिंग कोड पाएं',
      repairPairingLabel: 'नए कोड से पेयर करें',
      repairPairingHint: 'अपने फ़ोन पर नया पेयरिंग कोड देखें, फिर उसे यहां डालें.',
      landingEyebrow: 'आपका फ़ोन. आपका ब्राउज़र. एक कनेक्टेड सफ़र.',
      landingTitle: 'Android Auto को पास के ब्राउज़र पर लाएं.',
      landingLead: 'NavOnWeb आपके लोकल नेटवर्क पर कनेक्ट होता है, ताकि आप किसी संगत ब्राउज़र से प्रोजेक्शन को देख, सुन और कंट्रोल कर सकें.',
      landingHighlightsLabel: 'NavOnWeb की खासियतें',
      landingHighlightLocal: 'लोकल-फ़र्स्ट कनेक्शन',
      landingHighlightMedia: 'वीडियो, आवाज़ और टच',
      landingHighlightResponsive: 'रिस्पॉन्सिव ब्राउज़र व्यू',
      landingPlayStoreLabel: 'Google Play पर उपलब्धता',
      landingPlayStoreComingSoon: 'Google Play पर जल्द आ रहा है',
      landingPlayStoreCta: 'NavOnWeb को Google Play पर पाएं',
      landingPlayStoreHint: 'इसके लिए Android Auto वाला फ़ोन और उसी लोकल नेटवर्क पर एक ब्राउज़र ज़रूरी है.',
      landingPeek: 'देखें कि NavOnWeb कैसे काम करता है',
      landingBandTitle: 'आपके फ़ोन की Android Auto स्क्रीन, ब्राउज़र में.',
      landingPremiumScreenshotAlt: 'NavOnWeb प्रीमियम सर्विस चलने की स्क्रीन',
      landingBrowserEyebrow: 'कनेक्टेड अनुभव',
      landingBrowserTitle: 'आपका प्रोजेक्शन, ब्राउज़र में तैयार.',
      landingBrowserLead: 'पेयर होने के बाद, लाइव Android Auto स्क्रीन ब्राउज़र की उपलब्ध जगह को वीडियो, आवाज़ और टच कंट्रोल के साथ भर देती है.',
      landingBrowserScreenshotAlt: 'NavOnWeb ब्राउज़र सेशन का चित्रण, AI से दोबारा बनाई गई मैप इमेजरी के साथ',
      landingBrowserCaption: 'ब्राउज़र में कनेक्टेड सेशन — चित्रण के लिए मैप इमेजरी AI से दोबारा बनाई गई है',
      landingBenefitsEyebrow: 'उस स्क्रीन के इर्द-गिर्द डिज़ाइन किया गया, जो आपके पास पहले से है',
      landingBenefitsTitle: 'जाना-पहचाना सफ़र, बिना किसी और गाड़ी अडैप्टर के.',
      landingBenefitsLead: 'समर्थित प्रोजेक्शन सेशन अपने फ़ोन पर रखें और उसे उसी नेटवर्क पर मौजूद संगत गाड़ी, टैबलेट या लैपटॉप के ब्राउज़र पर लाएं.',
      landingLocalTitle: 'लोकल कनेक्ट करें',
      landingLocalBody: 'अपने फ़ोन का हॉटस्पॉट या वही Wi-Fi नेटवर्क इस्तेमाल करें. प्रोजेक्शन मीडिया ब्राउज़र और फ़ोन के बीच सीधे रास्ते पर ही रहता है.',
      landingAdaptiveTitle: 'बदलती स्क्रीन के हिसाब से फ़िट',
      landingAdaptiveBody: 'टच कोऑर्डिनेट को सही रखते हुए व्यू चौड़ी, छोटी और पोर्ट्रेट ब्राउज़र जगह के हिसाब से ढल जाता है.',
      landingRememberedTitle: 'एक बार पेयर करें, जल्दी वापस आएं',
      landingRememberedBody: 'ब्राउज़र को मंज़ूरी देने के लिए एक बार इस्तेमाल होने वाला कोड डालें. उस ब्राउज़र को तेज़ी से फिर से कनेक्ट होने के लिए याद रखा जाता है.',
      landingStepsEyebrow: 'यह कैसे काम करता है',
      landingStepsTitle: 'पार्क किए हुए कुछ ही मिनटों में तैयार.',
      landingStepInstallTitle: 'NavOnWeb इंस्टॉल करें और तैयार करें',
      landingStepInstallBody: 'पहली बार इस्तेमाल की गाइड आपको फ़ोन की ज़रूरी सेटिंग के बारे में कदम-दर-कदम बताती है.',
      landingStepServerTitle: 'Android Auto™ हेड यूनिट सर्वर शुरू करें',
      landingStepServerBody: 'NavOnWeb आपको सीधे Android Auto™ के संबंधित सेटिंग पेज पर ले जा सकता है.',
      landingStepPairTitle: 'navonweb.com खोलें और पेयर करें',
      landingStepPairBody: 'ब्राउज़र को फ़ोन के नेटवर्क से कनेक्ट करें, ऊपर दिया गया आठ अंकों का कोड डालें और देखना शुरू करें.',
      landingSafety: 'सेटअप सिर्फ़ गाड़ी को सुरक्षित जगह पार्क करके ही पूरा करें. गाड़ी चलाते समय फ़ोन या गाड़ी की स्क्रीन का इस्तेमाल न करें.',
      landingPrivacyPolicy: 'निजता नीति',
      landingTrademark: 'Android Auto, Google LLC का ट्रेडमार्क है. NavOnWeb एक स्वतंत्र प्रॉडक्ट है और यह Google या किसी भी वाहन निर्माता से संबद्ध नहीं है, न ही उनके द्वारा समर्थित या प्रायोजित है.',
    }),
    id: Object.freeze({
      browserPairing: 'Penyandingan browser',
      pairingCodeLabel: 'Kode penyandingan 8 digit yang ditampilkan di ponsel Anda',
      connect: 'Hubungkan',
      pairingRememberedHint: 'Masukkan kode satu kali dan browser ini akan diingat.',
      androidAutoScreen: 'Layar Android Auto',
      projectionInputArea: 'Area video dan input sentuh Android Auto',
      projectionFrameAlt: 'Video proyeksi Android Auto',
      androidAutoWaiting: 'Menunggu Android Auto',
      fullscreenEnter: 'Layar penuh',
      fullscreenExit: 'Keluar dari layar penuh',
      fullscreenEnterLabel: 'Lihat Android Auto dalam layar penuh',
      fullscreenExitLabel: 'Keluar dari layar penuh Android Auto',
      normalViewState: 'Menampilkan tampilan standar',
      fullscreenViewState: 'Menampilkan layar penuh',
      theaterViewState: 'Menampilkan mode teater',
      fullscreenHintWindows: 'Tekan Esc untuk keluar dari layar penuh.',
      fullscreenHintTouch: 'Cubit ke luar/ke dalam untuk mengaktifkan atau menonaktifkan layar penuh.',
      presentationControlsLabel: 'Kontrol tampilan proyeksi',
      presentationOrientationLabel: 'Orientasi',
      presentationOrientationAuto: 'Otomatis',
      presentationOrientationLandscape: 'Lanskap',
      presentationOrientationPortrait: 'Potret',
      presentationAlignmentLabel: 'Perataan',
      presentationAlignmentTrigger: 'Perataan: {alignment}. Buka pemilih posisi.',
      presentationAlignCenter: 'Tengah',
      presentationAlignTop: 'Atas',
      presentationAlignBottom: 'Bawah',
      presentationAlignLeft: 'Kiri',
      presentationAlignRight: 'Kanan',
      presentationAlignTopLeft: 'Kiri atas',
      presentationAlignTopRight: 'Kanan atas',
      presentationAlignBottomLeft: 'Kiri bawah',
      presentationAlignBottomRight: 'Kanan bawah',
      presentationAlignCustom: 'Kustom',
      presentationGuideTitle: 'Kontrol tampilan layar penuh',
      presentationGuideOrientation: 'Pilih Otomatis, Lanskap, atau Potret sebelum masuk ke layar penuh.',
      presentationGuideAlignment: 'Sebelum masuk ke layar penuh, pilih posisi video di dalam margin kosong (letterbox).',
      presentationGuideMove: 'Gerakkan dua jari secara bersamaan untuk memindahkan posisi video di dalam margin yang tersedia.',
      presentationGuideExit: 'Sebelum gerakan terkunci, cubitan ke dalam yang jelas akan keluar dari layar penuh.',
      presentationGuideReset: 'Tekan dengan tiga jari untuk mereset posisi video.',
      presentationGuideDoNotShowAgain: 'Jangan tampilkan lagi',
      presentationGuideDismiss: 'Oke',
      presentationGuideAutoDismiss: 'Panduan ini akan tertutup otomatis dalam {seconds} detik.',
      presentationGuideAutoDismissStopped: 'Penutupan otomatis telah dihentikan.',
      presentationGuideCountdownLabel: 'Waktu hingga panduan ini tertutup secara otomatis',
      presentationReset: 'Posisi video telah direset.',
      announcements: 'Pengumuman',
      noticesLoading: 'Memuat pengumuman…',
      noAnnouncements: 'Tidak ada pengumuman.',
      noticesUnavailable: 'Pengumuman tidak tersedia untuk sementara.',
      noticesStale: 'Menampilkan pengumuman yang tersimpan.',
      announcementUntitled: 'Pengumuman',
      premiumUpgradeMessage: 'Beli tingkat Premium untuk menggunakan video resolusi tinggi (1080p) dan audio stereo.',
      premiumDoNotShowAgain: 'Jangan tampilkan lagi',
      confirm: 'Oke',
      connectionExpiredPhone: 'Koneksi browser telah kedaluwarsa. Masukkan kode baru yang ditampilkan di ponsel Anda.',
      connectionExpired: 'Koneksi browser telah kedaluwarsa. Masukkan kode baru.',
      savedConnectionExpired: 'Koneksi yang tersimpan telah kedaluwarsa. Masukkan kode baru yang ditampilkan di ponsel Anda.',
      videoWaiting: 'Menunggu video',
      videoWaitingElapsed: 'Waktu tunggu {time}',
      mediaPermissionPrompt: 'Ketuk sekali untuk mengaktifkan suara dan mikrofon browser.',
      mediaPermissionAllow: 'Aktifkan suara dan mikrofon',
      mediaPermissionDenied: 'Akses mikrofon diblokir. Izinkan di izin situs ini, lalu coba lagi.',
      localNetworkPrompt: 'Izinkan akses jaringan lokal untuk terhubung langsung ke ponsel Anda.',
      localNetworkDenied: 'Browser tidak dapat mengonfirmasi akses jaringan lokal. Jika koneksi gagal, periksa izin situs ini, lalu coba lagi.',
      localNetworkAllow: 'Izinkan jaringan lokal',
      localNetworkRetry: 'Coba lagi',
      androidAutoReconnecting: 'Menghubungkan ulang ke Android Auto',
      serverWaiting: 'Menunggu video',
      localControlRecovering: 'Memulihkan koneksi kontrol lokal.',
      viewportMainSessionOnly: 'Perangkat ini bukan sesi utama. Rasio aspek dan area tampilan proyeksi mengikuti sesi utama yang dipilih di ponsel Anda.',
      eightDigitRequired: 'Masukkan angka 8 digit.',
      connecting: 'Menghubungkan…',
      invalidCode: 'Kode tidak valid.',
      codeNotRegistered: 'Ponsel belum mendaftarkan kode ini. Pastikan layanan sedang berjalan dan ponsel serta browser berada di jaringan yang sama, lalu coba lagi.',
      expiredCode: 'Kode telah kedaluwarsa. Periksa aplikasi untuk mendapatkan kode baru.',
      retryLater: 'Coba lagi sebentar lagi.',
      unableToConnect: 'Tidak dapat terhubung. Coba lagi sebentar lagi.',
      repairPairing: 'Dapatkan kode penyandingan baru',
      repairPairingLabel: 'Sandingkan dengan kode baru',
      repairPairingHint: 'Periksa kode penyandingan baru di ponsel Anda, lalu masukkan di sini.',
      landingEyebrow: 'Ponsel Anda. Browser Anda. Satu perjalanan yang terhubung.',
      landingTitle: 'Hadirkan Android Auto di browser terdekat.',
      landingLead: 'NavOnWeb terhubung melalui jaringan lokal Anda sehingga Anda dapat melihat, mendengar, dan mengontrol proyeksi dari browser yang kompatibel.',
      landingHighlightsLabel: 'Keunggulan NavOnWeb',
      landingHighlightLocal: 'Koneksi lokal langsung',
      landingHighlightMedia: 'Video, suara, dan sentuhan',
      landingHighlightResponsive: 'Tampilan browser responsif',
      landingPlayStoreLabel: 'Ketersediaan di Google Play',
      landingPlayStoreComingSoon: 'Segera hadir di Google Play',
      landingPlayStoreCta: 'Dapatkan NavOnWeb di Google Play',
      landingPlayStoreHint: 'Memerlukan ponsel Android Auto dan browser di jaringan lokal yang sama.',
      landingPeek: 'Lihat cara kerja NavOnWeb',
      landingBandTitle: 'Layar Android Auto ponsel Anda, di browser.',
      landingPremiumScreenshotAlt: 'Layar layanan NavOnWeb Premium yang sedang berjalan',
      landingBrowserEyebrow: 'Pengalaman saat terhubung',
      landingBrowserTitle: 'Proyeksi Anda, siap di browser.',
      landingBrowserLead: 'Setelah disandingkan, layar Android Auto langsung mengisi ruang browser yang tersedia dengan video, suara, dan kontrol sentuh.',
      landingBrowserScreenshotAlt: 'Ilustrasi sesi browser NavOnWeb dengan gambar peta yang dibuat ulang oleh AI',
      landingBrowserCaption: 'Sesi terhubung di browser — gambar peta dibuat ulang dengan AI sebagai ilustrasi',
      landingBenefitsEyebrow: 'Dirancang untuk layar yang sudah Anda miliki',
      landingBenefitsTitle: 'Berkendara seperti biasa, tanpa adaptor kendaraan tambahan.',
      landingBenefitsLead: 'Pertahankan sesi proyeksi yang didukung di ponsel Anda dan hadirkan ke browser kendaraan, tablet, atau laptop yang kompatibel di jaringan yang sama.',
      landingLocalTitle: 'Terhubung secara lokal',
      landingLocalBody: 'Gunakan hotspot ponsel Anda atau jaringan Wi-Fi yang sama. Media proyeksi tetap berada di jalur langsung antara browser dan ponsel.',
      landingAdaptiveTitle: 'Menyesuaikan dengan layar yang berubah',
      landingAdaptiveBody: 'Tampilan menyesuaikan dengan ruang browser yang lebar, ringkas, maupun potret sambil menjaga koordinat sentuhan tetap selaras.',
      landingRememberedTitle: 'Sandingkan sekali, kembali dengan cepat',
      landingRememberedBody: 'Masukkan kode sekali pakai untuk menyetujui browser. Browser tersebut akan diingat agar dapat terhubung kembali lebih cepat.',
      landingStepsEyebrow: 'Cara kerjanya',
      landingStepsTitle: 'Siap dalam beberapa menit saat kendaraan terparkir.',
      landingStepInstallTitle: 'Instal dan siapkan NavOnWeb',
      landingStepInstallBody: 'Panduan penggunaan pertama memandu Anda melalui setelan ponsel yang diperlukan.',
      landingStepServerTitle: 'Mulai server head unit Android Auto™',
      landingStepServerBody: 'NavOnWeb dapat langsung membawa Anda ke halaman setelan Android Auto™ yang relevan.',
      landingStepPairTitle: 'Buka navonweb.com dan sandingkan',
      landingStepPairBody: 'Hubungkan browser ke jaringan ponsel, masukkan kode delapan digit di atas, lalu mulai menonton.',
      landingSafety: 'Selesaikan penyiapan hanya saat kendaraan terparkir dengan aman. Jangan mengoperasikan ponsel atau layar kendaraan saat mengemudi.',
      landingPrivacyPolicy: 'Kebijakan Privasi',
      landingTrademark: 'Android Auto adalah merek dagang Google LLC. NavOnWeb adalah produk independen dan tidak berafiliasi dengan, didukung oleh, atau disponsori oleh Google maupun produsen kendaraan mana pun.',
    }),
    de: Object.freeze({
      browserPairing: 'Browser-Kopplung',
      pairingCodeLabel: '8-stelliger Kopplungscode, der auf deinem Smartphone angezeigt wird',
      connect: 'Verbinden',
      pairingRememberedHint: 'Gib den Code einmal ein, und dieser Browser wird gespeichert.',
      androidAutoScreen: 'Android Auto-Bildschirm',
      projectionInputArea: 'Android Auto-Video- und Touch-Eingabebereich',
      projectionFrameAlt: 'Android Auto-Projektionsvideo',
      androidAutoWaiting: 'Warten auf Android Auto',
      fullscreenEnter: 'Vollbild',
      fullscreenExit: 'Vollbild beenden',
      fullscreenEnterLabel: 'Android Auto im Vollbild anzeigen',
      fullscreenExitLabel: 'Android Auto-Vollbild beenden',
      normalViewState: 'Standardansicht wird angezeigt',
      fullscreenViewState: 'Vollbild wird angezeigt',
      theaterViewState: 'Kinomodus wird angezeigt',
      fullscreenHintWindows: 'Drücke Esc, um das Vollbild zu beenden.',
      fullscreenHintTouch: 'Finger auseinander- oder zusammenziehen, um das Vollbild umzuschalten.',
      presentationControlsLabel: 'Steuerung der Projektionsanzeige',
      presentationOrientationLabel: 'Ausrichtung',
      presentationOrientationAuto: 'Automatisch',
      presentationOrientationLandscape: 'Querformat',
      presentationOrientationPortrait: 'Hochformat',
      presentationAlignmentLabel: 'Position',
      presentationAlignmentTrigger: 'Position: {alignment}. Positionsauswahl öffnen.',
      presentationAlignCenter: 'Mitte',
      presentationAlignTop: 'Oben',
      presentationAlignBottom: 'Unten',
      presentationAlignLeft: 'Links',
      presentationAlignRight: 'Rechts',
      presentationAlignTopLeft: 'Oben links',
      presentationAlignTopRight: 'Oben rechts',
      presentationAlignBottomLeft: 'Unten links',
      presentationAlignBottomRight: 'Unten rechts',
      presentationAlignCustom: 'Benutzerdefiniert',
      presentationGuideTitle: 'Vollbild-Anzeigesteuerung',
      presentationGuideOrientation: 'Wähle vor dem Wechsel ins Vollbild Automatisch, Querformat oder Hochformat.',
      presentationGuideAlignment: 'Lege vor dem Wechsel ins Vollbild fest, wo das Video innerhalb der schwarzen Ränder positioniert wird.',
      presentationGuideMove: 'Bewege zwei Finger gemeinsam, um das Video innerhalb der verfügbaren Ränder zu verschieben.',
      presentationGuideExit: 'Solange die Verschiebung noch nicht eingerastet ist, beendet ein deutliches Zusammenziehen der Finger das Vollbild.',
      presentationGuideReset: 'Tippe mit drei Fingern, um die Videoposition zurückzusetzen.',
      presentationGuideDoNotShowAgain: 'Nicht mehr anzeigen',
      presentationGuideDismiss: 'OK',
      presentationGuideAutoDismiss: 'Diese Anleitung wird in {seconds} s automatisch geschlossen.',
      presentationGuideAutoDismissStopped: 'Das automatische Schließen wurde gestoppt.',
      presentationGuideCountdownLabel: 'Zeit, bis diese Anleitung automatisch geschlossen wird',
      presentationReset: 'Die Videoposition wurde zurückgesetzt.',
      announcements: 'Ankündigungen',
      noticesLoading: 'Ankündigungen werden geladen…',
      noAnnouncements: 'Es gibt keine Ankündigungen.',
      noticesUnavailable: 'Ankündigungen sind vorübergehend nicht verfügbar.',
      noticesStale: 'Gespeicherte Ankündigungen werden angezeigt.',
      announcementUntitled: 'Ankündigung',
      premiumUpgradeMessage: 'Bitte kaufe die Premium-Stufe, um hochauflösendes Video (1080p) und Stereo-Audio zu nutzen.',
      premiumDoNotShowAgain: 'Nicht mehr anzeigen',
      confirm: 'OK',
      connectionExpiredPhone: 'Die Browserverbindung ist abgelaufen. Gib den neuen Code ein, der auf deinem Smartphone angezeigt wird.',
      connectionExpired: 'Die Browserverbindung ist abgelaufen. Gib einen neuen Code ein.',
      savedConnectionExpired: 'Die gespeicherte Verbindung ist abgelaufen. Gib den neuen Code ein, der auf deinem Smartphone angezeigt wird.',
      videoWaiting: 'Warten auf Video',
      videoWaitingElapsed: 'Wartezeit {time}',
      mediaPermissionPrompt: 'Tippe einmal, um Ton und das Browser-Mikrofon zu aktivieren.',
      mediaPermissionAllow: 'Ton und Mikrofon aktivieren',
      mediaPermissionDenied: 'Der Mikrofonzugriff ist blockiert. Erlaube ihn in den Berechtigungen dieser Website und versuche es dann erneut.',
      localNetworkPrompt: 'Erlaube den Zugriff auf das lokale Netzwerk, um dich direkt mit deinem Smartphone zu verbinden.',
      localNetworkDenied: 'Der Browser konnte den Zugriff auf das lokale Netzwerk nicht bestätigen. Wenn die Verbindung fehlschlägt, prüfe die Berechtigung dieser Website und versuche es erneut.',
      localNetworkAllow: 'Lokales Netzwerk zulassen',
      localNetworkRetry: 'Erneut versuchen',
      androidAutoReconnecting: 'Android Auto wird neu verbunden',
      serverWaiting: 'Warten auf Video',
      localControlRecovering: 'Die lokale Steuerungsverbindung wird wiederhergestellt.',
      viewportMainSessionOnly: 'Dieses Gerät ist nicht die Hauptsitzung. Das Seitenverhältnis und der Anzeigebereich der Projektion folgen der auf deinem Smartphone ausgewählten Hauptsitzung.',
      eightDigitRequired: 'Gib eine 8-stellige Zahl ein.',
      connecting: 'Verbindung wird hergestellt…',
      invalidCode: 'Der Code ist ungültig.',
      codeNotRegistered: 'Das Smartphone hat diesen Code noch nicht registriert. Vergewissere dich, dass der Dienst läuft und dass Smartphone und Browser im selben Netzwerk sind, und versuche es dann erneut.',
      expiredCode: 'Der Code ist abgelaufen. Sieh in der App nach einem neuen Code.',
      retryLater: 'Versuche es gleich noch einmal.',
      unableToConnect: 'Verbindung nicht möglich. Versuche es gleich noch einmal.',
      repairPairing: 'Neuen Kopplungscode anfordern',
      repairPairingLabel: 'Mit neuem Code koppeln',
      repairPairingHint: 'Sieh auf deinem Smartphone nach einem neuen Kopplungscode und gib ihn hier ein.',
      landingEyebrow: 'Dein Smartphone. Dein Browser. Eine verbundene Fahrt.',
      landingTitle: 'Bring Android Auto in einen Browser in deiner Nähe.',
      landingLead: 'NavOnWeb verbindet sich über dein lokales Netzwerk, damit du die Projektion in einem kompatiblen Browser sehen, hören und steuern kannst.',
      landingHighlightsLabel: 'NavOnWeb-Highlights',
      landingHighlightLocal: 'Lokale Direktverbindung',
      landingHighlightMedia: 'Video, Ton und Touch',
      landingHighlightResponsive: 'Responsive Browseransicht',
      landingPlayStoreLabel: 'Verfügbarkeit bei Google Play',
      landingPlayStoreComingSoon: 'Bald bei Google Play',
      landingPlayStoreCta: 'NavOnWeb bei Google Play laden',
      landingPlayStoreHint: 'Erfordert ein Smartphone mit Android Auto und einen Browser im selben lokalen Netzwerk.',
      landingPeek: 'So funktioniert NavOnWeb',
      landingBandTitle: 'Der Android Auto-Bildschirm deines Smartphones, in einem Browser.',
      landingPremiumScreenshotAlt: 'Bildschirm des laufenden NavOnWeb Premium-Dienstes',
      landingBrowserEyebrow: 'Das verbundene Erlebnis',
      landingBrowserTitle: 'Deine Projektion, bereit im Browser.',
      landingBrowserLead: 'Nach der Kopplung füllt der Live-Bildschirm von Android Auto den verfügbaren Browserbereich mit Video, Ton und Touch-Steuerung.',
      landingBrowserScreenshotAlt: 'Illustration einer NavOnWeb-Browsersitzung mit KI-nachgebildeten Kartenbildern',
      landingBrowserCaption: 'Verbundene Sitzung in einem Browser — Kartenbilder zur Veranschaulichung mit KI nachgebildet',
      landingBenefitsEyebrow: 'Entwickelt für den Bildschirm, den du schon hast',
      landingBenefitsTitle: 'Eine vertraute Fahrt, ohne einen weiteren Fahrzeugadapter.',
      landingBenefitsLead: 'Behalte die unterstützte Projektionssitzung auf deinem Smartphone und bring sie in einen kompatiblen Browser im Fahrzeug, auf dem Tablet oder auf dem Laptop im selben Netzwerk.',
      landingLocalTitle: 'Lokal verbinden',
      landingLocalBody: 'Nutze den Hotspot deines Smartphones oder dasselbe WLAN. Die Projektionsmedien bleiben auf dem direkten Weg zwischen Browser und Smartphone.',
      landingAdaptiveTitle: 'Passt sich wechselnden Bildschirmen an',
      landingAdaptiveBody: 'Die Ansicht passt sich breiten, kompakten und hochformatigen Browserbereichen an und hält dabei die Touch-Koordinaten ausgerichtet.',
      landingRememberedTitle: 'Einmal koppeln, schnell zurückkehren',
      landingRememberedBody: 'Gib den Einmalcode ein, um einen Browser zu genehmigen. Dieser Browser wird für eine schnellere Neuverbindung gespeichert.',
      landingStepsEyebrow: 'So funktioniert es',
      landingStepsTitle: 'In wenigen Minuten im geparkten Zustand bereit.',
      landingStepInstallTitle: 'NavOnWeb installieren und vorbereiten',
      landingStepInstallBody: 'Die Erste-Schritte-Anleitung führt dich durch die erforderlichen Smartphone-Einstellungen.',
      landingStepServerTitle: 'Android Auto™ Head-Unit-Server starten',
      landingStepServerBody: 'NavOnWeb kann dich direkt zur passenden Android Auto™-Einstellungsseite bringen.',
      landingStepPairTitle: 'navonweb.com öffnen und koppeln',
      landingStepPairBody: 'Verbinde den Browser mit dem Netzwerk des Smartphones, gib oben den achtstelligen Code ein und beginne mit dem Ansehen.',
      landingSafety: 'Schließe die Einrichtung nur im sicher geparkten Zustand ab. Bediene während der Fahrt weder ein Smartphone noch einen Fahrzeugbildschirm.',
      landingPrivacyPolicy: 'Datenschutzerklärung',
      landingTrademark: 'Android Auto ist eine Marke von Google LLC. NavOnWeb ist ein unabhängiges Produkt und steht weder in Verbindung mit Google oder einem Fahrzeughersteller noch wird es von diesen unterstützt oder gesponsert.',
    }),
    fr: Object.freeze({
      browserPairing: 'Appairage du navigateur',
      pairingCodeLabel: 'Code d’appairage à 8 chiffres affiché sur votre téléphone',
      connect: 'Se connecter',
      pairingRememberedHint: 'Saisissez le code une seule fois : ce navigateur sera mémorisé.',
      androidAutoScreen: 'Écran Android Auto',
      projectionInputArea: 'Zone vidéo et de saisie tactile Android Auto',
      projectionFrameAlt: 'Vidéo de projection Android Auto',
      androidAutoWaiting: 'En attente d’Android Auto',
      fullscreenEnter: 'Plein écran',
      fullscreenExit: 'Quitter le plein écran',
      fullscreenEnterLabel: 'Afficher Android Auto en plein écran',
      fullscreenExitLabel: 'Quitter le plein écran Android Auto',
      normalViewState: 'Affichage standard',
      fullscreenViewState: 'Affichage en plein écran',
      theaterViewState: 'Affichage en mode cinéma',
      fullscreenHintWindows: 'Appuyez sur Échap pour quitter le plein écran.',
      fullscreenHintTouch: 'Écartez ou pincez deux doigts pour activer ou quitter le plein écran.',
      presentationControlsLabel: 'Commandes d’affichage de la projection',
      presentationOrientationLabel: 'Orientation',
      presentationOrientationAuto: 'Auto',
      presentationOrientationLandscape: 'Paysage',
      presentationOrientationPortrait: 'Portrait',
      presentationAlignmentLabel: 'Alignement',
      presentationAlignmentTrigger: 'Alignement : {alignment}. Ouvrir le sélecteur de position.',
      presentationAlignCenter: 'Centre',
      presentationAlignTop: 'Haut',
      presentationAlignBottom: 'Bas',
      presentationAlignLeft: 'Gauche',
      presentationAlignRight: 'Droite',
      presentationAlignTopLeft: 'En haut à gauche',
      presentationAlignTopRight: 'En haut à droite',
      presentationAlignBottomLeft: 'En bas à gauche',
      presentationAlignBottomRight: 'En bas à droite',
      presentationAlignCustom: 'Personnalisé',
      presentationGuideTitle: 'Commandes d’affichage en plein écran',
      presentationGuideOrientation: 'Choisissez Auto, Paysage ou Portrait avant de passer en plein écran.',
      presentationGuideAlignment: 'Avant de passer en plein écran, choisissez où aligner la vidéo lorsqu’elle est affichée avec des bandes noires.',
      presentationGuideMove: 'Déplacez deux doigts ensemble pour repositionner la vidéo dans les marges disponibles.',
      presentationGuideExit: 'Avant que le déplacement ne se verrouille, un pincement net vers l’intérieur quitte le plein écran.',
      presentationGuideReset: 'Appuyez avec trois doigts pour réinitialiser la position de la vidéo.',
      presentationGuideDoNotShowAgain: 'Ne plus afficher',
      presentationGuideDismiss: 'OK',
      presentationGuideAutoDismiss: 'Ce guide se ferme automatiquement dans {seconds} s.',
      presentationGuideAutoDismissStopped: 'La fermeture automatique a été arrêtée.',
      presentationGuideCountdownLabel: 'Temps restant avant la fermeture automatique de ce guide',
      presentationReset: 'La position de la vidéo a été réinitialisée.',
      announcements: 'Annonces',
      noticesLoading: 'Chargement des annonces…',
      noAnnouncements: 'Aucune annonce.',
      noticesUnavailable: 'Les annonces sont temporairement indisponibles.',
      noticesStale: 'Affichage des annonces enregistrées.',
      announcementUntitled: 'Annonce',
      premiumUpgradeMessage: 'Veuillez acheter le niveau Premium pour utiliser la vidéo haute résolution (1080p) et le son stéréo.',
      premiumDoNotShowAgain: 'Ne plus afficher',
      confirm: 'OK',
      connectionExpiredPhone: 'La connexion du navigateur a expiré. Saisissez le nouveau code affiché sur votre téléphone.',
      connectionExpired: 'La connexion du navigateur a expiré. Saisissez un nouveau code.',
      savedConnectionExpired: 'La connexion enregistrée a expiré. Saisissez le nouveau code affiché sur votre téléphone.',
      videoWaiting: 'En attente de la vidéo',
      videoWaitingElapsed: 'Temps d’attente {time}',
      mediaPermissionPrompt: 'Appuyez une fois pour activer le son et le microphone du navigateur.',
      mediaPermissionAllow: 'Activer le son et le microphone',
      mediaPermissionDenied: 'L’accès au microphone est bloqué. Autorisez-le dans les autorisations de ce site, puis réessayez.',
      localNetworkPrompt: 'Autorisez l’accès au réseau local pour vous connecter directement à votre téléphone.',
      localNetworkDenied: 'Le navigateur n’a pas pu confirmer l’accès au réseau local. Si la connexion échoue, vérifiez l’autorisation de ce site, puis réessayez.',
      localNetworkAllow: 'Autoriser le réseau local',
      localNetworkRetry: 'Réessayer',
      androidAutoReconnecting: 'Reconnexion à Android Auto',
      serverWaiting: 'En attente de la vidéo',
      localControlRecovering: 'Rétablissement de la connexion de contrôle locale.',
      viewportMainSessionOnly: 'Cet appareil n’est pas la session principale. Le format d’image et la zone d’affichage de la projection suivent la session principale sélectionnée sur votre téléphone.',
      eightDigitRequired: 'Saisissez un nombre à 8 chiffres.',
      connecting: 'Connexion…',
      invalidCode: 'Le code n’est pas valide.',
      codeNotRegistered: 'Le téléphone n’a pas encore enregistré ce code. Vérifiez que le service est en cours d’exécution et que le téléphone et le navigateur sont sur le même réseau, puis réessayez.',
      expiredCode: 'Le code a expiré. Consultez l’application pour obtenir un nouveau code.',
      retryLater: 'Réessayez dans un instant.',
      unableToConnect: 'Connexion impossible. Réessayez dans un instant.',
      repairPairing: 'Obtenir un nouveau code d’appairage',
      repairPairingLabel: 'Appairer avec un nouveau code',
      repairPairingHint: 'Consultez votre téléphone pour obtenir un nouveau code d’appairage, puis saisissez-le ici.',
      landingEyebrow: 'Votre téléphone. Votre navigateur. Un seul trajet connecté.',
      landingTitle: 'Affichez Android Auto dans un navigateur à proximité.',
      landingLead: 'NavOnWeb se connecte via votre réseau local pour que vous puissiez voir, entendre et contrôler la projection depuis un navigateur compatible.',
      landingHighlightsLabel: 'Points forts de NavOnWeb',
      landingHighlightLocal: 'Connexion locale en priorité',
      landingHighlightMedia: 'Vidéo, son et tactile',
      landingHighlightResponsive: 'Affichage adaptatif dans le navigateur',
      landingPlayStoreLabel: 'Disponibilité sur Google Play',
      landingPlayStoreComingSoon: 'Bientôt disponible sur Google Play',
      landingPlayStoreCta: 'Télécharger NavOnWeb sur Google Play',
      landingPlayStoreHint: 'Nécessite un téléphone compatible Android Auto et un navigateur sur le même réseau local.',
      landingPeek: 'Découvrez comment fonctionne NavOnWeb',
      landingBandTitle: 'L’écran Android Auto de votre téléphone, dans un navigateur.',
      landingPremiumScreenshotAlt: 'Écran du service NavOnWeb Premium en cours d’exécution',
      landingBrowserEyebrow: 'L’expérience connectée',
      landingBrowserTitle: 'Votre projection, prête dans le navigateur.',
      landingBrowserLead: 'Une fois l’appairage effectué, l’écran Android Auto en direct occupe l’espace disponible du navigateur, avec la vidéo, le son et les commandes tactiles.',
      landingBrowserScreenshotAlt: 'Illustration d’une session NavOnWeb dans un navigateur, avec des images de carte recréées par IA',
      landingBrowserCaption: 'Session connectée dans un navigateur — images de carte recréées par IA à titre d’illustration',
      landingBenefitsEyebrow: 'Conçu autour de l’écran que vous possédez déjà',
      landingBenefitsTitle: 'Une conduite familière, sans adaptateur supplémentaire pour le véhicule.',
      landingBenefitsLead: 'Conservez la session de projection prise en charge sur votre téléphone et affichez-la dans le navigateur compatible d’un véhicule, d’une tablette ou d’un ordinateur portable sur le même réseau.',
      landingLocalTitle: 'Connexion locale',
      landingLocalBody: 'Utilisez le point d’accès de votre téléphone ou le même réseau Wi-Fi. Les médias de projection restent sur le chemin direct entre le navigateur et le téléphone.',
      landingAdaptiveTitle: 'S’adapte aux changements d’écran',
      landingAdaptiveBody: 'L’affichage s’adapte aux espaces de navigateur larges, compacts et en portrait, tout en gardant les coordonnées tactiles alignées.',
      landingRememberedTitle: 'Appairez une fois, revenez rapidement',
      landingRememberedBody: 'Saisissez le code à usage unique pour approuver un navigateur. Ce navigateur est mémorisé pour une reconnexion plus rapide.',
      landingStepsEyebrow: 'Comment ça marche',
      landingStepsTitle: 'Prêt en quelques minutes, à l’arrêt.',
      landingStepInstallTitle: 'Installez et préparez NavOnWeb',
      landingStepInstallBody: 'Le guide de première utilisation vous accompagne dans les réglages requis du téléphone.',
      landingStepServerTitle: 'Démarrez le serveur d’unité principale Android Auto™',
      landingStepServerBody: 'NavOnWeb peut vous amener directement à la page des paramètres Android Auto™ concernée.',
      landingStepPairTitle: 'Ouvrez navonweb.com et appairez',
      landingStepPairBody: 'Connectez le navigateur au réseau du téléphone, saisissez le code à huit chiffres ci-dessus et commencez à regarder.',
      landingSafety: 'Effectuez la configuration uniquement à l’arrêt, en stationnement sécurisé. N’utilisez pas de téléphone ni d’écran de véhicule en conduisant.',
      landingPrivacyPolicy: 'Politique de confidentialité',
      landingTrademark: 'Android Auto est une marque de Google LLC. NavOnWeb est un produit indépendant, sans affiliation, approbation ni parrainage de la part de Google ou d’un quelconque constructeur automobile.',
    }),
    ja: Object.freeze({
      browserPairing: 'ブラウザのペアリング',
      pairingCodeLabel: 'スマートフォンに表示された8桁のペアリングコード',
      connect: '接続',
      pairingRememberedHint: 'コードを一度入力すると、このブラウザが記憶されます。',
      androidAutoScreen: 'Android Auto画面',
      projectionInputArea: 'Android Autoの映像とタッチ入力エリア',
      projectionFrameAlt: 'Android Autoのプロジェクション映像',
      androidAutoWaiting: 'Android Autoを待機中',
      fullscreenEnter: '全画面表示',
      fullscreenExit: '全画面表示を終了',
      fullscreenEnterLabel: 'Android Autoを全画面で表示',
      fullscreenExitLabel: 'Android Autoの全画面表示を終了',
      normalViewState: '標準表示中',
      fullscreenViewState: '全画面表示中',
      theaterViewState: 'シアターモード表示中',
      fullscreenHintWindows: 'Escキーを押すと全画面表示を終了します。',
      fullscreenHintTouch: 'ピンチアウト／ピンチインで全画面表示を切り替えます。',
      presentationControlsLabel: 'プロジェクション表示の設定',
      presentationOrientationLabel: '画面の向き',
      presentationOrientationAuto: '自動',
      presentationOrientationLandscape: '横向き',
      presentationOrientationPortrait: '縦向き',
      presentationAlignmentLabel: '配置',
      presentationAlignmentTrigger: '配置：{alignment}。位置の選択を開きます。',
      presentationAlignCenter: '中央',
      presentationAlignTop: '上',
      presentationAlignBottom: '下',
      presentationAlignLeft: '左',
      presentationAlignRight: '右',
      presentationAlignTopLeft: '左上',
      presentationAlignTopRight: '右上',
      presentationAlignBottomLeft: '左下',
      presentationAlignBottomRight: '右下',
      presentationAlignCustom: 'カスタム',
      presentationGuideTitle: '全画面表示の操作',
      presentationGuideOrientation: '全画面表示にする前に、自動、横向き、縦向きのいずれかを選択してください。',
      presentationGuideAlignment: '全画面表示にする前に、余白内で映像を配置する位置を選択してください。',
      presentationGuideMove: '2本の指をそろえて動かすと、余白の範囲内で映像の位置を移動できます。',
      presentationGuideExit: '移動が確定する前に、2本の指をはっきり閉じる（ピンチイン）と全画面表示を終了します。',
      presentationGuideReset: '3本の指で押すと、映像の位置がリセットされます。',
      presentationGuideDoNotShowAgain: '今後表示しない',
      presentationGuideDismiss: 'OK',
      presentationGuideAutoDismiss: 'このガイドは{seconds}秒後に自動的に閉じます。',
      presentationGuideAutoDismissStopped: '自動的に閉じる動作を停止しました。',
      presentationGuideCountdownLabel: 'このガイドが自動的に閉じるまでの時間',
      presentationReset: '映像の位置をリセットしました。',
      announcements: 'お知らせ',
      noticesLoading: 'お知らせを読み込み中…',
      noAnnouncements: 'お知らせはありません。',
      noticesUnavailable: 'お知らせは一時的に利用できません。',
      noticesStale: '保存されたお知らせを表示しています。',
      announcementUntitled: 'お知らせ',
      premiumUpgradeMessage: '高解像度（1080p）映像とステレオ音声を使用するには、プレミアムをご購入ください。',
      premiumDoNotShowAgain: '今後表示しない',
      confirm: 'OK',
      connectionExpiredPhone: 'ブラウザ接続の有効期限が切れました。スマートフォンに表示された新しいコードを入力してください。',
      connectionExpired: 'ブラウザ接続の有効期限が切れました。新しいコードを入力してください。',
      savedConnectionExpired: '保存された接続の有効期限が切れました。スマートフォンに表示された新しいコードを入力してください。',
      videoWaiting: '映像を待機中',
      videoWaitingElapsed: '待機時間 {time}',
      mediaPermissionPrompt: '一度タップすると、音声とブラウザのマイクが有効になります。',
      mediaPermissionAllow: '音声とマイクを有効にする',
      mediaPermissionDenied: 'マイクへのアクセスがブロックされています。このサイトの権限で許可してから、再試行してください。',
      localNetworkPrompt: 'スマートフォンに直接接続するには、ローカルネットワークへのアクセスを許可してください。',
      localNetworkDenied: 'ブラウザがローカルネットワークへのアクセスを確認できませんでした。接続できない場合は、このサイトの権限を確認して再試行してください。',
      localNetworkAllow: 'ローカルネットワークを許可',
      localNetworkRetry: '再試行',
      androidAutoReconnecting: 'Android Autoに再接続中',
      serverWaiting: '映像を待機中',
      localControlRecovering: 'ローカル操作接続を復旧しています。',
      viewportMainSessionOnly: 'このデバイスはメインセッションではありません。プロジェクションのアスペクト比と表示領域は、スマートフォンで選択したメインセッションに従います。',
      eightDigitRequired: '8桁の数字を入力してください。',
      connecting: '接続中…',
      invalidCode: 'コードが無効です。',
      codeNotRegistered: 'スマートフォンでこのコードはまだ登録されていません。サービスが実行中で、スマートフォンとブラウザが同じネットワークに接続されていることを確認してから、もう一度お試しください。',
      expiredCode: 'コードの有効期限が切れました。アプリで新しいコードを確認してください。',
      retryLater: 'しばらくしてからもう一度お試しください。',
      unableToConnect: '接続できません。しばらくしてからもう一度お試しください。',
      repairPairing: '新しいペアリングコードを取得',
      repairPairingLabel: '新しいコードでペアリング',
      repairPairingHint: 'スマートフォンで新しいペアリングコードを確認し、ここに入力してください。',
      landingEyebrow: 'あなたのスマートフォン。あなたのブラウザ。ひとつにつながるドライブ。',
      landingTitle: 'Android Autoを、近くのブラウザへ。',
      landingLead: 'NavOnWebはローカルネットワーク経由で接続し、対応ブラウザからプロジェクションを見て、聞いて、操作できるようにします。',
      landingHighlightsLabel: 'NavOnWebの特長',
      landingHighlightLocal: 'ローカル優先の接続',
      landingHighlightMedia: '映像、音声、タッチ',
      landingHighlightResponsive: 'レスポンシブなブラウザ表示',
      landingPlayStoreLabel: 'Google Playでの提供状況',
      landingPlayStoreComingSoon: 'Google Playで近日公開',
      landingPlayStoreCta: 'Google PlayでNavOnWebを入手',
      landingPlayStoreHint: 'Android Auto対応のスマートフォンと、同じローカルネットワーク上のブラウザが必要です。',
      landingPeek: 'NavOnWebの仕組みを見る',
      landingBandTitle: 'スマートフォンのAndroid Auto画面を、ブラウザで。',
      landingPremiumScreenshotAlt: 'NavOnWebプレミアムのサービス実行画面',
      landingBrowserEyebrow: 'つながる体験',
      landingBrowserTitle: 'プロジェクションが、ブラウザですぐに。',
      landingBrowserLead: 'ペアリングが完了すると、Android Autoのライブ画面がブラウザの表示スペースいっぱいに広がり、映像、音声、タッチ操作を利用できます。',
      landingBrowserScreenshotAlt: 'NavOnWebのブラウザセッションのイメージ（地図画像はAIで再現）',
      landingBrowserCaption: 'ブラウザでの接続セッション — 地図画像はイメージとしてAIで再現したものです',
      landingBenefitsEyebrow: '今ある画面を中心に設計',
      landingBenefitsTitle: '車載アダプターを追加せずに、いつものドライブを。',
      landingBenefitsLead: '対応するプロジェクションセッションはスマートフォン上に保ちながら、同じネットワーク上の対応する車両、タブレット、ノートパソコンのブラウザに表示します。',
      landingLocalTitle: 'ローカルで接続',
      landingLocalBody: 'スマートフォンのホットスポットまたは同じWi-Fiネットワークを使用します。プロジェクションのメディアは、ブラウザとスマートフォン間の直接経路のみを通ります。',
      landingAdaptiveTitle: '変化する画面にフィット',
      landingAdaptiveBody: 'タッチ座標のずれを防ぎながら、横長、コンパクト、縦長のブラウザスペースに表示を合わせます。',
      landingRememberedTitle: '一度ペアリングすれば、次回はすぐに',
      landingRememberedBody: 'ワンタイムコードを入力してブラウザを承認します。承認したブラウザは記憶され、次回はより速く再接続できます。',
      landingStepsEyebrow: '使い方',
      landingStepsTitle: '駐車中の数分で準備完了。',
      landingStepInstallTitle: 'NavOnWebをインストールして準備',
      landingStepInstallBody: '初回セットアップガイドが、必要なスマートフォンの設定を順に案内します。',
      landingStepServerTitle: 'Android Auto™ヘッドユニットサーバーを起動',
      landingStepServerBody: 'NavOnWebから該当するAndroid Auto™の設定ページを直接開けます。',
      landingStepPairTitle: 'navonweb.comを開いてペアリング',
      landingStepPairBody: 'ブラウザをスマートフォンのネットワークに接続し、上の入力欄に8桁のコードを入力すると視聴を開始できます。',
      landingSafety: 'セットアップは、安全な場所に駐車した状態でのみ行ってください。運転中はスマートフォンや車両の画面を操作しないでください。',
      landingPrivacyPolicy: 'プライバシーポリシー',
      landingTrademark: 'Android AutoはGoogle LLCの商標です。NavOnWebは独立した製品であり、Googleおよびいかなる自動車メーカーとも提携、承認、後援の関係にありません。',
    }),
    zh: Object.freeze({
      browserPairing: '浏览器配对',
      pairingCodeLabel: '手机上显示的 8 位配对码',
      connect: '连接',
      pairingRememberedHint: '只需输入一次代码，此浏览器就会被记住。',
      androidAutoScreen: 'Android Auto 屏幕',
      projectionInputArea: 'Android Auto 视频和触摸输入区域',
      projectionFrameAlt: 'Android Auto 投屏视频',
      androidAutoWaiting: '正在等待 Android Auto',
      fullscreenEnter: '全屏',
      fullscreenExit: '退出全屏',
      fullscreenEnterLabel: '全屏查看 Android Auto',
      fullscreenExitLabel: '退出 Android Auto 全屏',
      normalViewState: '正在显示标准视图',
      fullscreenViewState: '正在显示全屏',
      theaterViewState: '正在显示剧场模式',
      fullscreenHintWindows: '按 Esc 键退出全屏。',
      fullscreenHintTouch: '双指张开/捏合可切换全屏。',
      presentationControlsLabel: '投屏显示控件',
      presentationOrientationLabel: '方向',
      presentationOrientationAuto: '自动',
      presentationOrientationLandscape: '横屏',
      presentationOrientationPortrait: '竖屏',
      presentationAlignmentLabel: '对齐',
      presentationAlignmentTrigger: '对齐：{alignment}。打开位置选择器。',
      presentationAlignCenter: '居中',
      presentationAlignTop: '顶部',
      presentationAlignBottom: '底部',
      presentationAlignLeft: '左侧',
      presentationAlignRight: '右侧',
      presentationAlignTopLeft: '左上',
      presentationAlignTopRight: '右上',
      presentationAlignBottomLeft: '左下',
      presentationAlignBottomRight: '右下',
      presentationAlignCustom: '自定义',
      presentationGuideTitle: '全屏显示控件',
      presentationGuideOrientation: '进入全屏前，请选择自动、横屏或竖屏。',
      presentationGuideAlignment: '进入全屏前，请选择留有黑边时视频的对齐位置。',
      presentationGuideMove: '两指并拢移动可在可用边距内重新定位视频。',
      presentationGuideExit: '在移动锁定之前，明显的双指捏合会退出全屏。',
      presentationGuideReset: '三指按压可重置视频位置。',
      presentationGuideDoNotShowAgain: '不再显示',
      presentationGuideDismiss: '确定',
      presentationGuideAutoDismiss: '本指南将在 {seconds} 秒后自动关闭。',
      presentationGuideAutoDismissStopped: '自动关闭已停止。',
      presentationGuideCountdownLabel: '距本指南自动关闭的剩余时间',
      presentationReset: '视频位置已重置。',
      announcements: '公告',
      noticesLoading: '正在加载公告…',
      noAnnouncements: '暂无公告。',
      noticesUnavailable: '公告暂时不可用。',
      noticesStale: '正在显示已保存的公告。',
      announcementUntitled: '公告',
      premiumUpgradeMessage: '请购买 Premium 以使用高分辨率（1080p）视频和立体声音频。',
      premiumDoNotShowAgain: '不再显示',
      confirm: '确定',
      connectionExpiredPhone: '浏览器连接已过期。请输入手机上显示的新代码。',
      connectionExpired: '浏览器连接已过期。请输入新代码。',
      savedConnectionExpired: '已保存的连接已过期。请输入手机上显示的新代码。',
      videoWaiting: '正在等待视频',
      videoWaitingElapsed: '等待时间 {time}',
      mediaPermissionPrompt: '点按一次即可启用声音和浏览器麦克风。',
      mediaPermissionAllow: '启用声音和麦克风',
      mediaPermissionDenied: '麦克风访问已被阻止。请在此网站的权限中允许，然后重试。',
      localNetworkPrompt: '请允许本地网络访问，以便直接连接到您的手机。',
      localNetworkDenied: '浏览器无法确认本地网络访问权限。如果连接失败，请检查此网站的权限，然后重试。',
      localNetworkAllow: '允许本地网络',
      localNetworkRetry: '重试',
      androidAutoReconnecting: '正在重新连接到 Android Auto',
      serverWaiting: '正在等待视频',
      localControlRecovering: '正在恢复本地控制连接。',
      viewportMainSessionOnly: '此设备不是主会话。投屏宽高比和视口将跟随您在手机上选择的主会话。',
      eightDigitRequired: '请输入 8 位数字。',
      connecting: '正在连接…',
      invalidCode: '代码无效。',
      codeNotRegistered: '手机尚未注册此代码。请确认服务正在运行，并且手机和浏览器处于同一网络，然后重试。',
      expiredCode: '代码已过期。请在应用中查看新代码。',
      retryLater: '请稍后重试。',
      unableToConnect: '无法连接。请稍后重试。',
      repairPairing: '获取新配对码',
      repairPairingLabel: '使用新代码配对',
      repairPairingHint: '请在手机上查看新配对码，然后在此输入。',
      landingEyebrow: '您的手机。您的浏览器。一路相连。',
      landingTitle: '将 Android Auto 带入附近的浏览器。',
      landingLead: 'NavOnWeb 通过您的本地网络进行连接，让您可以在兼容的浏览器中观看、收听和控制投屏。',
      landingHighlightsLabel: 'NavOnWeb 亮点',
      landingHighlightLocal: '本地优先的连接',
      landingHighlightMedia: '视频、声音和触摸',
      landingHighlightResponsive: '自适应浏览器视图',
      landingPlayStoreLabel: 'Google Play 上架情况',
      landingPlayStoreComingSoon: '即将登陆 Google Play',
      landingPlayStoreCta: '在 Google Play 上获取 NavOnWeb',
      landingPlayStoreHint: '需要一部支持 Android Auto 的手机和同一本地网络中的浏览器。',
      landingPeek: '了解 NavOnWeb 的工作原理',
      landingBandTitle: '您手机上的 Android Auto 屏幕，呈现在浏览器中。',
      landingPremiumScreenshotAlt: 'NavOnWeb Premium 服务运行画面',
      landingBrowserEyebrow: '互联体验',
      landingBrowserTitle: '您的投屏，已在浏览器中就绪。',
      landingBrowserLead: '配对后，实时的 Android Auto 屏幕会填满可用的浏览器空间，并提供视频、声音和触摸控制。',
      landingBrowserScreenshotAlt: 'NavOnWeb 浏览器会话示意图，地图图像由 AI 重新生成',
      landingBrowserCaption: '浏览器中的已连接会话 — 地图图像由 AI 重新生成，仅供示意',
      landingBenefitsEyebrow: '围绕您已有的屏幕而设计',
      landingBenefitsTitle: '熟悉的驾驶体验，无需另购车载适配器。',
      landingBenefitsLead: '让受支持的投屏会话留在您的手机上，并将其带到同一网络中兼容的车辆、平板电脑或笔记本电脑浏览器。',
      landingLocalTitle: '本地连接',
      landingLocalBody: '使用您手机的热点或同一 Wi-Fi 网络。投屏媒体始终经由浏览器与手机之间的直接路径传输。',
      landingAdaptiveTitle: '适应多变的屏幕',
      landingAdaptiveBody: '视图会适应宽屏、紧凑和竖屏的浏览器空间，同时保持触摸坐标对齐。',
      landingRememberedTitle: '配对一次，快速回连',
      landingRememberedBody: '输入一次性代码以批准浏览器。该浏览器会被记住，以便更快地重新连接。',
      landingStepsEyebrow: '工作原理',
      landingStepsTitle: '停好车，几分钟即可就绪。',
      landingStepInstallTitle: '安装并准备 NavOnWeb',
      landingStepInstallBody: '首次使用指南会引导您完成所需的手机设置。',
      landingStepServerTitle: '启动 Android Auto™ 车机服务器',
      landingStepServerBody: 'NavOnWeb 可以直接带您进入相关的 Android Auto™ 设置页面。',
      landingStepPairTitle: '打开 navonweb.com 并配对',
      landingStepPairBody: '将浏览器连接到手机的网络，在上方输入 8 位代码，然后开始观看。',
      landingSafety: '请仅在安全停车后完成设置。驾驶时请勿操作手机或车辆屏幕。',
      landingPrivacyPolicy: '隐私权政策',
      landingTrademark: 'Android Auto 是 Google LLC 的商标。NavOnWeb 是独立产品，与 Google 或任何车辆制造商均无关联，也未获得其背书或赞助。',
    }),
    ru: Object.freeze({
      browserPairing: 'Сопряжение с браузером',
      pairingCodeLabel: '8-значный код сопряжения, показанный на телефоне',
      connect: 'Подключить',
      pairingRememberedHint: 'Введите код один раз, и этот браузер будет запомнен.',
      androidAutoScreen: 'Экран Android Auto',
      projectionInputArea: 'Область видео и сенсорного ввода Android Auto',
      projectionFrameAlt: 'Видео проекции Android Auto',
      androidAutoWaiting: 'Ожидание Android Auto',
      fullscreenEnter: 'Во весь экран',
      fullscreenExit: 'Выйти из полноэкранного режима',
      fullscreenEnterLabel: 'Открыть Android Auto во весь экран',
      fullscreenExitLabel: 'Выйти из полноэкранного режима Android Auto',
      normalViewState: 'Показан стандартный вид',
      fullscreenViewState: 'Показан полноэкранный режим',
      theaterViewState: 'Показан режим кинотеатра',
      fullscreenHintWindows: 'Нажмите Esc, чтобы выйти из полноэкранного режима.',
      fullscreenHintTouch: 'Разведите или сведите пальцы, чтобы переключить полноэкранный режим.',
      presentationControlsLabel: 'Управление отображением проекции',
      presentationOrientationLabel: 'Ориентация',
      presentationOrientationAuto: 'Авто',
      presentationOrientationLandscape: 'Альбомная',
      presentationOrientationPortrait: 'Книжная',
      presentationAlignmentLabel: 'Выравнивание',
      presentationAlignmentTrigger: 'Выравнивание: {alignment}. Открыть выбор положения.',
      presentationAlignCenter: 'По центру',
      presentationAlignTop: 'Сверху',
      presentationAlignBottom: 'Снизу',
      presentationAlignLeft: 'Слева',
      presentationAlignRight: 'Справа',
      presentationAlignTopLeft: 'Сверху слева',
      presentationAlignTopRight: 'Сверху справа',
      presentationAlignBottomLeft: 'Снизу слева',
      presentationAlignBottomRight: 'Снизу справа',
      presentationAlignCustom: 'Своё',
      presentationGuideTitle: 'Управление полноэкранным отображением',
      presentationGuideOrientation: 'Перед переходом в полноэкранный режим выберите «Авто», «Альбомная» или «Книжная».',
      presentationGuideAlignment: 'Перед переходом в полноэкранный режим выберите, как выровнять видео внутри полей.',
      presentationGuideMove: 'Двигайте двумя пальцами одновременно, чтобы переместить видео в пределах доступных полей.',
      presentationGuideExit: 'Пока не началось перемещение, отчётливое сведение пальцев завершает полноэкранный режим.',
      presentationGuideReset: 'Нажмите тремя пальцами, чтобы сбросить положение видео.',
      presentationGuideDoNotShowAgain: 'Больше не показывать',
      presentationGuideDismiss: 'ОК',
      presentationGuideAutoDismiss: 'Это руководство закроется автоматически через {seconds} с.',
      presentationGuideAutoDismissStopped: 'Автоматическое закрытие остановлено.',
      presentationGuideCountdownLabel: 'Время до автоматического закрытия этого руководства',
      presentationReset: 'Положение видео сброшено.',
      announcements: 'Объявления',
      noticesLoading: 'Загрузка объявлений…',
      noAnnouncements: 'Объявлений нет.',
      noticesUnavailable: 'Объявления временно недоступны.',
      noticesStale: 'Показаны сохранённые объявления.',
      announcementUntitled: 'Объявление',
      premiumUpgradeMessage: 'Приобретите уровень Премиум, чтобы использовать видео высокого разрешения (1080p) и стереозвук.',
      premiumDoNotShowAgain: 'Больше не показывать',
      confirm: 'ОК',
      connectionExpiredPhone: 'Срок подключения браузера истёк. Введите новый код, показанный на телефоне.',
      connectionExpired: 'Срок подключения браузера истёк. Введите новый код.',
      savedConnectionExpired: 'Срок сохранённого подключения истёк. Введите новый код, показанный на телефоне.',
      videoWaiting: 'Ожидание видео',
      videoWaitingElapsed: 'Время ожидания {time}',
      mediaPermissionPrompt: 'Нажмите один раз, чтобы включить звук и микрофон браузера.',
      mediaPermissionAllow: 'Включить звук и микрофон',
      mediaPermissionDenied: 'Доступ к микрофону заблокирован. Разрешите его в разрешениях этого сайта и повторите попытку.',
      localNetworkPrompt: 'Разрешите доступ к локальной сети, чтобы подключиться к телефону напрямую.',
      localNetworkDenied: 'Браузер не смог подтвердить доступ к локальной сети. Если подключение не удаётся, проверьте разрешение этого сайта и повторите попытку.',
      localNetworkAllow: 'Разрешить доступ к локальной сети',
      localNetworkRetry: 'Повторить',
      androidAutoReconnecting: 'Повторное подключение к Android Auto',
      serverWaiting: 'Ожидание видео',
      localControlRecovering: 'Восстановление локального канала управления.',
      viewportMainSessionOnly: 'Это устройство не является основным сеансом. Соотношение сторон проекции и область просмотра следуют за основным сеансом, выбранным на телефоне.',
      eightDigitRequired: 'Введите 8-значное число.',
      connecting: 'Подключение…',
      invalidCode: 'Недействительный код.',
      codeNotRegistered: 'Телефон ещё не зарегистрировал этот код. Убедитесь, что служба запущена, а телефон и браузер подключены к одной сети, затем повторите попытку.',
      expiredCode: 'Срок действия кода истёк. Получите новый код в приложении.',
      retryLater: 'Повторите попытку через некоторое время.',
      unableToConnect: 'Не удалось подключиться. Повторите попытку через некоторое время.',
      repairPairing: 'Получить новый код сопряжения',
      repairPairingLabel: 'Сопряжение с новым кодом',
      repairPairingHint: 'Найдите новый код сопряжения на телефоне и введите его здесь.',
      landingEyebrow: 'Ваш телефон. Ваш браузер. Одна поездка на связи.',
      landingTitle: 'Перенесите Android Auto в браузер рядом с вами.',
      landingLead: 'NavOnWeb подключается через вашу локальную сеть, чтобы вы могли видеть, слышать и управлять проекцией из совместимого браузера.',
      landingHighlightsLabel: 'Основные возможности NavOnWeb',
      landingHighlightLocal: 'Локальное подключение',
      landingHighlightMedia: 'Видео, звук и касания',
      landingHighlightResponsive: 'Адаптивный вид в браузере',
      landingPlayStoreLabel: 'Доступность в Google Play',
      landingPlayStoreComingSoon: 'Скоро в Google Play',
      landingPlayStoreCta: 'Скачать NavOnWeb в Google Play',
      landingPlayStoreHint: 'Нужен телефон с Android Auto и браузер в той же локальной сети.',
      landingPeek: 'Посмотрите, как работает NavOnWeb',
      landingBandTitle: 'Экран Android Auto вашего телефона — в браузере.',
      landingPremiumScreenshotAlt: 'Экран работающей службы NavOnWeb Премиум',
      landingBrowserEyebrow: 'Как это выглядит после подключения',
      landingBrowserTitle: 'Ваша проекция, готовая в браузере.',
      landingBrowserLead: 'После сопряжения живой экран Android Auto заполняет доступное пространство браузера с видео, звуком и сенсорным управлением.',
      landingBrowserScreenshotAlt: 'Иллюстрация сеанса NavOnWeb в браузере с картой, воссозданной с помощью ИИ',
      landingBrowserCaption: 'Подключённый сеанс в браузере — изображение карты воссоздано с помощью ИИ в качестве иллюстрации',
      landingBenefitsEyebrow: 'Создано вокруг экрана, который у вас уже есть',
      landingBenefitsTitle: 'Привычная поездка без ещё одного автомобильного адаптера.',
      landingBenefitsLead: 'Оставьте поддерживаемый сеанс проекции на телефоне и перенесите его в совместимый браузер автомобиля, планшета или ноутбука в той же сети.',
      landingLocalTitle: 'Подключение по локальной сети',
      landingLocalBody: 'Используйте точку доступа телефона или ту же сеть Wi-Fi. Медиаданные проекции остаются на прямом пути между браузером и телефоном.',
      landingAdaptiveTitle: 'Подстраивается под разные экраны',
      landingAdaptiveBody: 'Вид адаптируется к широким, компактным и вертикальным окнам браузера, сохраняя точность координат касаний.',
      landingRememberedTitle: 'Одно сопряжение — быстрый возврат',
      landingRememberedBody: 'Введите одноразовый код, чтобы одобрить браузер. Этот браузер запоминается для более быстрого повторного подключения.',
      landingStepsEyebrow: 'Как это работает',
      landingStepsTitle: 'Готово за несколько минут на парковке.',
      landingStepInstallTitle: 'Установите и подготовьте NavOnWeb',
      landingStepInstallBody: 'Руководство по началу работы проведёт вас по необходимым настройкам телефона.',
      landingStepServerTitle: 'Запустите сервер головного устройства Android Auto™',
      landingStepServerBody: 'NavOnWeb может сразу открыть нужную страницу настроек Android Auto™.',
      landingStepPairTitle: 'Откройте navonweb.com и выполните сопряжение',
      landingStepPairBody: 'Подключите браузер к сети телефона, введите 8-значный код выше и начните просмотр.',
      landingSafety: 'Выполняйте настройку только на безопасной парковке. Не пользуйтесь телефоном или экраном автомобиля во время движения.',
      landingPrivacyPolicy: 'Политика конфиденциальности',
      landingTrademark: 'Android Auto является товарным знаком Google LLC. NavOnWeb — независимый продукт, не связанный с Google или каким-либо производителем автомобилей, не одобренный и не спонсируемый ими.',
    }),
    tr: Object.freeze({
      browserPairing: 'Tarayıcı eşleme',
      pairingCodeLabel: 'Telefonunuzda gösterilen 8 haneli eşleme kodu',
      connect: 'Bağlan',
      pairingRememberedHint: 'Kodu bir kez girin; bu tarayıcı hatırlanacak.',
      androidAutoScreen: 'Android Auto ekranı',
      projectionInputArea: 'Android Auto video ve dokunmatik giriş alanı',
      projectionFrameAlt: 'Android Auto yansıtma videosu',
      androidAutoWaiting: 'Android Auto bekleniyor',
      fullscreenEnter: 'Tam ekran',
      fullscreenExit: 'Tam ekrandan çık',
      fullscreenEnterLabel: 'Android Auto’yu tam ekranda görüntüle',
      fullscreenExitLabel: 'Android Auto tam ekranından çık',
      normalViewState: 'Standart görünüm gösteriliyor',
      fullscreenViewState: 'Tam ekran gösteriliyor',
      theaterViewState: 'Sinema modu gösteriliyor',
      fullscreenHintWindows: 'Tam ekrandan çıkmak için Esc tuşuna basın.',
      fullscreenHintTouch: 'Tam ekranı açıp kapatmak için iki parmağınızı açın veya kapatın.',
      presentationControlsLabel: 'Yansıtma ekranı kontrolleri',
      presentationOrientationLabel: 'Yön',
      presentationOrientationAuto: 'Otomatik',
      presentationOrientationLandscape: 'Yatay',
      presentationOrientationPortrait: 'Dikey',
      presentationAlignmentLabel: 'Hizalama',
      presentationAlignmentTrigger: 'Hizalama: {alignment}. Konum seçiciyi aç.',
      presentationAlignCenter: 'Orta',
      presentationAlignTop: 'Üst',
      presentationAlignBottom: 'Alt',
      presentationAlignLeft: 'Sol',
      presentationAlignRight: 'Sağ',
      presentationAlignTopLeft: 'Sol üst',
      presentationAlignTopRight: 'Sağ üst',
      presentationAlignBottomLeft: 'Sol alt',
      presentationAlignBottomRight: 'Sağ alt',
      presentationAlignCustom: 'Özel',
      presentationGuideTitle: 'Tam ekran görüntüleme kontrolleri',
      presentationGuideOrientation: 'Tam ekrana geçmeden önce Otomatik, Yatay veya Dikey seçeneğini belirleyin.',
      presentationGuideAlignment: 'Tam ekrana geçmeden önce kenar boşluklu videonun nereye hizalanacağını seçin.',
      presentationGuideMove: 'Videoyu kullanılabilir kenar boşlukları içinde yeniden konumlandırmak için iki parmağınızı birlikte hareket ettirin.',
      presentationGuideExit: 'Hareket kilitlenmeden önce iki parmağınızı belirgin şekilde birbirine yaklaştırırsanız tam ekrandan çıkılır.',
      presentationGuideReset: 'Video konumunu sıfırlamak için üç parmağınızla basın.',
      presentationGuideDoNotShowAgain: 'Bir daha gösterme',
      presentationGuideDismiss: 'Tamam',
      presentationGuideAutoDismiss: 'Bu kılavuz {seconds} saniye içinde otomatik olarak kapanır.',
      presentationGuideAutoDismissStopped: 'Otomatik kapanma durduruldu.',
      presentationGuideCountdownLabel: 'Bu kılavuzun otomatik olarak kapanmasına kalan süre',
      presentationReset: 'Video konumu sıfırlandı.',
      announcements: 'Duyurular',
      noticesLoading: 'Duyurular yükleniyor…',
      noAnnouncements: 'Duyuru yok.',
      noticesUnavailable: 'Duyurular geçici olarak kullanılamıyor.',
      noticesStale: 'Kaydedilmiş duyurular gösteriliyor.',
      announcementUntitled: 'Duyuru',
      premiumUpgradeMessage: 'Yüksek çözünürlüklü (1080p) video ve stereo ses kullanmak için lütfen Premium paketini satın alın.',
      premiumDoNotShowAgain: 'Bir daha gösterme',
      confirm: 'Tamam',
      connectionExpiredPhone: 'Tarayıcı bağlantısının süresi doldu. Telefonunuzda gösterilen yeni kodu girin.',
      connectionExpired: 'Tarayıcı bağlantısının süresi doldu. Yeni bir kod girin.',
      savedConnectionExpired: 'Kayıtlı bağlantının süresi doldu. Telefonunuzda gösterilen yeni kodu girin.',
      videoWaiting: 'Video bekleniyor',
      videoWaitingElapsed: 'Bekleme süresi {time}',
      mediaPermissionPrompt: 'Sesi ve tarayıcı mikrofonunu etkinleştirmek için bir kez dokunun.',
      mediaPermissionAllow: 'Sesi ve mikrofonu etkinleştir',
      mediaPermissionDenied: 'Mikrofon erişimi engellendi. Bu sitenin izinlerinden izin verip yeniden deneyin.',
      localNetworkPrompt: 'Telefonunuza doğrudan bağlanmak için yerel ağ erişimine izin verin.',
      localNetworkDenied: 'Tarayıcı, yerel ağ erişimini doğrulayamadı. Bağlantı başarısız olursa bu sitenin iznini kontrol edip yeniden deneyin.',
      localNetworkAllow: 'Yerel ağa izin ver',
      localNetworkRetry: 'Yeniden dene',
      androidAutoReconnecting: 'Android Auto’ya yeniden bağlanılıyor',
      serverWaiting: 'Video bekleniyor',
      localControlRecovering: 'Yerel kontrol bağlantısı kurtarılıyor.',
      viewportMainSessionOnly: 'Bu cihaz ana oturum değil. Yansıtmanın en boy oranı ve görünüm alanı, telefonunuzda seçilen ana oturumu izler.',
      eightDigitRequired: '8 haneli bir sayı girin.',
      connecting: 'Bağlanılıyor…',
      invalidCode: 'Kod geçerli değil.',
      codeNotRegistered: 'Telefon bu kodu henüz kaydetmedi. Hizmetin çalıştığından ve telefon ile tarayıcının aynı ağda olduğundan emin olup tekrar deneyin.',
      expiredCode: 'Kodun süresi doldu. Yeni bir kod için uygulamayı kontrol edin.',
      retryLater: 'Birazdan tekrar deneyin.',
      unableToConnect: 'Bağlanılamıyor. Birazdan tekrar deneyin.',
      repairPairing: 'Yeni eşleme kodu al',
      repairPairingLabel: 'Yeni kodla eşle',
      repairPairingHint: 'Telefonunuzda yeni eşleme kodunu kontrol edip buraya girin.',
      landingEyebrow: 'Telefonunuz. Tarayıcınız. Tek bir bağlantılı sürüş.',
      landingTitle: 'Android Auto’yu yakınınızdaki bir tarayıcıya taşıyın.',
      landingLead: 'NavOnWeb, yerel ağınız üzerinden bağlanır; böylece yansıtmayı uyumlu bir tarayıcıdan görebilir, duyabilir ve kontrol edebilirsiniz.',
      landingHighlightsLabel: 'NavOnWeb’in öne çıkan özellikleri',
      landingHighlightLocal: 'Yerel öncelikli bağlantı',
      landingHighlightMedia: 'Video, ses ve dokunma',
      landingHighlightResponsive: 'Duyarlı tarayıcı görünümü',
      landingPlayStoreLabel: 'Google Play’de kullanılabilirlik',
      landingPlayStoreComingSoon: 'Yakında Google Play’de',
      landingPlayStoreCta: 'NavOnWeb’i Google Play’den edinin',
      landingPlayStoreHint: 'Android Auto destekli bir telefon ve aynı yerel ağda bir tarayıcı gerekir.',
      landingPeek: 'NavOnWeb’in nasıl çalıştığını görün',
      landingBandTitle: 'Telefonunuzun Android Auto ekranı, bir tarayıcıda.',
      landingPremiumScreenshotAlt: 'NavOnWeb Premium hizmetinin çalıştığı ekran',
      landingBrowserEyebrow: 'Bağlantılı deneyim',
      landingBrowserTitle: 'Yansıtmanız, tarayıcıda hazır.',
      landingBrowserLead: 'Eşleme tamamlandığında canlı Android Auto ekranı, tarayıcıdaki kullanılabilir alanı video, ses ve dokunmatik kontrollerle doldurur.',
      landingBrowserScreenshotAlt: 'Yapay zekâyla yeniden oluşturulmuş harita görselleri içeren NavOnWeb tarayıcı oturumu illüstrasyonu',
      landingBrowserCaption: 'Tarayıcıda bağlı oturum — harita görselleri, gösterim amacıyla yapay zekâyla yeniden oluşturulmuştur',
      landingBenefitsEyebrow: 'Zaten sahip olduğunuz ekran etrafında tasarlandı',
      landingBenefitsTitle: 'Ek bir araç adaptörü olmadan tanıdık bir sürüş.',
      landingBenefitsLead: 'Desteklenen yansıtma oturumunu telefonunuzda tutun ve aynı ağdaki uyumlu bir araç, tablet veya dizüstü bilgisayar tarayıcısına taşıyın.',
      landingLocalTitle: 'Yerel olarak bağlanın',
      landingLocalBody: 'Telefonunuzun hotspot’unu veya aynı Wi-Fi ağını kullanın. Yansıtma medyası, tarayıcı ile telefon arasındaki doğrudan yolda kalır.',
      landingAdaptiveTitle: 'Değişen ekranlara uyum sağlar',
      landingAdaptiveBody: 'Görünüm; geniş, dar ve dikey tarayıcı alanlarına uyum sağlarken dokunma koordinatlarını hizalı tutar.',
      landingRememberedTitle: 'Bir kez eşleyin, hızlıca geri dönün',
      landingRememberedBody: 'Bir tarayıcıyı onaylamak için tek kullanımlık kodu girin. Bu tarayıcı, daha hızlı yeniden bağlanabilmesi için hatırlanır.',
      landingStepsEyebrow: 'Nasıl çalışır?',
      landingStepsTitle: 'Park hâlindeyken birkaç dakikada hazır.',
      landingStepInstallTitle: 'NavOnWeb’i yükleyin ve hazırlayın',
      landingStepInstallBody: 'İlk kullanım rehberi, gerekli telefon ayarlarında size adım adım yol gösterir.',
      landingStepServerTitle: 'Android Auto™ ana birim sunucusunu başlatın',
      landingStepServerBody: 'NavOnWeb sizi doğrudan ilgili Android Auto™ ayarlar sayfasına yönlendirebilir.',
      landingStepPairTitle: 'navonweb.com’u açın ve eşleyin',
      landingStepPairBody: 'Tarayıcıyı telefonun ağına bağlayın, yukarıdaki sekiz haneli kodu girin ve izlemeye başlayın.',
      landingSafety: 'Kurulumu yalnızca güvenli bir şekilde park etmişken tamamlayın. Sürüş sırasında telefonu veya araç ekranını kullanmayın.',
      landingPrivacyPolicy: 'Gizlilik Politikası',
      landingTrademark: 'Android Auto, Google LLC’nin ticari markasıdır. NavOnWeb bağımsız bir üründür; Google veya herhangi bir araç üreticisiyle bağlantılı değildir ve bunlar tarafından onaylanmamış ya da sponsorluğu üstlenilmemiştir.',
    }),
  });
  const PATH_LOCALE = resolvePathLocale(window.location.pathname);
  let ACTIVE_LOCALE = PATH_LOCALE || resolveSystemLocale();
  const browserLanguageCandidates = resolveBrowserLanguageCandidates();
  let NOTICE_LOCALE_CANDIDATES = resolveNoticeLocaleCandidates(
    PATH_LOCALE ? [PATH_LOCALE, ...browserLanguageCandidates] : browserLanguageCandidates,
    ACTIVE_LOCALE
  );
  let NOTICE_DATE_TIME_LOCALE = resolveDateTimeLocale(
    NOTICE_LOCALE_CANDIDATES,
    ACTIVE_LOCALE
  );
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
  const sessionTouchOverlay = document.querySelector('#session-touch-overlay');
  const frame = document.querySelector('#frame');
  const webRtcVideo = document.querySelector('#webrtc-video');
  const streamState = document.querySelector('#stream-state');
  const streamStateMessage = document.querySelector('#stream-state-message');
  const streamWaitTime = document.querySelector('#stream-wait-time');
  const localNetworkPanel = document.querySelector('#local-network-panel');
  const localNetworkMessage = document.querySelector('#local-network-message');
  const localNetworkAllow = document.querySelector('#local-network-allow');
  const mediaPermissionPanel = document.querySelector('#media-permission-panel');
  const mediaPermissionMessage = document.querySelector('#media-permission-message');
  const mediaPermissionAllow = document.querySelector('#media-permission-allow');
  const repairPairingPanel = document.querySelector('#repair-pairing-panel');
  const repairPairingButton = document.querySelector('#repair-pairing');
  const viewerControls = document.querySelector('#viewer-controls');
  const viewportAuthorityNotice = document.querySelector('#viewport-authority-notice');
  const fullscreenButton = document.querySelector('#fullscreen');
  const presentationOrientationInputs = Array.from(
    document.querySelectorAll('input[name="presentation-orientation"]')
  );
  const presentationAlignmentLabel = document.querySelector('#presentation-alignment-label');
  const presentationAlignmentTrigger = document.querySelector('#presentation-alignment-trigger');
  const presentationAlignmentPicker = document.querySelector('#presentation-alignment-picker');
  const presentationAlignmentSelect = document.querySelector('#presentation-alignment');
  const presentationAlignmentInputs = Array.from(
    document.querySelectorAll('input[name="presentation-alignment-choice"]')
  );
  const presentationGuide = document.querySelector('#presentation-guide');
  const presentationGuideCard = document.querySelector('#presentation-guide-card');
  const presentationGuideDismissForever = document.querySelector(
    '#presentation-guide-dismiss-forever'
  );
  const presentationGuideDismiss = document.querySelector('#presentation-guide-dismiss');
  const presentationGuideCountdown = document.querySelector('#presentation-guide-countdown');
  const presentationGuideCountdownText = document.querySelector('#presentation-guide-countdown-text');
  const presentationGuideProgress = document.querySelector('#presentation-guide-progress');
  const fullscreenState = document.querySelector('#fullscreen-state');
  const fullscreenHint = document.querySelector('#fullscreen-hint');
  const expandedViewportProbe = document.querySelector('#expanded-viewport-probe');
  const premiumPrompt = document.querySelector('#premium-prompt');
  const premiumPromptDismiss = document.querySelector('#premium-prompt-dismiss');
  const premiumPromptConfirm = document.querySelector('#premium-prompt-confirm');
  const noticePanel = document.querySelector('#notice-panel');
  const noticeSummary = noticePanel.querySelector('summary');
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
  let streamStateKey = 'androidAutoWaiting';
  let videoWaitingStartedAt = null;
  let videoWaitingTimer = 0;
  let pairingInFlight = false;
  let repairPairingTimer = 0;
  let freshCloudRelayRouteRequired = loadFreshCloudRelayRouteRequirement();
  let activePointerId = null;
  let lastPointerPosition = {x: 0, y: 0};
  let lastMove = 0;
  const touchPointers = new Map();
  let pinchGesture = null;
  let suppressAndroidAutoTouch = false;
  let pendingSingleTouch = null;
  let pendingSingleTouchTimer = 0;
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
  let webRtcControlOpenTimer = 0;
  let webRtcControlNegotiatingGeneration = 0;
  let localControlCutover = false;
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
  let webRtcSessionCloseBarrier = null;
  let webRtcIcePairPublishedGeneration = 0;
  let webRtcVideoStatsTimer = 0;
  let webRtcVideoStatsGeneration = 0;
  let webRtcVideoStatsPrevious = null;
  let localNetworkPermissionState = CLOUD_RELAY_MODE ? 'checking' : 'not_applicable';
  let localNetworkPermissionStatus = null;
  let localNetworkPermissionQuery = null;
  let localNetworkPermissionRequestInFlight = false;
  let androidAutoInteractive = false;
  let androidAutoTouchReady = false;
  let browserSessionAccess = 'read_only';
  let browserSessionRole = 'viewer';
  let browserSessionDeviceId = '';
  let browserSessionColorSlot = 0;
  let browserSessionMetadataSupported = false;
  let viewportAuthorityRejected = false;
  let viewportAuthorityNoticeState = 'hidden';
  let viewportAuthorityNoticeTimer = 0;
  let viewportAuthorityNoticeTimerGeneration = 0;
  let viewportAuthorityNoticeEligibleState = false;
  let viewportAuthorityNoticeExpandedState = false;
  const remoteTouchMarkers = new Map();
  let pageActive = true;
  let theaterMode = false;
  let screenWakeLock = null;
  let screenWakeLockRequest = null;
  let screenWakeLockGeneration = 0;
  let expandedViewRequestGeneration = 0;
  let fullscreenEntryPendingGeneration = 0;
  // True while the user wants the expanded view; a native fullscreen entry that lands after
  // the user already left (a slow Fullscreen API) is undone against this flag.
  let expandedViewWanted = false;
  // Set while the expanded view owns the history entry pushed on entry, so the browser's Back
  // button (or the Android back gesture) leaves the view instead of the page.
  let expandedViewHistoryEntry = false;
  // Set while our own history.back() - consuming that entry on Esc, pinch, or a native exit -
  // is in flight, so the popstate it produces is not mistaken for the user pressing Back.
  let expandedViewHistoryPopPending = false;
  let expandedViewWasActive = false;
  let fullscreenHintTimer = 0;
  // Browsers that expose requestFullscreen() but never settle it would otherwise hold the
  // expanded-view toggle forever; after this long the native attempt is abandoned and the
  // already-visible theater mode simply stays.
  const NATIVE_FULLSCREEN_TIMEOUT_MS = 1500;
  const initialPresentationPreferences = loadPresentationPreferences();
  let presentationOrientation = initialPresentationPreferences.orientation;
  let presentationAlignment = initialPresentationPreferences.alignment;
  let presentationOffset = presentationAlignment === 'custom'
    ? initialPresentationPreferences.offset
    : presentationAlignmentOffset(presentationAlignment);
  let presentationSnapState = presentationSnapStateForOffset(presentationOffset);
  let currentPresentationLayout = null;
  let presentationAlignmentPickerOpen = false;
  let presentationGuideOpen = false;
  let presentationGuideAutoDismissTimer = 0;
  let presentationGuideCountdownTimer = 0;
  let presentationGuideAutoDismissDeadline = 0;
  let presentationGuideTimerGeneration = 0;
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
  let outputAudioWebRtcRecoveryGeneration = 0;
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
  let expandedViewportTarget = null;
  let expandedViewportOrientationAxis = '';
  let developmentViewportMode = '';
  let developmentNarrowDriving = false;
  let developmentNarrowCycleTimer = 0;
  let viewportReportTimer = 0;
  let viewportReportAbortController = null;
  let viewportReportTask = null;
  let viewportReportGeneration = 0;
  let pendingViewportReport = null;
  let pendingViewportReportGeneration = 0;
  let pendingViewportReportTimeoutAttempts = 0;
  let lastViewportReportKey = '';

  function resolveBrowserLanguageCandidates() {
    const candidates = [];
    if (Array.isArray(navigator.languages)) candidates.push(...navigator.languages);
    if (typeof navigator.language === 'string') candidates.push(navigator.language);
    return candidates;
  }

  function supportedLocaleForLanguageTag(tag) {
    const normalized = String(tag || '').trim().toLowerCase().replace(/_/g, '-');
    if (!normalized) return '';
    const [base, ...subtags] = normalized.split('-');
    const language = base === 'in' ? 'id' : base;
    if (language === 'zh' && subtags.some(part => ['tw', 'hk', 'mo', 'hant'].includes(part))) {
      return '';
    }
    return SUPPORTED_LOCALES.includes(language) ? language : '';
  }

  function resolveSystemLocale(candidates = resolveBrowserLanguageCandidates()) {
    for (const candidate of candidates) {
      const locale = supportedLocaleForLanguageTag(candidate);
      if (locale) return locale;
    }
    return 'en';
  }

  function resolvePathLocale(pathname) {
    // Every bundled language has a /xx/ entry page; the first path segment picks it.
    const match = /^\/([a-z]{2})(?:\/|$)/i.exec(String(pathname || ''));
    const locale = match ? match[1].toLowerCase() : '';
    return SUPPORTED_LOCALES.includes(locale) ? locale : '';
  }

  function resolveNoticeLocaleCandidates(
    systemCandidates = resolveBrowserLanguageCandidates(),
    activeLocale = resolveSystemLocale(systemCandidates)
  ) {
    const result = [];
    const add = value => {
      const normalized = String(value || '').trim().replace(/_/g, '-').toLowerCase();
      if (!/^[a-z]{2,8}(?:-[a-z0-9]{1,8})*$/.test(normalized)) return;
      if (!result.includes(normalized)) result.push(normalized);
      const primary = normalized.split('-', 1)[0];
      if (!result.includes(primary)) result.push(primary);
    };
    systemCandidates.forEach(add);
    add(activeLocale);
    add('en');
    add('ko');
    return Object.freeze(result);
  }

  function resolveDateTimeLocale(candidates, activeLocale) {
    const fallback = DATE_TIME_FALLBACK_LOCALES[activeLocale] || 'en-US';
    if (typeof Intl === 'undefined' || typeof Intl.DateTimeFormat !== 'function' ||
        typeof Intl.DateTimeFormat.supportedLocalesOf !== 'function') {
      return fallback;
    }
    for (const candidate of candidates || []) {
      try {
        const supported = Intl.DateTimeFormat.supportedLocalesOf([candidate]);
        if (supported.length > 0) return supported[0];
      } catch (_) {
        // Ignore malformed browser language tags and continue to the safe fallback.
      }
    }
    return fallback;
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

  function localizedKeyForRenderedText(locale, renderedText) {
    const dictionary = I18N[locale] || I18N.en;
    return Object.keys(dictionary).find(key => dictionary[key] === renderedText) || '';
  }

  // The html lang attribute carries the region the bundled dictionary was written for.
  const DOCUMENT_LANGUAGE_TAGS = Object.freeze({ pt: 'pt-BR', zh: 'zh-CN' });

  function documentLanguageTag(locale) {
    return DOCUMENT_LANGUAGE_TAGS[locale] || locale;
  }

  function applyDocumentLocale(previousLocale = '') {
    try {
      document.documentElement.lang = documentLanguageTag(ACTIVE_LOCALE);
      document.querySelectorAll('[data-i18n]').forEach(element => {
        const markupKey = element.getAttribute('data-i18n');
        const activeKey = previousLocale
          ? localizedKeyForRenderedText(previousLocale, element.textContent)
          : markupKey;
        // Runtime status text may intentionally differ from the markup default. Translate it
        // only when it is a known prior-locale string; otherwise preserve the live status.
        if (activeKey) element.textContent = t(activeKey);
      });
      document.querySelectorAll('[data-i18n-aria-label]').forEach(element => {
        element.setAttribute('aria-label', t(element.getAttribute('data-i18n-aria-label')));
      });
      document.querySelectorAll('[data-i18n-alt]').forEach(element => {
        element.setAttribute('alt', t(element.getAttribute('data-i18n-alt')));
      });
      document.querySelectorAll('[data-locale-src-en][data-locale-src-ko]').forEach(element => {
        const localizedSource = element.getAttribute(`data-locale-src-${ACTIVE_LOCALE}`) ||
          element.getAttribute('data-locale-src-en');
        if (localizedSource) element.setAttribute('src', localizedSource);
      });
    } finally {
      document.documentElement.removeAttribute('data-i18n-pending');
    }
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

  function normalizeDebugLocale(value) {
    const requested = String(value || '').trim().toLowerCase();
    if (SUPPORTED_LOCALES.includes(requested)) return requested;
    throw new RangeError(
      'dbg.changeLang(locale) accepts only ' + SUPPORTED_LOCALES.map(code => `"${code}"`).join(', ')
    );
  }

  function changeDebugLanguage(value) {
    const nextLocale = normalizeDebugLocale(value);
    const previousLocale = ACTIVE_LOCALE;
    ACTIVE_LOCALE = nextLocale;
    NOTICE_LOCALE_CANDIDATES = resolveNoticeLocaleCandidates(
      [ACTIVE_LOCALE],
      ACTIVE_LOCALE
    );
    NOTICE_DATE_TIME_LOCALE = resolveDateTimeLocale(
      NOTICE_LOCALE_CANDIDATES,
      ACTIVE_LOCALE
    );
    applyDocumentLocale(previousLocale);
    if (typeof streamStateKey !== 'undefined' && streamStateKey === 'videoWaiting') {
      renderVideoWaitingElapsed();
    }
    cancelNoticeRequestForRetry();
    void ensureNoticesLoaded();
    return ACTIVE_LOCALE;
  }

  Object.defineProperty(window, 'dbg', {
    value: Object.freeze({changeLang: changeDebugLanguage}),
    enumerable: false,
    configurable: false,
    writable: false
  });

  function legacyCredentialStorageKeys() {
    const keys = [];
    for (let index = 0; index < window.localStorage.length; index += 1) {
      const key = window.localStorage.key(index);
      if (key && key !== STORAGE_KEY && key.endsWith(LEGACY_STORAGE_KEY_SUFFIX)) {
        keys.push(key);
      }
    }
    return keys;
  }

  function loadRememberedCredential() {
    try {
      const saved = window.localStorage.getItem(STORAGE_KEY) || '';
      if (CREDENTIAL_PATTERN.test(saved)) return saved;
      for (const legacyKey of legacyCredentialStorageKeys()) {
        const legacyCredential = window.localStorage.getItem(legacyKey) || '';
        if (!CREDENTIAL_PATTERN.test(legacyCredential)) continue;
        window.localStorage.setItem(STORAGE_KEY, legacyCredential);
        window.localStorage.removeItem(legacyKey);
        return legacyCredential;
      }
      return '';
    } catch (_) {
      return '';
    }
  }

  function rememberCredential(value) {
    try {
      window.localStorage.setItem(STORAGE_KEY, value);
      for (const legacyKey of legacyCredentialStorageKeys()) {
        window.localStorage.removeItem(legacyKey);
      }
    } catch (_) {
      // 저장소가 차단된 브라우저는 현재 탭에서만 연결을 유지합니다.
    }
  }

  function forgetCredential() {
    try {
      window.localStorage.removeItem(STORAGE_KEY);
      for (const legacyKey of legacyCredentialStorageKeys()) {
        window.localStorage.removeItem(legacyKey);
      }
    } catch (_) {
      // 저장소 접근 실패와 관계없이 현재 연결은 폐기합니다.
    }
  }

  function boundedPresentationUnit(value) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return 0;
    return Math.max(-1, Math.min(1, numeric));
  }

  function presentationSnapStateForOffset(offset) {
    return Object.freeze({
      x: Math.abs(boundedPresentationUnit(offset && offset.x)) < 0.01,
      y: Math.abs(boundedPresentationUnit(offset && offset.y)) < 0.01
    });
  }

  function normalizePresentationPreferences(value) {
    const orientation = value && PRESENTATION_ORIENTATIONS.includes(value.orientation)
      ? value.orientation
      : 'auto';
    const alignment = value && PRESENTATION_ALIGNMENTS.includes(value.alignment)
      ? value.alignment
      : 'center';
    const rawOffset = value && typeof value.offset === 'object' ? value.offset : null;
    return Object.freeze({
      orientation,
      alignment,
      offset: Object.freeze({
        x: boundedPresentationUnit(rawOffset && rawOffset.x),
        y: boundedPresentationUnit(rawOffset && rawOffset.y)
      })
    });
  }

  function loadPresentationPreferences() {
    try {
      const saved = window.localStorage.getItem(PRESENTATION_PREFERENCES_KEY) || '';
      if (!saved || saved.length > 512) return normalizePresentationPreferences(null);
      return normalizePresentationPreferences(JSON.parse(saved));
    } catch (_) {
      return normalizePresentationPreferences(null);
    }
  }

  function rememberPresentationPreferences() {
    try {
      window.localStorage.setItem(PRESENTATION_PREFERENCES_KEY, JSON.stringify({
        orientation: presentationOrientation,
        alignment: presentationAlignment,
        offset: presentationOffset
      }));
    } catch (_) {
      // Presentation preferences remain valid for the current page when storage is unavailable.
    }
  }

  function presentationGuideWasDismissed() {
    try {
      return window.localStorage.getItem(PRESENTATION_GUIDE_DISMISSED_KEY) === 'true';
    } catch (_) {
      return false;
    }
  }

  function rememberPresentationGuideDismissal() {
    try {
      window.localStorage.setItem(PRESENTATION_GUIDE_DISMISSED_KEY, 'true');
    } catch (_) {
      // A blocked store shows the guide again on a later page load.
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
    if (noticeRequestTask) {
      noticeRequestTask.controller.abort();
      noticeRequestTask = null;
    }
    if (noticeExpiryTimer) window.clearTimeout(noticeExpiryTimer);
    noticeExpiryTimer = 0;
    noticesLoadedCredential = '';
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
      return new Intl.DateTimeFormat(NOTICE_DATE_TIME_LOCALE, {
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

  function diagnosticClockMillis() {
    return typeof performance !== 'undefined' && typeof performance.now === 'function'
      ? performance.now()
      : Date.now();
  }

  function recordConnectionTiming(scope, stage, startedAt, status = '') {
    const safeScope = /^(Pairing|Route|Signal|WebRtc)$/.test(scope) ? scope : 'Pairing';
    const safeStage = String(stage || 'unknown').replace(/[^a-z0-9_-]/gi, '').slice(0, 40) ||
      'unknown';
    const safeStatus = String(status || '').replace(/[^a-z0-9_.-]/gi, '').slice(0, 40);
    const elapsedMillis = Math.max(0, Math.round(diagnosticClockMillis() - startedAt));
    pad.dataset[`navonweb${safeScope}Stage`] = safeStage;
    pad.dataset[`navonweb${safeScope}ElapsedMs`] = String(elapsedMillis);
    if (safeStatus) pad.dataset[`navonweb${safeScope}Status`] = safeStatus;
    else delete pad.dataset[`navonweb${safeScope}Status`];
    console.info(
      `NAVONWEB_TIMING scope=${safeScope.toLowerCase()} stage=${safeStage} ` +
        `elapsedMs=${elapsedMillis}${safeStatus ? ` status=${safeStatus}` : ''}`
    );
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

  function visibleViewportBounds() {
    const visual = window.visualViewport;
    if (visual && Number.isFinite(visual.width) && Number.isFinite(visual.height) &&
        visual.width > 0 && visual.height > 0) {
      const top = Number.isFinite(visual.offsetTop) ? visual.offsetTop : 0;
      return Object.freeze({
        width: visual.width,
        height: visual.height,
        top,
        bottom: top + visual.height,
        source: 'visualViewport'
      });
    }
    const layout = layoutViewportDimensions();
    const top = Number.isFinite(layout.top) ? layout.top : 0;
    const bottom = Number.isFinite(layout.bottom) ? layout.bottom : top + layout.height;
    return Object.freeze({
      width: layout.width,
      height: layout.height,
      top,
      bottom,
      source: 'layoutViewport'
    });
  }

  function clearStandardProjectionBounds() {
    for (const property of ['width', 'height', 'aspect-ratio', 'justify-self']) {
      pad.style.removeProperty(property);
    }
    delete pad.dataset.navonwebStandardBounds;
  }

  function visibleLayoutHeight(element) {
    if (!element || element.hidden) return 0;
    const style = window.getComputedStyle(element);
    if (style.display === 'none') return 0;
    const rect = element.getBoundingClientRect();
    return Number.isFinite(rect.height) ? Math.max(0, rect.height) : 0;
  }

  function collapsedNoticePanelHeight() {
    if (!noticePanel || noticePanel.hidden) return 0;
    const summaryHeight = visibleLayoutHeight(noticeSummary);
    if (summaryHeight <= 0) return 0;
    const style = window.getComputedStyle(noticePanel);
    return summaryHeight +
      finiteCssPixels(style.paddingTop) + finiteCssPixels(style.paddingBottom) +
      finiteCssPixels(style.borderTopWidth) + finiteCssPixels(style.borderBottomWidth);
  }

  function standardProjectionAspectRatio() {
    if (document.body.classList.contains(DYNAMIC_ASPECT_BODY_CLASS) &&
        browserSessionOwnsViewport()) {
      const expanded = expectedExpandedProjectionViewport();
      if (expanded && Number.isFinite(expanded.aspectRatio) && expanded.aspectRatio > 0) {
        return expanded.aspectRatio;
      }
    }
    const sourceAspectRatio = projectionSourceAspectRatio();
    return Number.isFinite(sourceAspectRatio) && sourceAspectRatio > 0 ? sourceAspectRatio : 5 / 3;
  }

  function syncStandardProjectionBounds() {
    if (viewer.hidden || expandedViewActive()) {
      clearStandardProjectionBounds();
      return null;
    }
    const viewport = visibleViewportBounds();
    const padRect = pad.getBoundingClientRect();
    const padViewportTop = Math.max(viewport.top, padRect.top);
    const trailingHeights = [
      localNetworkPanel,
      mediaPermissionPanel,
      repairPairingPanel,
      viewportAuthorityNotice,
      viewerControls
    ].map(visibleLayoutHeight).filter(height => height > 0);
    const viewerStyle = window.getComputedStyle(viewer);
    const viewerGap = finiteCssPixels(viewerStyle.rowGap || viewerStyle.gap);
    const noticeHeight = collapsedNoticePanelHeight();
    const mainStyle = window.getComputedStyle(main);
    const noticeGap = noticeHeight > 0 ? finiteCssPixels(mainStyle.rowGap || mainStyle.gap) : 0;
    const reservedHeight = trailingHeights.reduce((total, height) => total + height, 0) +
      viewerGap * trailingHeights.length + noticeHeight + noticeGap +
      finiteCssPixels(mainStyle.paddingBottom);
    const availableHeight = Math.max(1, viewport.bottom - padViewportTop - reservedHeight);
    const containerWidth = viewer.clientWidth || pad.parentElement.clientWidth || pad.clientWidth;
    const availableWidth = Math.max(1, Math.min(viewport.width, containerWidth));
    const aspectRatio = standardProjectionAspectRatio();
    const height = Math.min(availableHeight, availableWidth / aspectRatio);
    const width = Math.min(availableWidth, height * aspectRatio);
    const roundedWidth = Math.max(1, Math.round(width * 100) / 100);
    const roundedHeight = Math.max(1, Math.round(height * 100) / 100);
    const widthCss = `${roundedWidth}px`;
    const heightCss = `${roundedHeight}px`;
    if (pad.style.getPropertyValue('width') !== widthCss) pad.style.setProperty('width', widthCss);
    if (pad.style.getPropertyValue('height') !== heightCss) pad.style.setProperty('height', heightCss);
    pad.style.setProperty('aspect-ratio', 'auto');
    pad.style.setProperty('justify-self', 'center');
    pad.dataset.navonwebStandardBounds = `${roundedWidth}x${roundedHeight}`;
    return Object.freeze({width: roundedWidth, height: roundedHeight, availableHeight});
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
      const top = Number.isFinite(visual.offsetTop) ? visual.offsetTop : 0;
      return {
        width: visual.width,
        height: visual.height,
        top,
        bottom: top + visual.height,
        scale: visualScale,
        source: 'visualViewport'
      };
    }
    const width = Math.max(document.documentElement.clientWidth, window.innerWidth || 0);
    const height = Math.max(document.documentElement.clientHeight, window.innerHeight || 0);
    return {
      width,
      height,
      top: 0,
      bottom: height,
      scale: visualScale,
      source: 'layoutViewport'
    };
  }

  function expandedViewportInsets() {
    if (!expandedViewportProbe) {
      return Object.freeze({left: 0, right: 0, top: 0, bottom: 0});
    }
    const style = window.getComputedStyle(expandedViewportProbe);
    return Object.freeze({
      left: finiteCssPixels(style.paddingLeft),
      right: finiteCssPixels(style.paddingRight),
      top: finiteCssPixels(style.paddingTop),
      bottom: finiteCssPixels(style.paddingBottom)
    });
  }

  function orientPresentationViewport(width, height, orientation) {
    const axis = projectionOrientationAxis(width, height);
    const requested = PRESENTATION_ORIENTATIONS.includes(orientation) ? orientation : 'auto';
    if (requested === 'auto' || !axis || axis === requested) {
      return Object.freeze({width, height, orientation: requested});
    }
    return Object.freeze({width: height, height: width, orientation: requested});
  }

  function effectiveProjectionOrientation() {
    return browserSessionOwnsViewport() ? presentationOrientation : 'auto';
  }

  function calculateExpandedProjectionViewport() {
    if (viewer.hidden) return null;
    const viewport = layoutViewportDimensions();
    let width = viewport.width;
    let height = viewport.height;
    let source = 'theaterPreview';
    const nativeFullscreenAvailable = !theaterMode &&
      typeof viewer.requestFullscreen === 'function' && document.fullscreenEnabled !== false;
    const screenWidth = Number(window.screen && window.screen.width);
    const screenHeight = Number(window.screen && window.screen.height);
    if (nativeFullscreenAvailable && Number.isFinite(screenWidth) && screenWidth > 0 &&
        Number.isFinite(screenHeight) && screenHeight > 0) {
      const orientationType = String(
        window.screen && window.screen.orientation && window.screen.orientation.type || ''
      );
      const legacyOrientation = Number(window.orientation);
      const orientationLandscape = orientationType.startsWith('landscape') ? true :
        orientationType.startsWith('portrait') ? false :
          Number.isFinite(legacyOrientation) ? Math.abs(legacyOrientation) % 180 === 90 : null;
      const dimensionsLandscape = screenWidth >= screenHeight;
      const swapDimensions = orientationLandscape !== null &&
        orientationLandscape !== dimensionsLandscape;
      width = swapDimensions ? screenHeight : screenWidth;
      height = swapDimensions ? screenWidth : screenHeight;
      source = 'fullscreenPreview';
    }
    if (developmentNarrowDriving) {
      width = Math.min(width, width * DEVELOPMENT_NARROW_WIDTH_SCALE);
    }
    const insets = expandedViewportInsets();
    width -= insets.left + insets.right;
    height -= insets.top + insets.bottom;
    if (width <= 0 || height <= 0) return null;
    const oriented = orientPresentationViewport(width, height, effectiveProjectionOrientation());
    const roundedWidth = Math.round(oriented.width * 100) / 100;
    const roundedHeight = Math.round(oriented.height * 100) / 100;
    return Object.freeze({
      width: roundedWidth,
      height: roundedHeight,
      aspectRatio: roundedWidth / roundedHeight,
      devicePixelRatio: browserDevicePixelRatio(),
      viewportScale: viewport.scale,
      source,
      presentationOrientation: oriented.orientation
    });
  }

  function expectedExpandedProjectionViewport() {
    return expandedViewportTarget || calculateExpandedProjectionViewport();
  }

  function projectionOrientationAxis(width, height) {
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0 ||
        Math.abs(width - height) <= 0.5) return '';
    return width > height ? 'landscape' : 'portrait';
  }

  function physicalViewportOrientationAxis() {
    const orientationType = String(
      window.screen && window.screen.orientation && window.screen.orientation.type || ''
    );
    if (orientationType.startsWith('landscape')) return 'landscape';
    if (orientationType.startsWith('portrait')) return 'portrait';
    const legacyOrientation = Number(window.orientation);
    if (Number.isFinite(legacyOrientation)) {
      return Math.abs(legacyOrientation) % 180 === 90 ? 'landscape' : 'portrait';
    }
    const screenWidth = Number(window.screen && window.screen.width);
    const screenHeight = Number(window.screen && window.screen.height);
    const screenAxis = projectionOrientationAxis(screenWidth, screenHeight);
    if (screenAxis) return screenAxis;
    const viewport = layoutViewportDimensions();
    return projectionOrientationAxis(viewport.width, viewport.height);
  }

  function lockExpandedProjectionViewport() {
    if (!expandedViewportTarget) {
      expandedViewportTarget = calculateExpandedProjectionViewport();
      expandedViewportOrientationAxis = physicalViewportOrientationAxis();
    }
    return expandedViewportTarget;
  }

  function releaseExpandedProjectionViewport() {
    expandedViewportTarget = null;
    expandedViewportOrientationAxis = '';
  }

  function refreshExpandedProjectionViewport() {
    if (!expandedViewportTarget) return false;
    const nextAxis = physicalViewportOrientationAxis();
    const physicalRotation = Boolean(
      expandedViewportOrientationAxis && nextAxis &&
      expandedViewportOrientationAxis !== nextAxis
    );
    if (!physicalRotation) return false;
    const next = calculateExpandedProjectionViewport();
    const effectiveOrientation = effectiveProjectionOrientation();
    const requestedAxis = effectiveOrientation === 'auto' ? nextAxis : effectiveOrientation;
    if (!next || projectionOrientationAxis(next.width, next.height) !== requestedAxis) return false;
    // orientationchange may precede the visual/layout viewport resize (notably in Safari).
    // Consume the physical-axis transition only after the corresponding geometry is observable.
    expandedViewportOrientationAxis = nextAxis;
    if (!viewportValueChanged(expandedViewportTarget, next)) return false;
    expandedViewportTarget = next;
    return true;
  }

  function measureProjectionViewport() {
    if (viewer.hidden) return null;
    // Negotiate the display-shaped content viewport while the standard view is still visible.
    // Fullscreen uses screen CSS pixels (address-bar independent); theater mode uses the current
    // viewport. Reusing this exact calculation after entry keeps the report key and quantized AA
    // layout stable across both CSS transitions.
    return expectedExpandedProjectionViewport();
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

  function clearViewportReportTimer() {
    if (viewportReportTimer) window.clearTimeout(viewportReportTimer);
    viewportReportTimer = 0;
  }

  function abortViewportReportTask(reason) {
    const task = viewportReportTask;
    if (!task) return;
    task.abortReason = reason;
    task.controller.abort();
  }

  function armPendingViewportReport(delayMillis) {
    clearViewportReportTimer();
    const generation = pendingViewportReportGeneration;
    viewportReportTimer = window.setTimeout(() => {
      viewportReportTimer = 0;
      if (!pendingViewportReport || generation !== pendingViewportReportGeneration ||
          generation !== viewportReportGeneration) return;
      reportProjectionViewport();
    }, delayMillis);
  }

  function stageLatestViewportReport(value, delayMillis = VIEWPORT_REPORT_SETTLE_MILLIS) {
    viewportReportGeneration += 1;
    pendingViewportReport = value;
    pendingViewportReportGeneration = viewportReportGeneration;
    pendingViewportReportTimeoutAttempts = 0;
    abortViewportReportTask('superseded');
    if (delayMillis === null) clearViewportReportTimer();
    else armPendingViewportReport(delayMillis);
  }

  function retryLatestViewportReport(task, delayMillis, timedOut = false) {
    if (viewportReportTask !== task || task.abortReason === 'superseded' ||
        task.generation !== viewportReportGeneration ||
        (pendingViewportReport && pendingViewportReportGeneration !== task.generation)) return false;
    const timeoutAttempts = task.timeoutAttempts + (timedOut ? 1 : 0);
    if (timedOut && timeoutAttempts > VIEWPORT_REPORT_MAX_TIMEOUT_RETRIES) return false;
    pendingViewportReport = task.value;
    pendingViewportReportGeneration = task.generation;
    pendingViewportReportTimeoutAttempts = timeoutAttempts;
    armPendingViewportReport(delayMillis);
    return true;
  }

  function scheduleViewportReport(value) {
    const blockedReason = !value ? 'no-viewport' :
      !browserCredential ? 'no-browser-credential' :
      !browserSessionOwnsViewport() ? 'not-main-session' :
      !pageActive ? 'page-inactive' :
      document.hidden ? 'document-hidden' :
      !document.body.classList.contains(DYNAMIC_ASPECT_BODY_CLASS) ? 'not-entitled' :
      Math.abs(value.viewportScale - 1) > 0.01 ? 'viewport-scaled' : '';
    if (blockedReason) {
      viewportReportGeneration += 1;
      clearViewportReportTimer();
      pendingViewportReport = null;
      pendingViewportReportGeneration = 0;
      pendingViewportReportTimeoutAttempts = 0;
      abortViewportReportTask('blocked');
      setDevelopmentViewportDiagnostic('viewport-report', `blocked:${blockedReason}`);
      return;
    }
    // This request is same-origin to the phone-hosted server. navigator.onLine can be false in
    // vehicle browsers without public Internet even though the local NavOnWeb server is healthy.
    const key = viewportReportKey(value);
    if (viewportReportTimer && pendingViewportReport &&
        pendingViewportReportGeneration === viewportReportGeneration &&
        viewportReportKey(pendingViewportReport) === key) {
      setDevelopmentViewportDiagnostic('viewport-report', `scheduled:${key}`);
      return;
    }
    if (viewportReportTask && viewportReportTask.generation === viewportReportGeneration &&
        viewportReportTask.key === key && !viewportReportTask.timedOut) {
      setDevelopmentViewportDiagnostic('viewport-report', `sending:${key}`);
      return;
    }
    setDevelopmentViewportDiagnostic('viewport-report', `scheduled:${key}`);
    stageLatestViewportReport(value);
  }

  function viewportReportKey(value) {
    return `${Math.max(1, Math.round(value.width))}x${Math.max(1, Math.round(value.height))}`;
  }

  function requestViewportControlReclaim() {
    if (!browserSessionOwnsViewport() ||
        !document.body.classList.contains(DYNAMIC_ASPECT_BODY_CLASS)) return;
    // A role transition can race a queued layout frame after an offline viewer changed its
    // orientation preference. Measure synchronously so becoming main never posts the stale
    // target once before the coalesced preference target.
    const latest = measureProjectionViewport();
    if (!latest) return;
    activeViewportValue = latest;
    lastViewportReportKey = '';
    scheduleViewportReport(latest);
  }

  async function reportProjectionViewport() {
    const value = pendingViewportReport;
    const generation = pendingViewportReportGeneration;
    const timeoutAttempts = pendingViewportReportTimeoutAttempts;
    pendingViewportReport = null;
    pendingViewportReportGeneration = 0;
    pendingViewportReportTimeoutAttempts = 0;
    if (!value || generation !== viewportReportGeneration || !browserCredential ||
        !browserSessionOwnsViewport() || !pageActive || document.hidden ||
        !document.body.classList.contains(DYNAMIC_ASPECT_BODY_CLASS) ||
        Math.abs(value.viewportScale - 1) > 0.01) {
      setDevelopmentViewportDiagnostic('viewport-report', 'blocked:stale');
      return;
    }
    const key = viewportReportKey(value);
    if (key === lastViewportReportKey) return;
    abortViewportReportTask('superseded');
    const controller = new AbortController();
    const task = {
      generation,
      key,
      value,
      timeoutAttempts,
      controller,
      timedOut: false,
      abortReason: ''
    };
    viewportReportTask = task;
    viewportReportAbortController = controller;
    const timeout = window.setTimeout(() => {
      task.timedOut = true;
      task.abortReason = 'timeout';
      controller.abort();
    }, VIEWPORT_REPORT_TIMEOUT_MILLIS);
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
      if (viewportReportTask !== task || generation !== viewportReportGeneration ||
          task.abortReason === 'superseded') return false;
      if (response.status === 401) {
        invalidateCredential(t('connectionExpiredPhone'));
        return false;
      }
      if (response.status === 200 || response.status === 202) {
        lastViewportReportKey = key;
        setDevelopmentViewportDiagnostic('viewport-report', `accepted:${response.status}:${key}`);
        return true;
      }
      if (response.status === 409 || response.status >= 500) {
        const conflict = response.status === 409 ? await response.json().catch(() => ({})) : {};
        if (viewportReportTask !== task || generation !== viewportReportGeneration ||
            task.abortReason === 'superseded') return false;
        if (conflict.error === 'main_session_required') {
          viewportAuthorityRejected = true;
          cancelViewportAuthority();
          syncViewportAuthorityNotice();
          void pollStatus({automatic: true});
          setDevelopmentViewportDiagnostic('viewport-report', `blocked:not-main-session:${key}`);
          return false;
        }
        const controllerBusy = conflict.error === 'viewport_controller_busy';
        const retryDelay = controllerBusy ?
          VIEWPORT_CONTROLLER_BUSY_RETRY_MILLIS : VIEWPORT_REPORT_RETRY_MILLIS;
        setDevelopmentViewportDiagnostic(
          'viewport-report',
          `${controllerBusy ? 'busy' : 'retry'}:${response.status}:${key}`
        );
        retryLatestViewportReport(task, retryDelay);
      }
      if (response.status !== 409 && response.status < 500) {
        setDevelopmentViewportDiagnostic('viewport-report', `rejected:${response.status}:${key}`);
      }
      return false;
    } catch (error) {
      const latest = viewportReportTask === task && generation === viewportReportGeneration &&
        task.abortReason !== 'superseded';
      if (error.name === 'AbortError' && task.timedOut && latest) {
        const retrying = retryLatestViewportReport(task, VIEWPORT_REPORT_RETRY_MILLIS, true);
        setDevelopmentViewportDiagnostic(
          'viewport-report',
          `${retrying ? 'retry:timeout' : 'failed:timeout'}:${key}`
        );
      } else if (error.name !== 'AbortError' && latest) {
        setDevelopmentViewportDiagnostic('viewport-report', `retry:network:${key}`);
        retryLatestViewportReport(task, VIEWPORT_REPORT_RETRY_MILLIS);
      } else {
        setDevelopmentViewportDiagnostic('viewport-report', `aborted:${key}`);
      }
      return false;
    } finally {
      window.clearTimeout(timeout);
      if (viewportReportTask === task) viewportReportTask = null;
      if (viewportReportAbortController === controller) viewportReportAbortController = null;
    }
  }

  function preapplyExpandedProjectionViewport() {
    const value = expectedExpandedProjectionViewport();
    if (!value || !browserCredential || !browserSessionOwnsViewport() ||
        !pageActive || document.hidden ||
        !document.body.classList.contains(DYNAMIC_ASPECT_BODY_CLASS) ||
        Math.abs(value.viewportScale - 1) > 0.01) return null;
    const key = viewportReportKey(value);
    if (key === lastViewportReportKey) return null;
    if (viewportReportTask && viewportReportTask.generation === viewportReportGeneration &&
        viewportReportTask.key === key && !viewportReportTask.timedOut) return null;
    if (!pendingViewportReport || pendingViewportReportGeneration !== viewportReportGeneration ||
        viewportReportKey(pendingViewportReport) !== key) {
      stageLatestViewportReport(value, null);
    } else {
      clearViewportReportTimer();
    }
    // Start the same-origin report before requestFullscreen/theater mode. The standard view has
    // normally completed this report already; this last flush remains fire-and-forget so the
    // Fullscreen API is still called inside the browser's transient user-activation window.
    return reportProjectionViewport();
  }

  function stopViewportReporting() {
    viewportReportGeneration += 1;
    clearViewportReportTimer();
    pendingViewportReport = null;
    pendingViewportReportGeneration = 0;
    pendingViewportReportTimeoutAttempts = 0;
    lastViewportReportKey = '';
    abortViewportReportTask('stopped');
  }

  function refreshDevelopmentViewportWidth() {
    if (!developmentNarrowDriving) {
      document.documentElement.style.removeProperty('--navonweb-development-viewport-width');
      return;
    }
    const viewport = layoutViewportDimensions();
    const fullContentWidth = viewport.width;
    const simulatedWidth = Math.max(320, Math.round(fullContentWidth * DEVELOPMENT_NARROW_WIDTH_SCALE));
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
      syncStandardProjectionBounds();
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

  function handleViewportGeometryChange() {
    cancelPointerInteraction();
    // The entry target prevents browser fullscreen CSS transitions from causing a second AA
    // reconnect. Once the physical viewport rotates, however, that target must follow the new
    // display geometry so the POST can replace the encoded orientation and touch viewport.
    refreshExpandedProjectionViewport();
    scheduleViewportLayoutSync();
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

  function applyDevelopmentNarrowDriving(enabled) {
    developmentNarrowDriving = Boolean(enabled);
    document.body.classList.toggle(DEVELOPMENT_NARROW_BODY_CLASS, developmentNarrowDriving);
    refreshDevelopmentViewportWidth();
    scheduleViewportLayoutSync();
    return developmentViewportSnapshot();
  }

  function stopDevelopmentNarrowCycle() {
    if (developmentNarrowCycleTimer) window.clearInterval(developmentNarrowCycleTimer);
    developmentNarrowCycleTimer = 0;
  }

  function startDevelopmentNarrowCycle() {
    if (developmentViewportMode !== DEVELOPMENT_NARROW_CYCLE_MODE || developmentNarrowCycleTimer) return;
    developmentNarrowCycleTimer = window.setInterval(() => {
      applyDevelopmentNarrowDriving(!developmentNarrowDriving);
    }, DEVELOPMENT_NARROW_CYCLE_INTERVAL_MILLIS);
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
      narrowDriving: developmentNarrowDriving,
      widthScale: DEVELOPMENT_NARROW_WIDTH_SCALE,
      simulatedWidthCssPixels: developmentNarrowDriving && simulatedWidth > 0 ? simulatedWidth : null,
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

  function setDevelopmentNarrowDriving(enabled) {
    stopDevelopmentNarrowCycle();
    developmentViewportMode = 'manual';
    return applyDevelopmentNarrowDriving(enabled);
  }

  function installDevelopmentViewport() {
    if (!NAVONWEB_DEVELOPMENT_VIEWPORT_ENABLED) return;
    let requestedMode = '';
    try {
      requestedMode = new URLSearchParams(window.location.search).get(DEVELOPMENT_VIEWPORT_QUERY) || '';
    } catch (_) {
      return;
    }
    if (requestedMode !== DEVELOPMENT_NARROW_DRIVING_MODE &&
        requestedMode !== DEVELOPMENT_NARROW_CYCLE_MODE) return;
    developmentViewportMode = requestedMode;
    applyDevelopmentNarrowDriving(true);
    if (requestedMode === DEVELOPMENT_NARROW_CYCLE_MODE) startDevelopmentNarrowCycle();
    window.__navOnWebDevelopmentViewport = Object.freeze({
      snapshot: developmentViewportSnapshot,
      setNarrowDriving: setDevelopmentNarrowDriving
    });
  }

  function installViewportObservers() {
    if (typeof ResizeObserver === 'function') {
      viewportResizeObserver = new ResizeObserver(() => scheduleViewportLayoutSync());
      viewportResizeObserver.observe(main);
      viewportResizeObserver.observe(localNetworkPanel);
      viewportResizeObserver.observe(mediaPermissionPanel);
      viewportResizeObserver.observe(repairPairingPanel);
      viewportResizeObserver.observe(viewerControls);
      viewportResizeObserver.observe(viewportAuthorityNotice);
      viewportResizeObserver.observe(noticePanel);
      viewportResizeObserver.observe(noticeSummary);
    }
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', handleViewportGeometryChange);
      window.visualViewport.addEventListener('scroll', scheduleViewportLayoutSync);
    }
    window.addEventListener('orientationchange', handleViewportGeometryChange);
    if (window.screen && window.screen.orientation &&
        typeof window.screen.orientation.addEventListener === 'function') {
      window.screen.orientation.addEventListener('change', handleViewportGeometryChange);
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
    // Permissions API results are advisory. Some Chromium embedders can report
    // "denied" even when the visible site setting is allowed, so only the ICE
    // connection result may stop or replace an established media session.
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
    syncViewportAuthorityNotice();
    syncLocalNetworkPermissionPanel();
    if (authenticated) syncScreenWakeLock();
    else releaseScreenWakeLock();
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
        value.densityDpi < 72 || value.densityDpi > 320 ||
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

  function sameProjectionMediaIdentity(left, right) {
    return left.id === right.id && left.width === right.width && left.height === right.height &&
      left.androidAutoFramesPerSecond === right.androidAutoFramesPerSecond &&
      left.webRtcFramesPerSecond === right.webRtcFramesPerSecond &&
      left.sourceAspectWidth === right.sourceAspectWidth &&
      left.sourceAspectHeight === right.sourceAspectHeight;
  }

  function sameProjectionProfile(left, right) {
    return sameProjectionMediaIdentity(left, right) && left.densityDpi === right.densityDpi;
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
        value.densityDpi < 72 || value.densityDpi > 320) return null;
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

  function projectionReadyForWebRtcPreflight(projection) {
    if (!projection || String(projection.activationState || '').toLowerCase() !== 'active') {
      return false;
    }
    const viewport = projection.viewport;
    if (!viewport || String(viewport.activationState || '').toLowerCase() !== 'active') {
      return false;
    }
    const active = normalizeProjectionViewport(viewport.activeLayout);
    const requested = normalizeProjectionViewport(viewport.requestedLayout);
    return Boolean(active && requested && sameProjectionViewport(active, requested));
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

  function presentationAlignmentOffset(alignment) {
    const horizontal = alignment.endsWith('left') || alignment === 'left' ? -1 :
      alignment.endsWith('right') || alignment === 'right' ? 1 : 0;
    const vertical = alignment.startsWith('top') || alignment === 'top' ? -1 :
      alignment.startsWith('bottom') || alignment === 'bottom' ? 1 : 0;
    return Object.freeze({x: horizontal, y: vertical});
  }

  function calculatePresentationLayout(containerWidth, containerHeight, aspectRatio, offset) {
    if (![containerWidth, containerHeight, aspectRatio].every(Number.isFinite) ||
        containerWidth <= 0 || containerHeight <= 0 || aspectRatio <= 0) return null;
    let width = containerWidth;
    let height = width / aspectRatio;
    if (height > containerHeight) {
      height = containerHeight;
      width = height * aspectRatio;
    }
    const marginX = Math.max(0, (containerWidth - width) / 2);
    const marginY = Math.max(0, (containerHeight - height) / 2);
    const normalizedOffset = Object.freeze({
      x: marginX > 0 ? boundedPresentationUnit(offset && offset.x) : 0,
      y: marginY > 0 ? boundedPresentationUnit(offset && offset.y) : 0
    });
    const offsetX = normalizedOffset.x * marginX;
    const offsetY = normalizedOffset.y * marginY;
    return Object.freeze({
      width,
      height,
      marginX,
      marginY,
      left: marginX + offsetX,
      top: marginY + offsetY,
      offsetX,
      offsetY,
      normalizedOffset
    });
  }

  function snapPresentationAxis(value, maximum, wasSnapped) {
    const boundedMaximum = Number.isFinite(maximum) ? Math.max(0, maximum) : 0;
    const bounded = Math.max(-boundedMaximum, Math.min(boundedMaximum, Number(value) || 0));
    const threshold = wasSnapped
      ? PRESENTATION_SNAP_EXIT_CSS_PIXELS
      : PRESENTATION_SNAP_ENTER_CSS_PIXELS;
    if (Math.abs(bounded) <= Math.min(boundedMaximum, threshold)) {
      return Object.freeze({value: 0, snapped: true});
    }
    return Object.freeze({value: bounded, snapped: false});
  }

  function dragPresentationOffset(layout, initialOffset, deltaX, deltaY, snapState) {
    if (!layout) return null;
    const initialX = boundedPresentationUnit(initialOffset && initialOffset.x) * layout.marginX;
    const initialY = boundedPresentationUnit(initialOffset && initialOffset.y) * layout.marginY;
    const horizontal = snapPresentationAxis(
      initialX + (Number.isFinite(deltaX) ? deltaX : 0),
      layout.marginX,
      Boolean(snapState && snapState.x)
    );
    const vertical = snapPresentationAxis(
      initialY + (Number.isFinite(deltaY) ? deltaY : 0),
      layout.marginY,
      Boolean(snapState && snapState.y)
    );
    return Object.freeze({
      offset: Object.freeze({
        x: layout.marginX > 0 ? horizontal.value / layout.marginX : 0,
        y: layout.marginY > 0 ? vertical.value / layout.marginY : 0
      }),
      snap: Object.freeze({x: horizontal.snapped, y: vertical.snapped})
    });
  }

  function presentationAlignmentI18nKey(value) {
    switch (value) {
      case 'top': return 'presentationAlignTop';
      case 'bottom': return 'presentationAlignBottom';
      case 'left': return 'presentationAlignLeft';
      case 'right': return 'presentationAlignRight';
      case 'top-left': return 'presentationAlignTopLeft';
      case 'top-right': return 'presentationAlignTopRight';
      case 'bottom-left': return 'presentationAlignBottomLeft';
      case 'bottom-right': return 'presentationAlignBottomRight';
      case 'custom': return 'presentationAlignCustom';
      default: return 'presentationAlignCenter';
    }
  }

  function setPresentationAlignmentPickerOpen(open, restoreTriggerFocus = false) {
    const allowed = presentationOrientation !== 'auto' && !expandedViewActive() &&
      !presentationAlignmentLabel.hidden;
    const nextOpen = Boolean(open && allowed);
    const wasOpen = presentationAlignmentPickerOpen;
    presentationAlignmentPickerOpen = nextOpen;
    presentationAlignmentTrigger.setAttribute('aria-expanded', String(nextOpen));
    presentationAlignmentPicker.hidden = !nextOpen;
    if (nextOpen && !wasOpen) {
      window.requestAnimationFrame(() => {
        if (!presentationAlignmentPickerOpen || presentationAlignmentPicker.hidden) return;
        const focusTarget = presentationAlignmentInputs.find(input => input.checked) ||
          presentationAlignmentInputs.find(input => input.value === 'center') ||
          presentationAlignmentInputs[0];
        if (!focusTarget) return;
        try { focusTarget.focus({preventScroll: true}); } catch (_) { focusTarget.focus(); }
      });
    } else if (!nextOpen && wasOpen && restoreTriggerFocus &&
        !presentationAlignmentLabel.hidden) {
      try { presentationAlignmentTrigger.focus({preventScroll: true}); } catch (_) {
        presentationAlignmentTrigger.focus();
      }
    }
    return nextOpen !== wasOpen;
  }

  function syncPresentationControls() {
    for (const input of presentationOrientationInputs) {
      input.checked = input.value === presentationOrientation;
    }
    presentationAlignmentLabel.hidden = presentationOrientation === 'auto';
    if (presentationOrientation === 'auto' || expandedViewActive()) {
      setPresentationAlignmentPickerOpen(false, false);
    }
    for (const input of presentationAlignmentInputs) {
      input.checked = input.value === presentationAlignment;
    }
    presentationAlignmentSelect.value = presentationAlignment;
    presentationAlignmentTrigger.dataset.presentationAlignment = presentationAlignment;
    presentationAlignmentTrigger.setAttribute(
      'aria-label',
      t('presentationAlignmentTrigger').replace(
        '{alignment}',
        t(presentationAlignmentI18nKey(presentationAlignment))
      )
    );
    pad.dataset.navonwebPresentationOrientation = presentationOrientation;
    pad.dataset.navonwebPresentationAlignment = presentationAlignment;
  }

  function syncProjectionContentLayout() {
    const containerWidth = pad.clientWidth;
    const containerHeight = pad.clientHeight;
    if (containerWidth <= 0 || containerHeight <= 0) return null;
    const alignmentOffset = presentationAlignment === 'custom'
      ? presentationOffset
      : presentationAlignmentOffset(presentationAlignment);
    const layout = calculatePresentationLayout(
      containerWidth,
      containerHeight,
      projectionSourceAspectRatio(),
      alignmentOffset
    );
    if (!layout) return null;
    currentPresentationLayout = layout;
    projectionContent.style.width = `${layout.width}px`;
    projectionContent.style.height = `${layout.height}px`;
    projectionContent.style.left = `${layout.left}px`;
    projectionContent.style.top = `${layout.top}px`;
    pad.dataset.navonwebPresentationSnapX = String(Math.abs(layout.offsetX) < 0.01);
    pad.dataset.navonwebPresentationSnapY = String(Math.abs(layout.offsetY) < 0.01);
    return layout;
  }

  function refreshPresentationViewportPreference() {
    if (expandedViewportTarget) {
      expandedViewportTarget = calculateExpandedProjectionViewport();
      expandedViewportOrientationAxis = physicalViewportOrientationAxis();
    }
    scheduleViewportLayoutSync();
  }

  function setPresentationOrientation(value) {
    if (!PRESENTATION_ORIENTATIONS.includes(value) || value === presentationOrientation) return false;
    cancelPointerInteraction();
    presentationOrientation = value;
    rememberPresentationPreferences();
    syncPresentationControls();
    refreshPresentationViewportPreference();
    return true;
  }

  function setPresentationAlignment(value) {
    if (!PRESENTATION_ALIGNMENTS.includes(value) || value === presentationAlignment) return false;
    cancelPointerInteraction();
    presentationAlignment = value;
    if (value !== 'custom') presentationOffset = presentationAlignmentOffset(value);
    presentationSnapState = presentationSnapStateForOffset(presentationOffset);
    rememberPresentationPreferences();
    syncPresentationControls();
    syncProjectionContentLayout();
    return true;
  }

  function resetPresentationPosition() {
    presentationAlignment = 'center';
    presentationOffset = Object.freeze({x: 0, y: 0});
    presentationSnapState = Object.freeze({x: true, y: true});
    rememberPresentationPreferences();
    syncPresentationControls();
    syncProjectionContentLayout();
  }

  function applyProjectionGeometry(value) {
    const next = normalizeProjectionProfile(value) || LEGACY_PROJECTION_PROFILE;
    if (sameProjectionProfile(next, activeProjectionProfile)) return false;
    const mediaIdentityChanged = !sameProjectionMediaIdentity(next, activeProjectionProfile);
    activeProjectionProfile = next;
    if (!mediaIdentityChanged) {
      // DPI is Android Auto UI metadata, not WebRTC media identity. Preserve the established
      // peer, frame and live portrait crop while AA performs its metadata-only reconnect.
      activeProjectionViewport = Object.freeze({
        ...activeProjectionViewport,
        densityDpi: next.densityDpi
      });
      setDevelopmentViewportDiagnostic(
        'viewport-active',
        `${activeProjectionViewport.contentWidth}x${activeProjectionViewport.contentHeight}` +
          `+${activeProjectionViewport.contentLeft}+${activeProjectionViewport.contentTop}` +
          `@${next.densityDpi}dpi`
      );
      scheduleViewportLayoutSync();
      return true;
    }
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

  function formatVideoWaitingDuration(elapsedSeconds) {
    const totalSeconds = Number.isFinite(elapsedSeconds)
      ? Math.max(0, Math.floor(elapsedSeconds))
      : 0;
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    const paddedSeconds = String(seconds).padStart(2, '0');
    return hours > 0
      ? `${hours}:${String(minutes).padStart(2, '0')}:${paddedSeconds}`
      : `${minutes}:${paddedSeconds}`;
  }

  function videoWaitingClockNow() {
    return typeof performance !== 'undefined' && typeof performance.now === 'function'
      ? performance.now()
      : Date.now();
  }

  function renderVideoWaitingElapsed(now = videoWaitingClockNow()) {
    if (videoWaitingStartedAt === null) return;
    const elapsedSeconds = Math.max(0, Math.floor((now - videoWaitingStartedAt) / 1000));
    const duration = formatVideoWaitingDuration(elapsedSeconds);
    streamWaitTime.textContent = t('videoWaitingElapsed').replace('{time}', duration);
    streamWaitTime.setAttribute('datetime', `PT${elapsedSeconds}S`);
  }

  function stopVideoWaitingTimer() {
    if (videoWaitingTimer) window.clearInterval(videoWaitingTimer);
    videoWaitingTimer = 0;
    videoWaitingStartedAt = null;
    streamState.classList.remove('video-waiting');
    streamWaitTime.hidden = true;
    streamWaitTime.textContent = '';
    streamWaitTime.removeAttribute('datetime');
  }

  function startVideoWaitingTimer() {
    streamState.classList.add('video-waiting');
    streamWaitTime.hidden = false;
    if (videoWaitingStartedAt === null) videoWaitingStartedAt = videoWaitingClockNow();
    renderVideoWaitingElapsed();
    if (!videoWaitingTimer) {
      videoWaitingTimer = window.setInterval(renderVideoWaitingElapsed, 1000);
    }
  }

  function setStreamState(key) {
    const normalizedKey = typeof key === 'string' ? key : '';
    streamStateKey = normalizedKey;
    if (normalizedKey) {
      streamStateMessage.textContent = t(normalizedKey);
      streamStateMessage.setAttribute('data-i18n', normalizedKey);
    } else {
      streamStateMessage.textContent = '';
      streamStateMessage.removeAttribute('data-i18n');
    }
    if (normalizedKey === 'videoWaiting') startVideoWaitingTimer();
    else stopVideoWaitingTimer();
  }

  function clearFrame() {
    if (frameObjectUrl) URL.revokeObjectURL(frameObjectUrl);
    frameObjectUrl = '';
    frameVersion = 0;
    frame.removeAttribute('src');
    pad.classList.remove('frame-ready');
    if (!pad.classList.contains('webrtc-ready')) setStreamState('androidAutoWaiting');
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

  function resumeFramePollingAfterLifecyclePause() {
    // Background timer/fetch suspension is browser-owned. Keep the same reader and credential;
    // when the page becomes visible, only nudge a reader whose timer was frozen or discarded.
    if (CLOUD_RELAY_MODE || !browserCredential || document.hidden ||
        pad.classList.contains('webrtc-ready')) return;
    if (!framePolling) {
      startFramePolling();
      return;
    }
    if (frameAbortController) return;
    if (frameTimer) clearTimeout(frameTimer);
    frameTimer = 0;
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
          setStreamState('');
        }
      };
      frame.src = nextObjectUrl;
      if (previousObjectUrl) URL.revokeObjectURL(previousObjectUrl);
    } catch (error) {
      if (error.name !== 'AbortError' && browserCredential === requestedCredential) {
        if (!pad.classList.contains('frame-ready')) setStreamState('videoWaiting');
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

  function screenWakeLockEligible() {
    return Boolean(browserCredential) && pageActive && !document.hidden && expandedViewActive();
  }

  function safelyReleaseScreenWakeLock(lock) {
    if (!lock || lock.released || typeof lock.release !== 'function') return;
    try {
      Promise.resolve(lock.release()).catch(() => null);
    } catch (_) {
      // Wake Lock is optional and must never interrupt projection controls.
    }
  }

  function releaseScreenWakeLock() {
    if (!screenWakeLock && !screenWakeLockRequest) return;
    screenWakeLockGeneration += 1;
    screenWakeLockRequest = null;
    const lock = screenWakeLock;
    screenWakeLock = null;
    safelyReleaseScreenWakeLock(lock);
  }

  function requestScreenWakeLock() {
    if (!screenWakeLockEligible() || screenWakeLock || screenWakeLockRequest) return;
    if (!navigator.wakeLock || typeof navigator.wakeLock.request !== 'function') return;
    const generation = ++screenWakeLockGeneration;
    let request;
    try {
      request = navigator.wakeLock.request('screen');
    } catch (_) {
      return;
    }
    screenWakeLockRequest = request;
    Promise.resolve(request).then(lock => {
      if (screenWakeLockRequest === request) screenWakeLockRequest = null;
      if (generation !== screenWakeLockGeneration || !screenWakeLockEligible()) {
        safelyReleaseScreenWakeLock(lock);
        return;
      }
      screenWakeLock = lock;
      if (lock && typeof lock.addEventListener === 'function') {
        lock.addEventListener('release', () => {
          if (screenWakeLock === lock) screenWakeLock = null;
        }, {once: true});
      }
    }).catch(() => {
      if (screenWakeLockRequest === request) screenWakeLockRequest = null;
    });
  }

  function syncScreenWakeLock() {
    if (screenWakeLockEligible()) requestScreenWakeLock();
    else releaseScreenWakeLock();
  }

  function hideFullscreenHint() {
    if (fullscreenHintTimer) window.clearTimeout(fullscreenHintTimer);
    fullscreenHintTimer = 0;
    fullscreenHint.hidden = true;
    fullscreenHint.textContent = '';
  }

  function showFullscreenHint(message = '') {
    hideFullscreenHint();
    fullscreenHint.textContent = message ||
      t(WINDOWS_PLATFORM ? 'fullscreenHintWindows' : 'fullscreenHintTouch');
    fullscreenHint.hidden = false;
    fullscreenHintTimer = window.setTimeout(() => {
      fullscreenHintTimer = 0;
      fullscreenHint.hidden = true;
      fullscreenHint.textContent = '';
    }, FULLSCREEN_HINT_DURATION_MILLIS);
  }

  function resetPresentationGuideAutoDismiss() {
    presentationGuideTimerGeneration += 1;
    if (presentationGuideAutoDismissTimer) {
      window.clearTimeout(presentationGuideAutoDismissTimer);
    }
    if (presentationGuideCountdownTimer) {
      window.clearInterval(presentationGuideCountdownTimer);
    }
    presentationGuideAutoDismissTimer = 0;
    presentationGuideCountdownTimer = 0;
    presentationGuideAutoDismissDeadline = 0;
    presentationGuideCountdown.hidden = true;
    presentationGuideCountdownText.textContent = '';
    presentationGuideProgress.hidden = false;
    presentationGuideProgress.max = PRESENTATION_GUIDE_AUTO_DISMISS_MILLIS;
    presentationGuideProgress.value = PRESENTATION_GUIDE_AUTO_DISMISS_MILLIS;
    presentationGuideProgress.removeAttribute('aria-valuetext');
  }

  function updatePresentationGuideCountdown(generation, deadline) {
    if (!presentationGuideOpen || generation !== presentationGuideTimerGeneration ||
        deadline <= 0 || deadline !== presentationGuideAutoDismissDeadline) return false;
    const remainingMillis = Math.max(0, deadline - Date.now());
    const remainingSeconds = Math.ceil(remainingMillis / 1000);
    const message = t('presentationGuideAutoDismiss').replace(
      '{seconds}',
      String(remainingSeconds)
    );
    if (presentationGuideCountdownText.textContent !== message) {
      presentationGuideCountdownText.textContent = message;
    }
    presentationGuideProgress.value = remainingMillis;
    presentationGuideProgress.setAttribute('aria-valuetext', message);
    return true;
  }

  function expirePresentationGuideAutoDismiss(generation, deadline) {
    if (!presentationGuideOpen || generation !== presentationGuideTimerGeneration ||
        deadline <= 0 || deadline !== presentationGuideAutoDismissDeadline) return false;
    const remainingMillis = deadline - Date.now();
    if (remainingMillis > 0) {
      updatePresentationGuideCountdown(generation, deadline);
      presentationGuideAutoDismissTimer = window.setTimeout(
        () => expirePresentationGuideAutoDismiss(generation, deadline),
        remainingMillis
      );
      return false;
    }
    presentationGuideAutoDismissTimer = 0;
    hidePresentationGuide(true);
    return true;
  }

  function startPresentationGuideAutoDismiss() {
    resetPresentationGuideAutoDismiss();
    const generation = presentationGuideTimerGeneration;
    const deadline = Date.now() + PRESENTATION_GUIDE_AUTO_DISMISS_MILLIS;
    presentationGuideAutoDismissDeadline = deadline;
    presentationGuideCountdown.hidden = false;
    updatePresentationGuideCountdown(generation, deadline);
    presentationGuideCountdownTimer = window.setInterval(
      () => updatePresentationGuideCountdown(generation, deadline),
      PRESENTATION_GUIDE_COUNTDOWN_INTERVAL_MILLIS
    );
    presentationGuideAutoDismissTimer = window.setTimeout(
      () => expirePresentationGuideAutoDismiss(generation, deadline),
      PRESENTATION_GUIDE_AUTO_DISMISS_MILLIS
    );
  }

  function stopPresentationGuideAutoDismissForInteraction() {
    if (!presentationGuideOpen || presentationGuideAutoDismissDeadline <= 0) return false;
    presentationGuideTimerGeneration += 1;
    if (presentationGuideAutoDismissTimer) window.clearTimeout(presentationGuideAutoDismissTimer);
    if (presentationGuideCountdownTimer) window.clearInterval(presentationGuideCountdownTimer);
    presentationGuideAutoDismissTimer = 0;
    presentationGuideCountdownTimer = 0;
    presentationGuideAutoDismissDeadline = 0;
    presentationGuideCountdownText.textContent = t('presentationGuideAutoDismissStopped');
    presentationGuideProgress.hidden = true;
    presentationGuideProgress.removeAttribute('aria-valuetext');
    return true;
  }

  function presentationGuideInteraction(event) {
    if (!presentationGuideOpen || !event) return;
    const target = event.target;
    const dismissalTarget = target === presentationGuideDismiss ||
      (target && typeof presentationGuideDismiss.contains === 'function' &&
        presentationGuideDismiss.contains(target));
    if (dismissalTarget && (event.type !== 'keydown' || event.key === 'Enter' || event.key === ' ')) {
      return;
    }
    if (event.type === 'keydown' && event.key === 'Escape') return;
    stopPresentationGuideAutoDismissForInteraction();
  }

  function hidePresentationGuide(restoreFocus = false) {
    if (!presentationGuideOpen && presentationGuide.hidden) return;
    const focusWasInside = typeof presentationGuide.contains === 'function' &&
      presentationGuide.contains(document.activeElement);
    presentationGuideOpen = false;
    presentationGuide.hidden = true;
    resetPresentationGuideAutoDismiss();
    presentationGuideDismissForever.checked = false;
    if (restoreFocus && focusWasInside) {
      const focusTarget = expandedViewActive() ? viewer : fullscreenButton;
      try { focusTarget.focus({preventScroll: true}); } catch (_) { focusTarget.focus(); }
    }
  }

  function maybeShowPresentationGuide() {
    if (!expandedViewActive() || presentationGuideWasDismissed()) return false;
    hideFullscreenHint();
    presentationGuideOpen = true;
    presentationGuideDismissForever.checked = false;
    presentationGuide.hidden = false;
    startPresentationGuideAutoDismiss();
    window.requestAnimationFrame(() => {
      if (!presentationGuideOpen || presentationGuide.hidden) return;
      try { presentationGuideDismiss.focus({preventScroll: true}); } catch (_) {
        presentationGuideDismiss.focus();
      }
    });
    return true;
  }

  function syncFullscreenState() {
    const nativeFullscreen = viewerOwnsFullscreen();
    const expanded = nativeFullscreen || theaterMode;
    if (expanded) setPresentationAlignmentPickerOpen(false, false);
    fullscreenButton.hidden = expanded;
    viewerControls.hidden = expanded;
    fullscreenButton.setAttribute('aria-pressed', String(expanded));
    fullscreenButton.setAttribute(
      'aria-label',
      expanded ? t('fullscreenExitLabel') : t('fullscreenEnterLabel')
    );
    fullscreenButton.textContent = t(expanded ? 'fullscreenExit' : 'fullscreenEnter');
    fullscreenState.textContent = nativeFullscreen
      ? t('fullscreenViewState')
      : theaterMode ? t('theaterViewState') : t('normalViewState');
    if (expanded && !expandedViewWasActive && !maybeShowPresentationGuide()) showFullscreenHint();
    if (!expanded) {
      hideFullscreenHint();
      hidePresentationGuide(false);
    }
    expandedViewWasActive = expanded;
    if (expanded) clearStandardProjectionBounds();
    syncViewportAuthorityNotice();
    syncScreenWakeLock();
    scheduleViewportLayoutSync();
  }

  function setTheaterMode(enabled) {
    theaterMode = Boolean(enabled);
    expandedViewWanted = theaterMode;
    document.body.classList.toggle('theater-mode', theaterMode);
    syncFullscreenState();
  }

  const EXPANDED_VIEW_HISTORY_STATE_KEY = 'navonwebExpandedView';

  function expandedViewHistoryStateOwned() {
    const state = window.history ? window.history.state : null;
    return Boolean(state && typeof state === 'object' && state[EXPANDED_VIEW_HISTORY_STATE_KEY] === true);
  }

  function pushExpandedViewHistoryEntry() {
    if (expandedViewHistoryEntry) return;
    if (!window.history || typeof window.history.pushState !== 'function') return;
    try {
      // Pushed synchronously inside the user's click / pinch task, so the browser records it as
      // gesture-backed: Chrome's history-manipulation intervention only skips entries that were
      // added without user activation.
      window.history.pushState({ [EXPANDED_VIEW_HISTORY_STATE_KEY]: true }, '');
      expandedViewHistoryEntry = true;
    } catch (_) {
      // Sandboxed or history-less contexts: the expanded view still works, Back just leaves.
    }
  }

  function consumeExpandedViewHistoryEntry() {
    if (!expandedViewHistoryEntry) return;
    expandedViewHistoryEntry = false;
    // Only our own entry is popped. After the user pressed Back the browser already removed it,
    // and popping again would leave the page.
    if (!expandedViewHistoryStateOwned()) return;
    if (!window.history || typeof window.history.back !== 'function') return;
    expandedViewHistoryPopPending = true;
    try {
      window.history.back();
    } catch (_) {
      expandedViewHistoryPopPending = false;
    }
  }

  function neutralizeStaleExpandedViewHistoryState() {
    // A reload while the view was expanded keeps the pushed entry as the current one. Strip the
    // marker so this document never mistakes that leftover for an entry it owns.
    if (!expandedViewHistoryStateOwned()) return;
    try {
      window.history.replaceState(null, '');
    } catch (_) {
      // The marker only matters for entries this document pushed itself.
    }
  }

  async function setExpandedView(enabled) {
    const generation = ++expandedViewRequestGeneration;
    cancelActivePointer();
    if (!enabled) {
      expandedViewWanted = false;
      consumeExpandedViewHistoryEntry();
      // A native request still in flight is abandoned here; if it lands later the
      // fullscreenchange handler exits it again because the view is no longer wanted.
      fullscreenEntryPendingGeneration = 0;
      if (theaterMode) {
        theaterMode = false;
        document.body.classList.remove('theater-mode');
      }
      if (viewerOwnsFullscreen()) await document.exitFullscreen().catch(() => null);
      if (generation === expandedViewRequestGeneration) {
        if (!expandedViewActive()) releaseExpandedProjectionViewport();
        syncFullscreenState();
      }
      return;
    }
    expandedViewWanted = true;
    if (expandedViewActive()) return;
    lockExpandedProjectionViewport();
    const preparation = preapplyExpandedProjectionViewport();
    if (preparation) preparation.catch(() => null);
    // Theater mode first: the projection already fills the page on browsers that reject, ignore,
    // or never settle the Fullscreen API (kiosk shells, some vehicle browsers). A successful
    // native request only upgrades the view - fullscreenchange swaps theater mode for the real
    // thing, and the locked entry viewport keeps that swap from renegotiating with the phone.
    setTheaterMode(true);
    // One history entry per expanded view: Back (button, gesture, Alt+Left) then exits the view
    // via popstate instead of leaving the page. Esc, pinch, and a native exit pop it themselves.
    pushExpandedViewHistoryEntry();
    if (typeof viewer.requestFullscreen !== 'function' || document.fullscreenEnabled === false) return;
    if (fullscreenEntryPendingGeneration !== 0) return;
    fullscreenEntryPendingGeneration = generation;
    let timeoutId = 0;
    try {
      await Promise.race([
        viewer.requestFullscreen(),
        new Promise((_, reject) => {
          timeoutId = window.setTimeout(
            () => reject(new Error('native fullscreen timeout')),
            NATIVE_FULLSCREEN_TIMEOUT_MS
          );
        }),
      ]);
    } catch (_) {
      // 거부·타임아웃·미지원: 이미 극장 모드이므로 그대로 둔다.
    } finally {
      if (timeoutId) window.clearTimeout(timeoutId);
      if (fullscreenEntryPendingGeneration === generation) fullscreenEntryPendingGeneration = 0;
    }
    if (generation !== expandedViewRequestGeneration) return;
    syncFullscreenState();
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
      if (signal && signal.aborted) throw abortError();
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
        if (signal) {
          signal.addEventListener('abort', onAbort, {once: true});
        }
        try {
          if (signal && signal.aborted) {
            onAbort();
            return;
          }
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
        const startedAt = diagnosticClockMillis();
        recordConnectionTiming('Signal', 'websocket_start', startedAt);
        const socket = new WebSocket(this.config.webSocketUrl);
        this.socket = socket;
        let settled = false;
        const timeout = window.setTimeout(() => {
          if (settled) return;
          settled = true;
          recordConnectionTiming('Signal', 'websocket_timeout', startedAt, 'timeout');
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
          recordConnectionTiming('Signal', 'websocket_open', startedAt, 'open');
          this.reconnectAttempt = 0;
          this.nextConnectAt = 0;
          resolve();
        }));
        socket.addEventListener('message', event => this.receive(socket, event.data));
        socket.addEventListener('error', () => {
          const error = new Error('Cloud relay connection failed');
          recordConnectionTiming('Signal', 'websocket_error', startedAt, 'error');
          this.clearSocket(socket, error);
          settle(() => reject(error));
        });
        socket.addEventListener('close', event => {
          const error = new Error(`Cloud relay closed (${event.code})`);
          recordConnectionTiming('Signal', 'websocket_close', startedAt, String(event.code));
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

  function cancelControlWebRtcOpenWatchdog(generation = 0) {
    if (generation && generation !== webRtcControlNegotiatingGeneration) return;
    if (webRtcControlOpenTimer) window.clearTimeout(webRtcControlOpenTimer);
    webRtcControlOpenTimer = 0;
    webRtcControlNegotiatingGeneration = 0;
  }

  function isControlWebRtcChannelNegotiating(generation) {
    const transport = webRtcControlTransport;
    return generation === webRtcGeneration &&
      generation === webRtcControlNegotiatingGeneration &&
      Boolean(transport) && transport.generation === generation && !transport.closed &&
      transport.channel.readyState === 'connecting';
  }

  function beginControlWebRtcChannelNegotiation(transport) {
    cancelControlWebRtcOpenWatchdog();
    webRtcControlNegotiatingGeneration = transport.generation;
  }

  function armControlWebRtcOpenWatchdog(generation) {
    if (webRtcControlOpenTimer || !isControlWebRtcChannelNegotiating(generation)) return;
    webRtcControlOpenTimer = window.setTimeout(() => {
      webRtcControlOpenTimer = 0;
      if (!isControlWebRtcChannelNegotiating(generation)) {
        if (webRtcControlNegotiatingGeneration === generation) {
          webRtcControlNegotiatingGeneration = 0;
        }
        return;
      }
      // End the grace period before scheduling failure. A late open event must not resurrect a
      // generation whose bounded control-channel negotiation already expired.
      webRtcControlNegotiatingGeneration = 0;
      markLocalControlUnavailable('open_timeout', generation);
    }, CONTROL_WEBRTC_OPEN_TIMEOUT_MILLIS);
  }

  class WebRtcControlTransport {
    constructor(channel, generation) {
      this.channel = channel;
      this.generation = generation;
      this.pending = new Map();
      this.closed = false;
      channel.addEventListener('open', () => {
        if (!this.isCurrent()) return;
        cancelControlWebRtcOpenWatchdog(this.generation);
        localControlCutover = true;
        pad.dataset.navonwebControlTransport = 'direct';
        if (streamStateKey === 'localControlRecovering') setStreamState('videoWaiting');
        cancelNoticeRequestForRetry();
        ensureNoticesLoaded();
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
        return Promise.reject(markLocalControlUnavailable('in_flight_saturated', this.generation));
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
        return Promise.reject(markLocalControlUnavailable('buffer_congested', this.generation));
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
      if (renderTouchPresence(envelope)) return;
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
        cancelControlWebRtcOpenWatchdog(this.generation);
        webRtcControlTransport = null;
        // Only cloud relay depends on this channel for API calls. On direct LAN it carries the
        // shared pointer and nothing else, so losing it costs the pointer, not the picture.
        if (CLOUD_RELAY_MODE) markLocalControlUnavailable('channel_failed', this.generation);
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
      'x-browser-device-name',
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

  function markLocalControlUnavailable(reason, generation = webRtcGeneration) {
    pad.dataset.navonwebControlTransport = `local_unavailable_${reason}`;
    setStreamState('localControlRecovering');
    window.setTimeout(() => {
      if (generation !== webRtcGeneration || (!webRtcPeer && !webRtcStarting)) return;
      // A viewport retry can arrive after the previous direct channel closed but before the new
      // generation's control DataChannel opens, including after PeerConnection reports connected.
      // Keep the request local-only and preserve that generation only during its bounded, watched
      // DataChannel negotiation. Timeout, post-open loss and explicit channel failure remain fatal.
      if (reason === 'channel_not_open' && isControlWebRtcChannelNegotiating(generation)) return;
      failWebRtc();
    }, 0);
    return new Error(`Local WebRTC control channel unavailable (${reason})`);
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

  function isLocalOnlyWebRtcControlRequest(path, options) {
    const method = String(options.method || 'GET').toUpperCase();
    const pathname = String(path || '').split('?')[0];
    return (method === 'GET' && pathname === '/api/notices') ||
      (method === 'POST' && pathname === '/api/touch') ||
      (localControlCutover && method === 'POST' && pathname === '/api/projection/viewport');
  }

  function isCloudRelaySignalingRequest(path, options) {
    const method = String(options.method || 'GET').toUpperCase();
    const pathname = String(path || '').split('?')[0];
    if ((method === 'GET' && (
      pathname === '/health' ||
      pathname === '/api/status' ||
      pathname === '/api/projection/profile' ||
      pathname === '/api/projection/viewport' ||
      pathname === '/api/webrtc/capabilities'
    )) || (method === 'POST' && (
      pathname === '/api/pair' ||
      pathname === '/api/projection/viewport' ||
      pathname === '/api/webrtc/session'
    ))) return true;
    return (method === 'GET' || method === 'DELETE') &&
      /^\/api\/webrtc\/session\/[A-Za-z0-9_-]{16,64}$/u.test(pathname);
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
      // Touch is always local-only. Initial viewport geometry may cross signaling before the
      // DataChannel opens, but becomes local-only after cutover. Read-only status/profile/viewport
      // metadata remains eligible for signaling during recovery and never carries media or input.
      if (isLocalOnlyWebRtcControlRequest(path, requestOptions)) {
        return Promise.reject(markLocalControlUnavailable('channel_not_open'));
      }
      if (!isCloudRelaySignalingRequest(path, requestOptions)) {
        return Promise.reject(new Error('Cloud relay is restricted to connection signaling'));
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
    return Boolean(browserCredential) && browserSessionCanControl() &&
      androidAutoInteractive && pageActive && !document.hidden;
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
    // Bulk Int16Array views replace the previous per-sample DataView.getInt16 loop, which cost
    // sampleRate * channels virtual calls per second of audio on the vehicle browser's CPU.
    const sampleCount = frameCount * stream.channels;
    let samples;
    if (PLATFORM_LITTLE_ENDIAN && pcmBytes.byteOffset % 2 === 0) {
      samples = new Int16Array(pcmBytes.buffer, pcmBytes.byteOffset, sampleCount);
    } else if (PLATFORM_LITTLE_ENDIAN) {
      // Odd chunk offsets take one aligned byte copy before the typed-array view.
      const aligned = new Uint8Array(sampleCount * 2);
      aligned.set(pcmBytes.subarray(0, sampleCount * 2));
      samples = new Int16Array(aligned.buffer);
    } else {
      const view = new DataView(pcmBytes.buffer, pcmBytes.byteOffset, sampleCount * 2);
      samples = new Int16Array(sampleCount);
      for (let index = 0; index < sampleCount; index += 1) {
        samples[index] = view.getInt16(index * 2, true);
      }
    }
    const channelCount = stream.channels;
    for (let channel = 0; channel < channelCount; channel += 1) {
      const output = buffer.getChannelData(channel);
      for (let frameIndex = 0; frameIndex < frameCount; frameIndex += 1) {
        output[frameIndex] = samples[frameIndex * channelCount + channel] / 32768;
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
    outputAudioWebRtcRecoveryGeneration = generation;
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

  function clearOutputAudioWebRtcRecovery(resetAttempts = false) {
    cancelOutputAudioWebRtcRecovery(resetAttempts);
    outputAudioWebRtcRecoveryRequired = false;
    outputAudioWebRtcRecoveryGeneration = 0;
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
    const recoveryGeneration = outputAudioWebRtcRecoveryGeneration;
    if (!CLOUD_RELAY_MODE || !outputAudioWebRtcRecoveryRequired || outputAudioWebRtcUnsupported ||
        recoveryGeneration === 0 || recoveryGeneration !== webRtcGeneration ||
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
          outputAudioWebRtcRecoveryGeneration !== recoveryGeneration ||
          recoveryGeneration !== webRtcGeneration ||
          !pageActive || document.hidden || !browserCredential || !androidAutoInteractive) return;
      outputAudioWebRtcRecoveryAttempts += 1;
      if (webRtcPeer || webRtcStarting) failWebRtc();
      else scheduleWebRtcRecovery();
    }, delayMillis);
  }

  function confirmOutputAudioWebRtcChannelsOpen() {
    // A failed track is removed from the map. Do not mistake the remaining open
    // subset for a complete channel set and cancel the required full-peer retry.
    if (outputAudioWebRtcRecoveryRequired || !webRtcAudioChannels.size ||
        [...webRtcAudioChannels.values()].some(channel => channel.readyState !== 'open')) return;
    clearOutputAudioWebRtcRecovery(true);
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
    // Creating this generation's channels consumes any recovery request left by
    // a previous peer. Their own post-connect open timers cover this attempt.
    clearOutputAudioWebRtcRecovery(false);
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
          document.hidden || webRtcStarting || webRtcRecoveryInFlight) return;
      webRtcRecoveryInFlight = true;
      let attempted = false;
      try {
        if (webRtcPeer) {
          if (isWebRtcConnected(webRtcPeer)) {
            cancelWebRtcRecovery(true);
            return;
          }
          resetWebRtc(true);
          await delay(WEBRTC_RECOVERY_CLOSE_GRACE_MILLIS);
        }
        // startWebRtc owns the common close/status admission gate for every offer entry.
        if (pageActive && browserCredential === requestedCredential &&
            androidAutoInteractive && !document.hidden) {
          attempted = await startWebRtc();
        }
      } finally {
        webRtcRecoveryInFlight = false;
        if (attempted && !webRtcPeer && browserCredential === requestedCredential &&
            androidAutoInteractive) {
          webRtcRecoveryAttempts += 1;
        }
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
    const capabilitiesPromise = (async () => {
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
          clearOutputAudioWebRtcRecovery(true);
        }
        pad.dataset.navonwebIceServerCount = String(webRtcServerCapabilities.iceServers.length);
        pad.dataset.navonwebControlCapability = webRtcServerCapabilities.controlDataChannelV1
          ? 'available'
          : 'unavailable';
        return webRtcServerCapabilities;
      } catch (_) {
        if (requestIsCurrent()) {
          webRtcServerCapabilities = null;
          pad.dataset.navonwebWebRtcFailureStage = 'capabilities';
        }
        return null;
      }
    })();
    webRtcCapabilitiesPromise = capabilitiesPromise;
    const capabilities = await capabilitiesPromise;
    if (!capabilities && requestIsCurrent() && webRtcCapabilitiesPromise === capabilitiesPromise) {
      webRtcCapabilitiesPromise = null;
    }
    return capabilities;
  }

  function createControlWebRtcChannel(peer, generation) {
    let channel = null;
    try {
      channel = peer.createDataChannel(CONTROL_WEBRTC_CHANNEL_LABEL, {ordered: true});
      const transport = new WebRtcControlTransport(channel, generation);
      const previous = webRtcControlTransport;
      webRtcControlTransport = transport;
      if (previous) previous.close();
      if (CLOUD_RELAY_MODE) {
        // Cloud relay carries API calls over this channel, so its absence is fatal and the
        // bounded open watchdog applies.
        beginControlWebRtcChannelNegotiation(transport);
        pad.dataset.navonwebControlTransport = 'negotiating';
      } else {
        // Direct LAN reaches the phone over HTTP and needs nothing from this channel except the
        // other sessions' touch_presence events, which the phone only sends to sessions that
        // have one open. It is opened for the shared pointer alone: no watchdog, and a failure
        // must never take the video session with it.
        pad.dataset.navonwebControlTransport = 'presence_only';
      }
      return transport;
    } catch (error) {
      if (channel) {
        try { channel.close(); } catch (_) { /* setup did not complete */ }
      }
      if (!CLOUD_RELAY_MODE) {
        pad.dataset.navonwebControlTransport = 'presence_unavailable';
        console.warn('Shared pointer is unavailable for this session.', error);
        return null;
      }
      pad.dataset.navonwebControlTransport = 'local_unavailable_setup';
      console.warn('WebRTC control channel is unavailable; cloud control fallback is disabled.', error);
      return null;
    }
  }

  function disposeControlWebRtcChannel() {
    const transport = webRtcControlTransport;
    cancelControlWebRtcOpenWatchdog();
    webRtcControlTransport = null;
    if (transport) transport.close();
    if (CLOUD_RELAY_MODE) pad.dataset.navonwebControlTransport = 'local_unavailable';
  }

  function stopWebRtcVideoStats() {
    if (webRtcVideoStatsTimer) window.clearInterval(webRtcVideoStatsTimer);
    webRtcVideoStatsTimer = 0;
    webRtcVideoStatsGeneration = 0;
    webRtcVideoStatsPrevious = null;
    delete pad.dataset.navonwebVideoBitrateKbps;
    delete pad.dataset.navonwebVideoFrameSize;
    delete pad.dataset.navonwebVideoFramesPerSecond;
    delete pad.dataset.navonwebVideoAverageQp;
  }

  async function publishWebRtcVideoStats(peer, generation) {
    if (!peer || generation !== webRtcGeneration || peer !== webRtcPeer ||
        typeof peer.getStats !== 'function') return;
    try {
      const report = await peer.getStats();
      let inbound = null;
      report.forEach(entry => {
        if (!inbound && entry.type === 'inbound-rtp' &&
            (entry.kind === 'video' || entry.mediaType === 'video')) inbound = entry;
      });
      if (!inbound || generation !== webRtcGeneration || peer !== webRtcPeer) return;
      const timestampMillis = Number(inbound.timestamp) || performance.now();
      const bytesReceived = Math.max(0, Number(inbound.bytesReceived) || 0);
      const framesDecoded = Math.max(0, Number(inbound.framesDecoded) || 0);
      const qpSum = Number.isFinite(Number(inbound.qpSum)) ? Number(inbound.qpSum) : null;
      const previous = webRtcVideoStatsPrevious;
      let bitrateKbps = null;
      let averageQp = null;
      if (previous && timestampMillis > previous.timestampMillis) {
        const elapsedSeconds = (timestampMillis - previous.timestampMillis) / 1000;
        bitrateKbps = Math.max(0, bytesReceived - previous.bytesReceived) * 8 /
          elapsedSeconds / 1000;
        const decodedDelta = Math.max(0, framesDecoded - previous.framesDecoded);
        if (qpSum !== null && previous.qpSum !== null && decodedDelta > 0) {
          averageQp = Math.max(0, qpSum - previous.qpSum) / decodedDelta;
        }
      }
      webRtcVideoStatsPrevious = {timestampMillis, bytesReceived, framesDecoded, qpSum};
      const width = Math.max(0, Number(inbound.frameWidth) || webRtcVideo.videoWidth || 0);
      const height = Math.max(0, Number(inbound.frameHeight) || webRtcVideo.videoHeight || 0);
      const framesPerSecond = Math.max(0, Number(inbound.framesPerSecond) || 0);
      pad.dataset.navonwebVideoFrameSize = width && height ? `${width}x${height}` : 'unknown';
      pad.dataset.navonwebVideoFramesPerSecond = framesPerSecond.toFixed(1);
      if (bitrateKbps !== null) pad.dataset.navonwebVideoBitrateKbps = bitrateKbps.toFixed(0);
      if (averageQp !== null) pad.dataset.navonwebVideoAverageQp = averageQp.toFixed(1);
      console.info(
        `NAVONWEB_VIDEO_STATS size=${pad.dataset.navonwebVideoFrameSize} ` +
          `fps=${framesPerSecond.toFixed(1)} bitrateKbps=${bitrateKbps === null ? 'warming' : bitrateKbps.toFixed(0)} ` +
          `avgQp=${averageQp === null ? 'unknown' : averageQp.toFixed(1)} ` +
          `lost=${Math.max(0, Number(inbound.packetsLost) || 0)}`
      );
    } catch (_) {
      // Video stats are diagnostic only and must never affect the media session.
    }
  }

  function startWebRtcVideoStats(peer, generation) {
    if (webRtcVideoStatsTimer && webRtcVideoStatsGeneration === generation) return;
    stopWebRtcVideoStats();
    webRtcVideoStatsGeneration = generation;
    publishWebRtcVideoStats(peer, generation);
    webRtcVideoStatsTimer = window.setInterval(
      () => publishWebRtcVideoStats(peer, generation),
      WEBRTC_VIDEO_STATS_INTERVAL_MILLIS
    );
  }

  function enqueueWebRtcSessionClose(sessionId, credential) {
    if (!WEBRTC_SESSION_PATTERN.test(sessionId) || !credential) return;
    const previous = webRtcSessionCloseBarrier;
    const controller = new AbortController();
    const entry = {credential, controller, previous, promise: null};
    entry.promise = (async () => {
      if (previous && previous.credential === credential) await previous.promise;
      if (controller.signal.aborted) return;
      const timeout = window.setTimeout(
        () => controller.abort(),
        STATUS_REQUEST_TIMEOUT_MILLIS
      );
      try {
        await api(
          `/api/webrtc/session/${encodeURIComponent(sessionId)}`,
          {method: 'DELETE', signal: controller.signal},
          credential
        );
      } catch (_) {
        // A timeout, controller replacement (404), or already-closed session all
        // release the bounded admission barrier; recovery will use fresh status.
      } finally {
        window.clearTimeout(timeout);
      }
    })().finally(() => {
      if (webRtcSessionCloseBarrier === entry) webRtcSessionCloseBarrier = null;
    });
    webRtcSessionCloseBarrier = entry;
  }

  async function awaitWebRtcSessionCloseBarrier(credential) {
    const entry = webRtcSessionCloseBarrier;
    if (!entry || entry.credential !== credential) return true;
    await entry.promise;
    return webRtcSessionCloseBarrier === null || webRtcSessionCloseBarrier === entry;
  }

  async function awaitWebRtcOfferAdmission(credential) {
    if (!pageActive || document.hidden || browserCredential !== credential ||
        !androidAutoInteractive || webRtcStarting || webRtcPeer) return false;
    if (!await awaitWebRtcSessionCloseBarrier(credential)) return false;
    if (!pageActive || document.hidden || browserCredential !== credential ||
        !androidAutoInteractive || webRtcStarting || webRtcPeer) return false;
    // Every offer entry, including user-triggered local-network permission recovery,
    // must observe a settled profile and viewport. pollStatus coalesces a regular poll,
    // whose shared boolean is false while either activation is still applying.
    const statusCurrent = await pollStatus({automatic: true, webRtcPreflight: true});
    return statusCurrent && pageActive && !document.hidden && browserCredential === credential &&
      androidAutoInteractive && !webRtcStarting && !webRtcPeer;
  }

  function clearWebRtcSessionCloseBarriers() {
    let entry = webRtcSessionCloseBarrier;
    webRtcSessionCloseBarrier = null;
    while (entry) {
      entry.controller.abort();
      entry = entry.previous;
    }
  }

  function resetWebRtc(notifyServer = true) {
    cancelWebRtcRecovery();
    // A full peer replacement is itself the audio recovery attempt. Do not let
    // the previous generation's delayed audio retry close the replacement offer.
    clearOutputAudioWebRtcRecovery(false);
    const closingSessionId = webRtcSessionId;
    const closingCredential = browserCredential;
    const closingPeer = webRtcPeer;
    webRtcGeneration += 1;
    stopWebRtcVideoStats();
    disposeControlWebRtcChannel();
    clearRemoteTouchMarkers();
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
      enqueueWebRtcSessionClose(closingSessionId, closingCredential);
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

  function resumeProjectionMediaAfterLifecyclePause() {
    if (!pageActive || document.hidden || !browserCredential) return;
    resumeFramePollingAfterLifecyclePause();
    if (!webRtcPeer || !isWebRtcConnected(webRtcPeer) || !webRtcVideo.srcObject) return;
    // Mobile browsers may keep inbound RTP alive while suspending HTMLVideoElement painting.
    // Reassert playback on foreground without replacing the peer, stream, or credential.
    webRtcVideo.play().catch(() => null);
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

  function webRtcStartIsCurrent(generation, peer, credential) {
    return generation === webRtcGeneration && peer === webRtcPeer &&
      browserCredential === credential;
  }

  async function consumeWebRtcSessionOpenResponse(response, generation, peer, credential) {
    if (response.status === 401) {
      if (webRtcStartIsCurrent(generation, peer, credential)) {
        invalidateCredential(t('connectionExpired'));
      }
      return null;
    }
    if (!response.ok) {
      if (!webRtcStartIsCurrent(generation, peer, credential)) return null;
      throw new Error(`signaling HTTP ${response.status}`);
    }
    const opened = await response.json();
    const sessionId = String(opened && opened.sessionId || '');
    if (!WEBRTC_SESSION_PATTERN.test(sessionId)) {
      if (!webRtcStartIsCurrent(generation, peer, credential)) return null;
      throw new Error('invalid WebRTC session');
    }
    if (!webRtcStartIsCurrent(generation, peer, credential)) {
      enqueueWebRtcSessionClose(sessionId, credential);
      return null;
    }
    webRtcSessionId = sessionId;
    return opened;
  }

  async function waitForWebRtcAnswer(initial, generation, peer, sessionId, credential) {
    const deadline = performance.now() + WEBRTC_ANSWER_TIMEOUT_MILLIS;
    let current = initial;
    while (webRtcStartIsCurrent(generation, peer, credential) && webRtcSessionId === sessionId) {
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
      if (!webRtcStartIsCurrent(generation, peer, credential) || webRtcSessionId !== sessionId) break;
      const response = await api(
        `/api/webrtc/session/${encodeURIComponent(sessionId)}`,
        {},
        credential
      );
      if (!webRtcStartIsCurrent(generation, peer, credential) || webRtcSessionId !== sessionId) break;
      if (response.status === 401) throw new Error('browser credential expired');
      if (!response.ok) throw new Error(`signaling HTTP ${response.status}`);
      current = await response.json();
    }
    throw new Error('WebRTC cancelled');
  }

  async function startWebRtc(userInitiated = false) {
    if (!pageActive || document.hidden || !browserCredential || !androidAutoInteractive ||
        webRtcStarting || webRtcPeer) {
      syncLocalNetworkPermissionPanel();
      return false;
    }
    const requestedCredential = browserCredential;
    if (!await awaitWebRtcOfferAdmission(requestedCredential)) {
      syncLocalNetworkPermissionPanel();
      return false;
    }
    const webRtcStartedAt = diagnosticClockMillis();
    let startupStage = 'capabilities';
    recordConnectionTiming('WebRtc', startupStage, webRtcStartedAt);
    const requestedProjectionRevision = projectionProfileRevision;
    const capabilities = await ensureWebRtcCapabilities();
    if (!pageActive || document.hidden || browserCredential !== requestedCredential ||
        projectionProfileRevision !== requestedProjectionRevision ||
        !androidAutoInteractive || webRtcStarting || webRtcPeer) {
      return false;
    }
    if (!capabilities || !capabilities.available) {
      if (!capabilities) pad.dataset.navonwebWebRtcFailureStage = 'capabilities';
      else if (!capabilities.available) pad.dataset.navonwebWebRtcFailureStage = 'unavailable';
      recordConnectionTiming(
        'WebRtc',
        capabilities && !capabilities.available ? 'unavailable' : 'capabilities_failed',
        webRtcStartedAt
      );
      return true;
    }
    const compatible = capabilities.codecs.some(name => browserCodecCapabilities.has(name));
    if (!compatible) {
      pad.dataset.navonwebWebRtcFailureStage = 'no_compatible_codec';
      recordConnectionTiming('WebRtc', 'no_compatible_codec', webRtcStartedAt);
      return true;
    }
    webRtcStarting = true;
    const generation = ++webRtcGeneration;
    let peer = null;
    clearIceFailureDiagnostics();
    try {
      startupStage = 'peer';
      recordConnectionTiming('WebRtc', startupStage, webRtcStartedAt);
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
      let connectedTimingPublished = false;
      const controlTransport = capabilities.controlDataChannelV1
        ? createControlWebRtcChannel(peer, generation)
        : null;
      if (CLOUD_RELAY_MODE && !controlTransport) {
        throw new Error('WebRTC control data channel is required in cloud mode');
      }
      const microphoneChannel = browserSessionCanControl()
        ? createMicrophoneWebRtcChannel(peer, generation)
        : null;
      if (CLOUD_RELAY_MODE && browserSessionCanControl() && !microphoneChannel) {
        throw new Error('WebRTC microphone data channel is required in cloud mode');
      }
      const outputAudioTracks = capabilities.outputAudioDataChannelsV1 || [];
      if (CLOUD_RELAY_MODE && outputAudioTracks.length > 0 &&
          !createOutputAudioWebRtcChannels(peer, generation, outputAudioTracks)) {
        throw new Error('WebRTC output audio data channels are required in cloud mode');
      }
      const transceiver = peer.addTransceiver('video', {direction: 'recvonly'});
      peer.addEventListener('track', event => {
        if (generation !== webRtcGeneration || peer !== webRtcPeer || event.track.kind !== 'video') return;
        webRtcVideo.srcObject = event.streams[0] || new MediaStream([event.track]);
        webRtcVideo.play().catch(() => null);
      });
      const updateConnectionState = () => {
        if (generation !== webRtcGeneration || peer !== webRtcPeer) return;
        const state = webRtcConnectionState(peer);
        if (state === 'connected' || state === 'completed') {
          if (!connectedTimingPublished) {
            connectedTimingPublished = true;
            recordConnectionTiming('WebRtc', 'connected', webRtcStartedAt, state);
          }
          cancelWebRtcRecovery(true);
          armControlWebRtcOpenWatchdog(generation);
          armOutputAudioWebRtcOpenTimers(generation);
          pad.classList.add('webrtc-ready');
          setStreamState('');
          stopFramePolling(false);
          webRtcStarting = false;
          publishSelectedIcePair(peer, generation);
          startWebRtcVideoStats(peer, generation);
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
      recordConnectionTiming('WebRtc', startupStage, webRtcStartedAt);
      const offer = await peer.createOffer();
      await peer.setLocalDescription(offer);
      startupStage = 'ice_gathering';
      recordConnectionTiming('WebRtc', startupStage, webRtcStartedAt);
      await waitForIceGatheringComplete(peer);
      if (!webRtcStartIsCurrent(generation, peer, requestedCredential)) return true;
      startupStage = 'session_open';
      recordConnectionTiming('WebRtc', startupStage, webRtcStartedAt);
      const response = await api('/api/webrtc/session?codec=auto', {
        method: 'POST',
        headers: {'Content-Type': 'application/sdp', 'Accept': 'application/json'},
        body: peer.localDescription.sdp
      }, requestedCredential);
      const opened = await consumeWebRtcSessionOpenResponse(
        response,
        generation,
        peer,
        requestedCredential
      );
      if (!opened) return true;
      const acceptedSessionId = String(opened.sessionId);
      startupStage = 'answer';
      recordConnectionTiming('WebRtc', startupStage, webRtcStartedAt);
      const answer = await waitForWebRtcAnswer(
        opened,
        generation,
        peer,
        acceptedSessionId,
        requestedCredential
      );
      if (!webRtcStartIsCurrent(generation, peer, requestedCredential)) return true;
      await peer.setRemoteDescription({type: 'answer', sdp: answer.answerSdp});
      if (!webRtcStartIsCurrent(generation, peer, requestedCredential)) return true;
      startupStage = 'connection';
      recordConnectionTiming('WebRtc', startupStage, webRtcStartedAt);
      await waitForWebRtcConnection(peer, generation);
      if (webRtcStartIsCurrent(generation, peer, requestedCredential)) {
        delete pad.dataset.navonwebWebRtcFailureStage;
        updateConnectionState();
      }
    } catch (error) {
      if (webRtcStartIsCurrent(generation, peer, requestedCredential)) {
        const failureReason = webRtcFailureReason(error);
        recordConnectionTiming('WebRtc', 'failed', webRtcStartedAt, startupStage);
        await publishIceFailure(peer, generation, startupStage, failureReason);
        if (!webRtcStartIsCurrent(generation, peer, requestedCredential)) return true;
        pad.dataset.navonwebWebRtcFailureStage = startupStage;
        console.warn(
          `NAVONWEB_WEBRTC_START_FAILED stage=${startupStage} ` +
            `reason=${failureReason} error=${error && error.name || 'Error'}`
        );
        failWebRtc();
      }
    } finally {
      if (webRtcStartIsCurrent(generation, peer, requestedCredential)) webRtcStarting = false;
    }
    return true;
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
      setStreamState(state === 'RECONNECTING'
        ? 'androidAutoReconnecting'
        : 'androidAutoWaiting');
      syncLocalNetworkPermissionPanel();
      return;
    }
    if (!pad.classList.contains('frame-ready') && !pad.classList.contains('webrtc-ready')) {
      setStreamState('videoWaiting');
    }
    if (!wasInteractive) cancelWebRtcRecovery(true);
    if (!webRtcPeer && !webRtcStarting) scheduleWebRtcRecovery();
    scheduleOutputAudioWebRtcRecovery();
    if (audioUnlocked) startAudioStreams();
    ensureMicrophoneCapture();
    maybeShowPremiumPrompt(wasInteractive, projection);
    syncLocalNetworkPermissionPanel();
  }

  function browserSessionCanControl() {
    return browserSessionAccess === 'control';
  }

  function browserSessionOwnsViewport() {
    return browserSessionRole === 'main' && !viewportAuthorityRejected;
  }

  function viewportAuthorityNoticeEligible() {
    return Boolean(
      browserCredential && !viewer.hidden && browserSessionMetadataSupported &&
      browserSessionRole === 'viewer'
    );
  }

  function cancelViewportAuthorityNoticeTimer() {
    viewportAuthorityNoticeTimerGeneration += 1;
    if (viewportAuthorityNoticeTimer) window.clearTimeout(viewportAuthorityNoticeTimer);
    viewportAuthorityNoticeTimer = 0;
  }

  function setViewportAuthorityNoticeVisible(visible) {
    const nextState = visible ? 'visible' : 'hidden';
    if (viewportAuthorityNoticeState === nextState) return false;
    viewportAuthorityNoticeState = nextState;
    viewportAuthorityNotice.hidden = !visible;
    if (visible) viewportAuthorityNotice.textContent = t('viewportMainSessionOnly');
    scheduleViewportLayoutSync();
    return true;
  }

  function startExpandedViewportAuthorityNotice() {
    cancelViewportAuthorityNoticeTimer();
    const generation = viewportAuthorityNoticeTimerGeneration;
    const changed = setViewportAuthorityNoticeVisible(true);
    viewportAuthorityNoticeTimer = window.setTimeout(() => {
      if (generation !== viewportAuthorityNoticeTimerGeneration ||
          !viewportAuthorityNoticeEligible() || !expandedViewActive()) return;
      viewportAuthorityNoticeTimer = 0;
      setViewportAuthorityNoticeVisible(false);
    }, VIEWPORT_AUTHORITY_NOTICE_EXPANDED_MILLIS);
    return changed;
  }

  function syncViewportAuthorityNotice() {
    const eligible = viewportAuthorityNoticeEligible();
    const expanded = expandedViewActive();
    const becameEligible = eligible && !viewportAuthorityNoticeEligibleState;
    const enteredExpanded = expanded && !viewportAuthorityNoticeExpandedState;
    viewportAuthorityNoticeEligibleState = eligible;
    viewportAuthorityNoticeExpandedState = expanded;
    if (!eligible) {
      cancelViewportAuthorityNoticeTimer();
      return setViewportAuthorityNoticeVisible(false);
    }
    if (!expanded) {
      cancelViewportAuthorityNoticeTimer();
      return setViewportAuthorityNoticeVisible(true);
    }
    if (enteredExpanded || becameEligible) return startExpandedViewportAuthorityNotice();
    return false;
  }

  function normalizeBrowserSession(value) {
    if (!value || typeof value !== 'object') return null;
    const deviceId = String(value.deviceId || '');
    const rawAccess = String(value.access || '').toLowerCase();
    const access = rawAccess === 'control' || rawAccess === 'interactive'
      ? 'control'
      : rawAccess === 'read_only' || rawAccess === 'read-only' ? 'read_only' : '';
    const role = String(value.role || '').toLowerCase();
    const colorSlot = Number(value.colorSlot);
    if (!SESSION_DEVICE_ID_PATTERN.test(deviceId) || !access ||
        (role !== 'main' && role !== 'viewer') ||
        (access === 'read_only' && role === 'main') ||
        !Number.isInteger(colorSlot) || colorSlot < 0 || colorSlot > 2) return null;
    return Object.freeze({deviceId, access, role, colorSlot});
  }

  function cancelViewportAuthority() {
    viewportReportGeneration += 1;
    clearViewportReportTimer();
    pendingViewportReport = null;
    pendingViewportReportGeneration = 0;
    pendingViewportReportTimeoutAttempts = 0;
    abortViewportReportTask('not_main');
    lastViewportReportKey = '';
  }

  function clearRemoteTouchMarkers() {
    for (const marker of remoteTouchMarkers.values()) {
      if (marker.timer) window.clearTimeout(marker.timer);
      marker.element.remove();
    }
    remoteTouchMarkers.clear();
  }

  function applyBrowserSession(value) {
    const normalized = normalizeBrowserSession(value);
    // Older phone builds predate session metadata and remain single-session/control owners.
    const legacy = value === null || typeof value === 'undefined';
    const next = normalized || (legacy
      ? Object.freeze({deviceId: '', access: 'control', role: 'main', colorSlot: 0})
      : Object.freeze({deviceId: '', access: 'read_only', role: 'viewer', colorSlot: 0}));
    const wasControl = browserSessionCanControl();
    const wasMain = browserSessionOwnsViewport();
    if (normalized && normalized.role === 'main') viewportAuthorityRejected = false;
    browserSessionMetadataSupported = normalized !== null;
    browserSessionDeviceId = next.deviceId;
    browserSessionAccess = next.access;
    browserSessionRole = next.role;
    browserSessionColorSlot = next.colorSlot;
    pad.dataset.navonwebSessionAccess = next.access;
    pad.dataset.navonwebSessionRole = next.role;
    pad.dataset.navonwebSessionColorSlot = String(next.colorSlot);
    if (wasControl && !browserSessionCanControl()) {
      resetTouchTransport(false, true);
      stopMicrophoneCapture('read_only_session');
    }
    const ownsViewport = browserSessionOwnsViewport();
    if (wasMain && !ownsViewport) cancelViewportAuthority();
    if (wasMain !== ownsViewport) {
      // Re-resolve the persisted orientation against the new authority before a main-session
      // reclaim. Viewers keep the received main projection aspect and never apply a forced target.
      refreshPresentationViewportPreference();
    }
    if (!wasMain && ownsViewport) requestViewportControlReclaim();
    syncViewportAuthorityNotice();
  }

  function resetBrowserSessionState() {
    browserSessionMetadataSupported = false;
    browserSessionDeviceId = '';
    browserSessionAccess = 'read_only';
    browserSessionRole = 'viewer';
    browserSessionColorSlot = 0;
    viewportAuthorityRejected = false;
    pad.dataset.navonwebSessionAccess = 'read_only';
    pad.dataset.navonwebSessionRole = 'viewer';
    pad.dataset.navonwebSessionColorSlot = '0';
    syncViewportAuthorityNotice();
    cancelViewportAuthority();
    resetTouchTransport(false, true);
    clearRemoteTouchMarkers();
  }

  function normalizeTouchPresenceEnvelope(envelope) {
    if (!envelope || envelope.type !== 'session_event' ||
        envelope.event !== 'touch_presence') return null;
    const sourceDeviceId = String(envelope.sourceDeviceId || '');
    const colorSlot = Number(envelope.colorSlot);
    const phase = String(envelope.phase || '').toLowerCase();
    const x = Number(envelope.x);
    const y = Number(envelope.y);
    const sequence = Number(envelope.sequence);
    if (!SESSION_DEVICE_ID_PATTERN.test(sourceDeviceId) ||
        !Number.isInteger(colorSlot) || colorSlot < 0 || colorSlot > 2 ||
        !['down', 'move', 'up', 'cancel'].includes(phase) ||
        !Number.isFinite(x) || x < 0 || x > 1 ||
        !Number.isFinite(y) || y < 0 || y > 1 ||
        !Number.isSafeInteger(sequence) || sequence < 0) return null;
    return Object.freeze({sourceDeviceId, colorSlot, phase, x, y, sequence});
  }

  function renderTouchPresence(envelope) {
    const event = normalizeTouchPresenceEnvelope(envelope);
    if (!event || !sessionTouchOverlay || document.hidden ||
        event.sourceDeviceId === browserSessionDeviceId) return false;
    const previous = remoteTouchMarkers.get(event.sourceDeviceId);
    if (previous && event.sequence <= previous.sequence) return false;
    if (previous && previous.timer) window.clearTimeout(previous.timer);
    const element = previous ? previous.element : document.createElement('span');
    element.className = 'session-touch-marker';
    element.dataset.colorSlot = String(event.colorSlot);
    element.style.left = `${event.x * 100}%`;
    element.style.top = `${event.y * 100}%`;
    if (!previous) sessionTouchOverlay.appendChild(element);
    element.classList.toggle('released', event.phase === 'up' || event.phase === 'cancel');
    const state = {element, sequence: event.sequence, timer: 0};
    const removalDelay = event.phase === 'up' || event.phase === 'cancel'
      ? SESSION_TOUCH_MARKER_RELEASE_MILLIS
      : SESSION_TOUCH_MARKER_STALE_MILLIS;
    state.timer = window.setTimeout(() => {
      if (remoteTouchMarkers.get(event.sourceDeviceId) !== state) return;
      element.remove();
      remoteTouchMarkers.delete(event.sourceDeviceId);
    }, removalDelay);
    remoteTouchMarkers.set(event.sourceDeviceId, state);
    return true;
  }

  function updateMicrophoneCaptureRequest(microphone) {
    const requested = browserSessionCanControl() &&
      Boolean(microphone && microphone.captureRequested);
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

  function liveCloudDirectSessionSurvivesStatusOutage() {
    if (!CLOUD_RELAY_MODE || !webRtcPeer || !isWebRtcConnected(webRtcPeer) ||
        !webRtcControlTransport || !webRtcControlTransport.isOpen()) return false;
    if (browserSessionCanControl() && !currentMicrophoneWebRtcChannel()) return false;
    if (outputAudioWebRtcRecoveryRequired ||
        [...webRtcAudioChannels.values()].some(channel => channel.readyState !== 'open')) {
      return false;
    }
    return true;
  }

  function consumeRecoveredStatusFailure() {
    const recovered = statusFailureCount > 0;
    statusFailureCount = 0;
    delete pad.dataset.navonwebSignalingState;
    return recovered;
  }

  function handleStatusPollFailure() {
    showAuthenticatedView(true);
    statusFailureCount += 1;
    scheduleRepairPairingAction();
    const preserveDirectSession = liveCloudDirectSessionSurvivesStatusOutage();
    if (preserveDirectSession) {
      pad.dataset.navonwebSignalingState = 'degraded';
    } else if (statusFailureCount >= 2) {
      renderAndroidAutoStatus(null);
    }
    setStreamState('serverWaiting');
    return preserveDirectSession;
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
      const recoveredFromStatusFailure = consumeRecoveredStatusFailure();
      applyBrowserSession(data.browserSession);
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
      // The boolean result is also shared with a concurrent recovery preflight. Keep it
      // false for every caller while profile/viewport activation is still applying so a
      // regular poll cannot accidentally admit an offer through promise coalescing.
      if (!projectionReadyForWebRtcPreflight(data.projection)) {
        pad.dataset.navonwebWebRtcFailureStage = 'projection_reconfiguring';
        return false;
      }
      ensureWebRtcCapabilities();
      if (androidAutoInteractive && !webRtcPeer && !webRtcStarting) scheduleWebRtcRecovery();
      return true;
    } catch (_) {
      if (!statusPollTaskIsCurrent(task)) return false;
      // Cloud signaling may disappear while the LAN PeerConnection and every required direct
      // DataChannel remain healthy. Preserve that local media/input session; its own PC/DC
      // lifecycle callbacks remain the authoritative teardown boundary.
      handleStatusPollFailure();
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
    clearWebRtcSessionCloseBarriers();
    outputAudioWebRtcUnsupported = false;
    clearOutputAudioWebRtcRecovery(true);
    browserCredential = '';
    resetBrowserSessionState();
    localControlCutover = false;
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
    consumeExpandedViewHistoryEntry();
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

  async function checkRememberedCloudRelayRoute() {
    if (!CLOUD_RELAY_MODE || CLOUD_RELAY_CONFIG.roomId) return 'not_applicable';
    const startedAt = diagnosticClockMillis();
    const controller = new AbortController();
    const timeout = window.setTimeout(
      () => controller.abort(),
      CLOUD_ROUTE_STATUS_TIMEOUT_MILLIS
    );
    recordConnectionTiming('Route', 'check_start', startedAt);
    try {
      const response = await fetch(CLOUD_RELAY_CONFIG.routeStatusUrl, {
        method: 'GET',
        mode: 'same-origin',
        credentials: 'include',
        cache: 'no-store',
        redirect: 'error',
        signal: controller.signal
      });
      if (response.status === 204) {
        recordConnectionTiming('Route', 'valid', startedAt, '204');
        return 'valid';
      }
      if (response.status === 401) {
        recordConnectionTiming('Route', 'invalid', startedAt, '401');
        return 'invalid';
      }
      recordConnectionTiming('Route', 'transient', startedAt, String(response.status));
      return 'transient';
    } catch (error) {
      const reason = error && error.name === 'AbortError' ? 'timeout' : 'network';
      recordConnectionTiming('Route', 'transient', startedAt, reason);
      return 'transient';
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function normalizePairingCode(candidate) {
    return String(candidate || '').replace(/\s+/g, '');
  }

  function formatPairingCodeInput(candidate) {
    const digits = normalizePairingCode(candidate).replace(/\D+/g, '').slice(0, 8);
    return digits.length > 4 ? `${digits.slice(0, 4)} ${digits.slice(4)}` : digits;
  }

  function pairingDeviceName() {
    const platform = String(
      (navigator.userAgentData && navigator.userAgentData.platform) ||
      navigator.platform || ''
    ).replace(/[\u0000-\u001f\u007f]+/g, ' ').replace(/\s+/g, ' ').trim();
    const agent = String(navigator.userAgent || '');
    const browser = /Edg\//u.test(agent) ? 'Edge' :
      /Firefox\//u.test(agent) ? 'Firefox' :
      /Chrome\//u.test(agent) ? 'Chrome' :
      /Safari\//u.test(agent) ? 'Safari' : 'Browser';
    return `${browser}${platform ? ` · ${platform}` : ''}`.slice(0, 64);
  }

  async function pairWithCode(candidate) {
    const pairingCode = normalizePairingCode(candidate);
    if (!/^\d{8}$/.test(pairingCode)) {
      setPairStatus(t('eightDigitRequired'), true);
      code.focus();
      return;
    }
    if (pairingInFlight) return;
    pairingInFlight = true;
    pair.disabled = true;
    setPairStatus(t('connecting'));
    const pairingStartedAt = diagnosticClockMillis();
    recordConnectionTiming('Pairing', 'bootstrap_start', pairingStartedAt);
    let bootstrapRouteMissing = false;
    let bootstrapRouteExpired = false;
    let pairingFailureStage = 'bootstrap';
    try {
      const bootstrapResponse = await bootstrapCloudRelayRoute(pairingCode);
      recordConnectionTiming(
        'Pairing',
        'bootstrap_complete',
        pairingStartedAt,
        bootstrapResponse ? String(bootstrapResponse.status) : 'local'
      );
      if (bootstrapResponse && bootstrapResponse.status === 404) {
        // Do not spend another bootstrap attempt. An existing HttpOnly route may still reach
        // /api/pair; otherwise explain that the phone has not published this code yet.
        bootstrapRouteMissing = true;
        console.warn('NAVONWEB_PAIR_ROUTE_MISSING status=404');
      }
      if (bootstrapResponse && bootstrapResponse.status === 410) {
        bootstrapRouteExpired = true;
        console.warn('NAVONWEB_PAIR_ROUTE_EXPIRED status=410');
      }
      if (bootstrapResponse && freshCloudRelayRouteRequired &&
          (bootstrapRouteMissing || bootstrapRouteExpired)) {
        setPairStatus(bootstrapRouteExpired ? t('expiredCode') : t('codeNotRegistered'), true);
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
      recordConnectionTiming('Pairing', 'pair_start', pairingStartedAt);
      const response = await api('/api/pair', {
        method: 'POST',
        headers: {
          'X-Pairing-Code': pairingCode,
          'X-Browser-Device-Name': pairingDeviceName()
        },
        cache: 'no-store'
      }, '');
      recordConnectionTiming('Pairing', 'pair_complete', pairingStartedAt, String(response.status));
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
      clearWebRtcSessionCloseBarriers();
      stopFramePolling(true);
      resetPremiumPromptSession();
      resetNoticeSession();
      resetBrowserSessionState();
      browserCredential = issuedCredential;
      localControlCutover = false;
      cancelWebRtcRecovery(true);
      webRtcCapabilitiesPromise = null;
      webRtcServerCapabilities = null;
      rememberCredential(issuedCredential);
      recordConnectionTiming('Pairing', 'connected', pairingStartedAt, 'ok');
      code.value = '';
      showAuthenticatedView(true);
      startStatusPolling();
      await pollStatus();
    } catch (error) {
      const errorName = error && typeof error.name === 'string' ? error.name : 'Error';
      recordConnectionTiming('Pairing', 'failed', pairingStartedAt, errorName);
      console.warn(`NAVONWEB_PAIR_FAILED stage=${pairingFailureStage} error=${errorName}`);
      setPairStatus(
        bootstrapRouteExpired
          ? t('expiredCode')
          : bootstrapRouteMissing ? t('codeNotRegistered') : t('unableToConnect'),
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
    resetBrowserSessionState();
    browserCredential = credential;
    localControlCutover = false;
    showAuthenticatedView(true);
    const routeState = await checkRememberedCloudRelayRoute();
    if (browserCredential !== credential) return;
    if (routeState === 'invalid') {
      resetCloudRelayRouteStateForRepair();
      invalidateCredential(t('savedConnectionExpired'));
      return;
    }
    if (routeState === 'valid') setFreshCloudRelayRouteRequirement(false);
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
    if (!browserCredential || !browserSessionCanControl() ||
        !androidAutoInteractive || !androidAutoTouchReady) return;
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
        !browserSessionCanControl() || !androidAutoInteractive || !androidAutoTouchReady) return;
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
        !browserSessionCanControl() || !androidAutoInteractive || !androidAutoTouchReady) return;
    touchRecoveryCancelInFlight = true;
    enqueueTouch('cancel', lastPointerPosition, {recoveryCancel: true});
  }

  function updatePointer(phase, position) {
    if (!position) return;
    lastPointerPosition = position;
    enqueueTouch(phase, position);
  }

  function cancelPendingSingleTouch() {
    if (pendingSingleTouchTimer) window.clearTimeout(pendingSingleTouchTimer);
    pendingSingleTouchTimer = 0;
    pendingSingleTouch = null;
  }

  function activatePendingSingleTouch(pointerId) {
    if (!pendingSingleTouch || pendingSingleTouch.pointerId !== pointerId ||
        suppressAndroidAutoTouch || activePointerId !== null) return false;
    const pending = pendingSingleTouch;
    cancelPendingSingleTouch();
    if (!pending.position || !browserCredential || !browserSessionCanControl() ||
        !androidAutoInteractive || !androidAutoTouchReady) return false;
    activePointerId = pointerId;
    updatePointer('down', pending.position);
    return true;
  }

  function stagePendingSingleTouch(event) {
    cancelPendingSingleTouch();
    pendingSingleTouch = Object.freeze({
      pointerId: event.pointerId,
      clientX: event.clientX,
      clientY: event.clientY,
      position: point(event, false)
    });
    pendingSingleTouchTimer = window.setTimeout(() => {
      pendingSingleTouchTimer = 0;
      activatePendingSingleTouch(event.pointerId);
    }, TOUCH_GESTURE_DECISION_MILLIS);
  }

  function cancelActivePointer() {
    cancelPendingSingleTouch();
    if (activePointerId === null) return;
    activePointerId = null;
    updatePointer('cancel', lastPointerPosition);
  }

  function restoreCancelledPresentationPan() {
    if (!pinchGesture || pinchGesture.mode !== 'pan' || pinchGesture.applied) return;
    presentationAlignment = pinchGesture.initialAlignment;
    presentationOffset = pinchGesture.initialPreferenceOffset;
    presentationSnapState = pinchGesture.initialSnapState;
    syncPresentationControls();
    syncProjectionContentLayout();
    pinchGesture.applied = true;
    pinchGesture.mode = 'cancelled';
  }

  function clearPinchPointers() {
    restoreCancelledPresentationPan();
    cancelPendingSingleTouch();
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

  function pointerCentroid(left, right) {
    return Object.freeze({x: (left.x + right.x) / 2, y: (left.y + right.y) / 2});
  }

  function beginPinchGesture() {
    const entries = Array.from(touchPointers.entries()).slice(0, 2);
    if (entries.length !== 2) return;
    const distance = pointerDistance(entries[0][1], entries[1][1]);
    if (!Number.isFinite(distance) || distance < 1) return;
    cancelPendingSingleTouch();
    cancelActivePointer();
    suppressAndroidAutoTouch = true;
    const layout = syncProjectionContentLayout();
    pinchGesture = {
      pointerIds: [entries[0][0], entries[1][0]],
      initialDistance: distance,
      initialCentroid: pointerCentroid(entries[0][1], entries[1][1]),
      initiallyExpanded: expandedViewActive(),
      initialLayout: layout,
      initialOffset: layout ? layout.normalizedOffset : Object.freeze({x: 0, y: 0}),
      initialAlignment: presentationAlignment,
      initialPreferenceOffset: presentationOffset,
      initialSnapState: presentationSnapState,
      mode: 'pending',
      presentationChanged: false,
      intent: null,
      applied: false
    };
  }

  function beginThreeFingerPresentationReset() {
    if (touchPointers.size < 3 || !expandedViewActive()) return false;
    cancelPendingSingleTouch();
    cancelActivePointer();
    suppressAndroidAutoTouch = true;
    pinchGesture = {
      pointerIds: Array.from(touchPointers.keys()).slice(0, 3),
      initiallyExpanded: expandedViewActive(),
      mode: 'reset',
      intent: null,
      applied: true
    };
    resetPresentationPosition();
    showFullscreenHint(t('presentationReset'));
    return true;
  }

  function applyPresentationPan(gesture, centroid) {
    if (!gesture.initialLayout || !gesture.initialCentroid) return false;
    const dragged = dragPresentationOffset(
      gesture.initialLayout,
      gesture.initialOffset,
      centroid.x - gesture.initialCentroid.x,
      centroid.y - gesture.initialCentroid.y,
      gesture.initialSnapState
    );
    if (!dragged) return false;
    const movedFromInitial = Math.abs(dragged.offset.x - gesture.initialOffset.x) > 0.0001 ||
      Math.abs(dragged.offset.y - gesture.initialOffset.y) > 0.0001;
    if (gesture.mode !== 'pan' && !movedFromInitial) return false;
    const changedFromCurrent = Math.abs(dragged.offset.x - presentationOffset.x) > 0.0001 ||
      Math.abs(dragged.offset.y - presentationOffset.y) > 0.0001 ||
      dragged.snap.x !== presentationSnapState.x || dragged.snap.y !== presentationSnapState.y;
    if (!changedFromCurrent) return gesture.mode === 'pan';
    presentationAlignment = 'custom';
    presentationOffset = dragged.offset;
    presentationSnapState = dragged.snap;
    gesture.presentationChanged = true;
    syncPresentationControls();
    syncProjectionContentLayout();
    return true;
  }

  function updatePinchGesture() {
    if (!pinchGesture || pinchGesture.applied ||
        pinchGesture.mode === 'reset' || pinchGesture.mode === 'cancelled') return;
    const left = touchPointers.get(pinchGesture.pointerIds[0]);
    const right = touchPointers.get(pinchGesture.pointerIds[1]);
    if (!left || !right) return;
    const distance = pointerDistance(left, right);
    const scale = distance / pinchGesture.initialDistance;
    if (!pinchGesture.initiallyExpanded && scale >= PINCH_EXPAND_SCALE) {
      pinchGesture.intent = 'expand';
      pinchGesture.mode = 'pinch';
      return;
    }
    if (!pinchGesture.initiallyExpanded) return;
    const centroid = pointerCentroid(left, right);
    if (pinchGesture.mode === 'pan') {
      applyPresentationPan(pinchGesture, centroid);
      return;
    }
    if (scale <= PINCH_COLLAPSE_SCALE) {
      pinchGesture.intent = 'collapse';
      pinchGesture.mode = 'pinch';
      return;
    }
    if (pinchGesture.mode !== 'pending') return;
    const translation = pointerDistance(centroid, pinchGesture.initialCentroid);
    const radialChange = Math.abs(distance - pinchGesture.initialDistance);
    if (translation >= PRESENTATION_PAN_LOCK_CSS_PIXELS &&
        translation >= radialChange * PRESENTATION_PAN_DIRECTION_RATIO) {
      if (applyPresentationPan(pinchGesture, centroid)) {
        pinchGesture.mode = 'pan';
        pinchGesture.intent = null;
      }
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
    if (!consumed) return false;
    if (cancelled) {
      restoreCancelledPresentationPan();
      if (pinchGesture && !pinchGesture.applied) {
        pinchGesture.applied = true;
        pinchGesture.mode = 'cancelled';
      }
    } else {
      touchPointers.set(event.pointerId, touchPoint(event));
      updatePinchGesture();
      if (pinchGesture && pinchGesture.mode === 'pan' && !pinchGesture.applied) {
        pinchGesture.applied = true;
        if (pinchGesture.presentationChanged) rememberPresentationPreferences();
      } else {
        applyPinchIntentFromGesture();
      }
    }
    touchPointers.delete(event.pointerId);
    if (touchPointers.size === 0) {
      pinchGesture = null;
      suppressAndroidAutoTouch = false;
    }
    return consumed;
  }

  function finishSingleTouchPointer(event, cancelled) {
    if (event.pointerType !== 'touch' || !touchPointers.has(event.pointerId)) return false;
    touchPointers.delete(event.pointerId);
    if (pendingSingleTouch && pendingSingleTouch.pointerId === event.pointerId) {
      const pending = pendingSingleTouch;
      cancelPendingSingleTouch();
      if (!cancelled) {
        pendingSingleTouch = pending;
        activatePendingSingleTouch(event.pointerId);
      }
    }
    if (event.pointerId === activePointerId) {
      if (cancelled) cancelActivePointer();
      else {
        updatePointer('up', point(event));
        activePointerId = null;
      }
    }
    return true;
  }

  code.addEventListener('input', () => {
    const formatted = formatPairingCodeInput(code.value);
    if (code.value !== formatted) code.value = formatted;
  });

  pairingForm.addEventListener('submit', event => {
    event.preventDefault();
    unlockAudio();
    pairWithCode(code.value);
  });
  fullscreenButton.addEventListener('click', () => {
    unlockAudio();
    toggleFullscreen();
  });
  presentationAlignmentTrigger.addEventListener('click', () => {
    setPresentationAlignmentPickerOpen(!presentationAlignmentPickerOpen, false);
  });
  for (const input of presentationOrientationInputs) {
    input.addEventListener('change', () => {
      if (input.checked) setPresentationOrientation(input.value);
    });
  }
  for (const input of presentationAlignmentInputs) {
    input.addEventListener('change', () => {
      if (!input.checked) return;
      setPresentationAlignment(input.value);
      setPresentationAlignmentPickerOpen(false, true);
    });
    input.addEventListener('click', () => {
      if (!input.checked || !presentationAlignmentPickerOpen) return;
      setPresentationAlignment(input.value);
      setPresentationAlignmentPickerOpen(false, true);
    });
  }
  presentationAlignmentSelect.addEventListener('change', () => {
    setPresentationAlignment(presentationAlignmentSelect.value);
  });
  presentationGuideCard.addEventListener('pointerdown', presentationGuideInteraction);
  presentationGuideCard.addEventListener('click', presentationGuideInteraction);
  presentationGuideCard.addEventListener('keydown', presentationGuideInteraction);
  document.addEventListener('pointerdown', event => {
    if (!presentationAlignmentPickerOpen || presentationAlignmentLabel.contains(event.target)) return;
    setPresentationAlignmentPickerOpen(false, false);
  });
  presentationGuideDismiss.addEventListener('click', () => {
    const dismissPermanently = presentationGuideDismissForever.checked;
    if (dismissPermanently) rememberPresentationGuideDismissal();
    hidePresentationGuide(true);
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
    if (presentationGuideOpen) {
      event.preventDefault();
      return;
    }
    unlockAudio();
    if (event.pointerType === 'touch') {
      touchPointers.set(event.pointerId, touchPoint(event));
      try { pad.setPointerCapture(event.pointerId); } catch (_) { /* capture is best effort */ }
      event.preventDefault();
      if (touchPointers.size >= 3) {
        beginThreeFingerPresentationReset();
        return;
      }
      if (touchPointers.size >= 2) {
        if (!pinchGesture) beginPinchGesture();
        return;
      }
      if (suppressAndroidAutoTouch) {
        return;
      }
      stagePendingSingleTouch(event);
      return;
    }
    if (!browserCredential || !browserSessionCanControl() ||
        !androidAutoInteractive || !androidAutoTouchReady ||
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
      if (pendingSingleTouch && pendingSingleTouch.pointerId === event.pointerId) {
        event.preventDefault();
        const movement = Math.hypot(
          event.clientX - pendingSingleTouch.clientX,
          event.clientY - pendingSingleTouch.clientY
        );
        if (movement < TOUCH_GESTURE_MOVE_CSS_PIXELS ||
            !activatePendingSingleTouch(event.pointerId)) return;
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
    if (finishSingleTouchPointer(event, false)) {
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
    if (finishSingleTouchPointer(event, true)) {
      event.preventDefault();
      return;
    }
    if (event.pointerId !== activePointerId) return;
    event.preventDefault();
    updatePointer('cancel', point(event));
    activePointerId = null;
  });
  pad.addEventListener('lostpointercapture', event => {
    if (finishGesturePointer(event, true)) return;
    if (finishSingleTouchPointer(event, true)) return;
    if (event.pointerId === activePointerId) cancelActivePointer();
  });
  window.addEventListener('blur', cancelPointerInteraction);
  window.addEventListener('resize', handleViewportGeometryChange);
  document.addEventListener('fullscreenchange', () => {
    cancelPointerInteraction();
    if (viewerOwnsFullscreen() && !expandedViewWanted) {
      // A slow native entry that landed after the user already left the expanded view.
      document.exitFullscreen().catch(() => null);
      return;
    }
    if (viewerOwnsFullscreen() && theaterMode) {
      // Native fullscreen took over the theater-mode view; the entry viewport stays locked.
      theaterMode = false;
      document.body.classList.remove('theater-mode');
    }
    if (!expandedViewActive()) {
      // The browser left native fullscreen on its own (Esc, swipe, system back): drop the
      // history entry as well so the next Back leaves the page as usual.
      expandedViewWanted = false;
      consumeExpandedViewHistoryEntry();
    }
    if (!expandedViewActive() && fullscreenEntryPendingGeneration === 0) {
      releaseExpandedProjectionViewport();
    }
    syncFullscreenState();
  });
  document.addEventListener('fullscreenerror', () => {
    // Theater mode is already showing; just release the native attempt so the toggle is free.
    if (fullscreenEntryPendingGeneration !== 0 &&
        fullscreenEntryPendingGeneration === expandedViewRequestGeneration) {
      fullscreenEntryPendingGeneration = 0;
    }
    if (expandedViewWanted && !viewerOwnsFullscreen() && !theaterMode) setTheaterMode(true);
  });
  window.addEventListener('popstate', () => {
    if (expandedViewHistoryPopPending) {
      // Our own history.back() landed. Re-derive ownership in case the view was re-entered (and
      // a fresh entry pushed) before that traversal completed.
      expandedViewHistoryPopPending = false;
      expandedViewHistoryEntry = expandedViewHistoryStateOwned();
      return;
    }
    if (!expandedViewHistoryEntry) return;
    // The user pressed Back: the browser has already dropped our entry, so leave the expanded
    // view instead of the page.
    expandedViewHistoryEntry = false;
    if (expandedViewWanted || expandedViewActive()) setExpandedView(false).catch(() => null);
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && presentationAlignmentPickerOpen) {
      event.preventDefault();
      setPresentationAlignmentPickerOpen(false, true);
      return;
    }
    if (event.key === 'Escape' && presentationGuideOpen) {
      event.preventDefault();
      hidePresentationGuide(true);
      return;
    }
    if (event.key === 'Escape' && theaterMode) {
      event.preventDefault();
      setExpandedView(false).catch(() => null);
    }
  });
  document.addEventListener('visibilitychange', () => {
    syncScreenWakeLock();
    if (document.hidden) {
      cancelRepairPairingCountdown();
      clearRemoteTouchMarkers();
      stopStatusPolling();
      if (!microphoneCaptureIsTerminal()) stopMicrophoneCapture('visibility_hidden');
      stopAudioStreams(false);
      resetTouchTransport(true, true);
      cancelWebRtcRecovery();
      cancelOutputAudioWebRtcRecovery(false);
    } else if (statusPollingEligible()) {
      resumeProjectionMediaAfterLifecyclePause();
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
    setStreamState('serverWaiting');
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
  window.addEventListener('pagehide', event => {
    pageActive = false;
    releaseScreenWakeLock();
    cancelRepairPairingCountdown();
    hidePresentationGuide(false);
    hidePremiumPrompt();
    cancelNoticeRequestForRetry();
    resetTouchTransport(true, true);
    stopViewportReporting();
    stopDevelopmentNarrowCycle();
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
    if (!event.persisted) {
      // A real navigation/unload has no matching pageshow in this document. Release the phone
      // session slot and cloud transport; BFCache pagehide keeps them for the same-page resume.
      stopFramePolling(false);
      resetWebRtc(true);
      if (cloudRelayTransport) {
        cloudRelayTransport.close();
        cloudRelayTransport = null;
      }
    }
  });
  window.addEventListener('pageshow', event => {
    pageActive = true;
    resumeProjectionMediaAfterLifecyclePause();
    startDevelopmentNarrowCycle();
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
  document.addEventListener('freeze', () => {
    // The OS owns JavaScript suspension. Release only ephemeral UI ownership; the authenticated
    // media transports and JPEG reader remain intact for the matching resume/pageshow event.
    releaseScreenWakeLock();
    cancelPointerInteraction();
  });
  document.addEventListener('resume', () => {
    if (!pageActive || document.hidden) return;
    resumeProjectionMediaAfterLifecyclePause();
    resumeSameOriginConnection();
  });

  applyDocumentLocale();
  pad.style.setProperty('--projection-aspect-ratio', '5 / 3');
  syncPresentationControls();
  pad.classList.add('aa-unavailable');
  pad.dataset.navonwebRtcApi = typeof RTCPeerConnection === 'function' ? 'available' : 'unavailable';
  pad.dataset.navonwebBrowserCodecCount = String(browserCodecCapabilities.size);
  pad.dataset.navonwebLocalNetworkPermission = localNetworkPermissionState;
  installDevelopmentViewport();
  installViewportObservers();
  neutralizeStaleExpandedViewHistoryState();
  syncFullscreenState();
  refreshLocalNetworkPermission().then(() => {
    if (statusPollingEligible() && androidAutoInteractive) scheduleWebRtcRecovery();
  });
  const rememberedCredential = loadRememberedCredential();
  if (rememberedCredential) connectRemembered(rememberedCredential);
  else showAuthenticatedView(false);
})();
