# NavOnWeb Cloudflare 구성

이 디렉터리에는 NavOnWeb 브라우저 클라이언트를 정적 사이트로 빌드하는 스크립트와,
장치·브라우저 사이의 WebRTC 연결 신호를 중계하는 Cloudflare Worker가 들어 있습니다.
특정 Cloudflare 계정·프로젝트와 운영 route 설정은 포함하지 않습니다. SEO와
개인정보처리방침처럼 사용자에게 공개되는 사이트 식별 정보는 정적 페이지에 유지합니다.

## 구성

- `pages/`: 랜딩 페이지, 개인정보처리방침, PWA 메타데이터와 정적 파일
- `scripts/build-pages.mjs`: Android에 포함되는 브라우저 클라이언트를 `dist/pages`로 조립
- `worker/src/`: Worker와 Durable Object 기반 페어링·신호 중계
- `worker/test/`, `pages/test/`: 프로토콜 및 브라우저 회귀 테스트
- `worker/wrangler.toml`: 자체 배포를 위한 일반화된 예시 설정

브라우저 UI의 원본은 다음 Android asset입니다. Cloudflare 전용 복사본을 따로 유지하지
않으므로 앱과 웹 배포가 같은 프로토콜과 동작을 사용합니다.

```text
../app/src/main/assets/tesla/index.html
../app/src/main/assets/tesla/app.js
```

빌드 결과인 `dist/`, 로컬 Wrangler 상태, 계정별 설정과 secret은 Git에서 제외됩니다.

## 요구 사항

- Node.js 22 이상
- npm
- Worker를 배포할 경우 Cloudflare 계정과 Wrangler 인증

의존성과 소스를 확인하려면 다음을 실행합니다.

```powershell
Set-Location cloudflare
npm ci
npm run check
npm test
```

## 로컬 실행

`.dev.vars.example`을 `.dev.vars`로 복사한 뒤
`BOOTSTRAP_HMAC_KEY`를 32바이트 이상의 임의 값으로 바꿉니다.
`.dev.vars`는 커밋하지 않습니다.

```powershell
Copy-Item .dev.vars.example .dev.vars
npm run dev:worker
```

다른 터미널에서 Pages를 빌드하고 실행합니다.

```powershell
npm run dev:pages
```

로컬 Pages 빌드는 `ws://localhost:8787`을 사용합니다. 배포용 빌드는 HTTPS/WSS만
허용하며 Content Security Policy에도 선택한 signaling origin만 기록합니다.

## 배포용 입력

`worker/wrangler.toml`의 기본값은 예시입니다. 배포 전에 다음 값만 자신의 환경에 맞게
설정합니다.

- `name`: 계정 안에서 고유한 Worker 이름
- `ALLOWED_BROWSER_ORIGINS`: 브라우저 클라이언트를 제공하는 정확한 HTTPS origin 목록
- `BOOTSTRAP_HMAC_KEY`: 소스에 기록하지 않고 Wrangler secret으로 저장하는 32바이트 이상 키
- 선택 사항: 자체 도메인 `routes`, 관측 설정과 환경별 Worker 이름

Origin wildcard는 허용되지 않으며 빈 allowlist에서는 브라우저 페어링이 거부됩니다.

```powershell
npx wrangler secret put BOOTSTRAP_HMAC_KEY --config worker/wrangler.toml
npm run deploy:worker
```

Pages 빌드에는 공개 주소만 환경변수로 전달합니다.

```powershell
$env:NAVONWEB_SIGNALING_WEBSOCKET_ORIGIN='wss://signal.example.com'
$env:NAVONWEB_SIGNALING_PATH_PREFIX='/_nw'
$env:NAVONWEB_PLAY_STORE_URL='https://play.google.com/store/apps/details?id=com.eigenkodex.navonweb'
npm run build:pages
npx wrangler pages deploy dist/pages --project-name '<your-pages-project>'
Remove-Item Env:NAVONWEB_SIGNALING_WEBSOCKET_ORIGIN
Remove-Item Env:NAVONWEB_SIGNALING_PATH_PREFIX
Remove-Item Env:NAVONWEB_PLAY_STORE_URL
```

`NAVONWEB_PLAY_STORE_URL`을 생략하면 설치 버튼은 준비 상태로 표시됩니다.
빌드 스크립트는 Google Play의 NavOnWeb 패키지 주소만 설치 링크로 허용합니다.

## 연결 구조

장치는 32바이트 임의 secret으로 자신을 인증합니다.

```text
roomId = base64url(SHA-256(US_ASCII(deviceSecret))).take(22)
```

장치 WebSocket은 Bearer credential로 room과 결합됩니다. 브라우저는 휴대전화가 표시한
8자리 일회용 코드를 제출하고, Worker가 발급한 `HttpOnly; Secure; SameSite=Strict`
signed route cookie로 자신의 room을 선택합니다. room ID와 device secret은 브라우저 URL에
포함되지 않습니다.

Worker는 SDP/ICE와 연결 메타데이터에 필요한 RPC만 중계합니다. 영상, 오디오, 터치와 일반
애플리케이션 데이터는 Worker를 통과하지 않습니다. 메시지는 UTF-8 JSON text이며 최대
192 KiB입니다. 자세한 endpoint와 만료 규칙은
[`BARE_URL_PAIRING.md`](./BARE_URL_PAIRING.md)에 정리되어 있습니다.

새 브라우저를 페어링해도 이전에 승인된 브라우저의 signed route는 취소되지 않습니다.
브라우저별 WebSocket과 in-flight 요청은 분리되고, 장치 측에서 허용하는 미디어 세션 수가
실제 동시 사용 범위를 결정합니다.

## 보안 및 개인정보 경계

- API token, device secret, HMAC secret, TURN credential을 추적 파일에 넣지 않습니다.
- Worker 설정에는 정확한 HTTPS origin만 허용하고 wildcard를 쓰지 않습니다.
- 일회용 코드는 최대 10분 동안만 유효하고 한 번 소비되면 같은 창에서 다시 사용되지 않습니다.
- signed route cookie는 최대 180일이며 브라우저가 사이트 데이터를 지우면 함께 제거됩니다.
- 개인정보 처리 내용은 [`pages/privacy.html`](./pages/privacy.html)에 공개된 구현과 맞춰
  유지합니다.

이 공개 소스에는 NavOnWeb signaling Worker와 Pages 빌드에 필요한 코드만 포함됩니다.
별도의 운영 백엔드, 데이터베이스, 관리자 도구, 운영 계정 설정과 secret은 이 구성의 일부가
아닙니다.
