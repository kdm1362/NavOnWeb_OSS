# NavOnWeb Cloudflare 배포 스캐폴드

이 디렉터리는 서로 독립적인 두 배포물을 만듭니다.

- Cloudflare Pages: Android APK에 포함된 실제 `tesla/index.html`과 `app.js`를 그대로 사용한
  HTTPS 웹 화면
- Worker + Durable Object: 짧은 URL의 최초 페어링·기기 선택과 SDP/ICE를 위한 WSS signaling relay

Pages는 서버 코드를 포함하지 않는 정적 프론트입니다. AA 영상·마이크·출력 음성과 연결 후
상태·공지·viewport·터치 제어는 앱의 WebRTC 트랙/DataChannel을 통해 브라우저와 휴대전화
사이에서 직접 전송합니다. Worker는 페어링·SDP/ICE와 직접 제어 채널이 열리기 전의 호환
fallback만 중계하며, JPEG frame과 출력 audio HTTP stream은 cloud mode에서 사용하지 않습니다.

## 파일 및 빌드 경계

Pages 원본을 별도로 복제하지 않습니다. `scripts/build-pages.mjs`가 빌드할 때 다음 파일을
읽습니다.

```text
../app/src/main/assets/tesla/index.html
../app/src/main/assets/tesla/app.js
```

Cloudflare용 소개 화면은 `pages/landing.html`과 `pages/landing.css`에서 관리하며, Android 앱의
로컬 웹 서버에는 주입하지 않습니다. 소개 화면에 쓰는 이미지는 검토된 첫 사용 안내 스크린샷을
빌드 시 복사합니다. 그리고 Git에서 제외된 `dist/pages/`에 다음을 생성합니다.

- `index.html`: packaged HTML에 Cloudflare 소개 화면, SEO/PWA 메타데이터와
  `/cloud-config.js` 로드를 추가
- `app.js`: packaged asset의 바이트 단위 복사본
- `landing.css`: 페어링 전 소개 화면 스타일
- `media/`: `docs/user-guide/screenshots/`에서 가져온 소개용 이미지
- `robots.txt`, `sitemap.xml`: 검색 엔진 크롤링과 Search Console 제출용 공개 파일
- `cloud-config.js`: 공개 WSS Origin 설정
- `_headers`: 해당 WSS Origin만 허용하는 CSP와 마이크 Permissions Policy

Pages에 디버그 입력창이나 event log UI를 추가하지 않으며, 제품 화면은 기기 ID를 URL에 노출하지
않는 `https://navonweb.com/`을 사용합니다. 처음 한 번 8자리 등록 코드로 승인하면 signed route
cookie와 브라우저 자격증명으로 같은 브라우저를 기억합니다. 인증된 화면으로 전환되는 즉시 소개
화면 전체를 숨기므로 Android Auto 뷰어의 레이아웃과 전체 화면 동작에는 관여하지 않습니다.

Play Store URL이 아직 없으면 소개 화면의 CTA는 `aria-disabled="true"`인 출시 준비 중 상태로
빌드됩니다. 실제 상품 페이지가 생기면 NavOnWeb 패키지의 공식 URL만 환경 변수로 설정합니다.

```powershell
$env:NAVONWEB_PLAY_STORE_URL='https://play.google.com/store/apps/details?id=com.eigenkodex.navonweb'
```

빌드 스크립트는 `play.google.com/store/apps/details`의
`com.eigenkodex.navonweb` 상품만 허용하며 다른 호스트나 패키지는 거부합니다.

생성되는 공개 설정은 다음 형태입니다.

```js
window.NAVONWEB_CLOUD_CONFIG = Object.freeze({
  signalingWebSocketOrigin: "wss://navonweb.com",
  signalingWebSocketPathPrefix: "/_nw"
});
```

## Android와 동일한 room 계약

장치 secret은 32바이트 난수를 padding 없는 base64url로 표현한 43자 문자열입니다. Room
ID는 **secret을 디코딩하지 않고 43자 US-ASCII 문자열 자체를 해시**해 만듭니다.

```text
roomId = base64url(SHA-256(US_ASCII(deviceSecret))).take(22)
```

장치 연결:

```http
GET /ws/device/{22-character-roomId}
Upgrade: websocket
Authorization: Bearer {43-character-deviceSecret}
```

브라우저 연결(등록 코드 bootstrap이 발급한 signed route cookie로 내부 room을 선택):

```http
GET /_nw/ws/browser
Upgrade: websocket
Origin: https://configured-pages-origin
Cookie: __Host-navonweb_route={signed-route-cookie}
```

Worker는 장치 Bearer를 해시해 URL의 Room ID와 고정 시간 비교하고, Durable Object에는
secret을 넘기지 않습니다. 브라우저는 다음 조건을 모두 만족해야 합니다.

- `ALLOWED_BROWSER_ORIGINS`에 등록된 정확한 HTTPS Origin
- 8자리 등록 코드를 사용해 발급받은 유효한 signed route cookie가 있음
- 인증된 장치가 해당 room에 먼저 연결됨
- room당 동시 브라우저 한 개

Room ID는 내부 routing capability이므로 페이지 URL, 분석 URL 또는 공개 로그에 남기지 않습니다.
8자리 등록 코드는 한 번 사용되거나 10분이 지나면 게시를 닫습니다. 이후 코드를 자동으로
재게시하지 않으며, 새 브라우저를 연결할 때 휴대전화 앱에서 명시적으로 10분짜리 창을 엽니다.
코드 만료는 이미 승인된 브라우저 credential, signed route cookie 또는 연결된 세션의 수명을
제한하지 않습니다.

## 메시지 계약

모든 클라이언트 메시지는 UTF-8 JSON text object이며 프레임 전체가 최대
`196608`바이트(192 KiB)입니다. 바이너리는 거부됩니다. `requestId`는 다음 정규식과
Android 구현을 정확히 공유합니다.

```regex
^[A-Za-z0-9_-]{16,64}$
```

브라우저에서 장치로 보내는 flat request:

```json
{
  "type": "rpc_request",
  "requestId": "YzM2N2Q5NTU2YjI4M2Q4MG",
  "method": "POST",
  "target": "/api/webrtc/session?codec=auto",
  "headers": {
    "accept": "application/json",
    "content-type": "application/sdp"
  },
  "bodyBase64": "dj0wLi4u"
}
```

장치에서 브라우저로 보내는 flat response:

```json
{
  "type": "rpc_response",
  "requestId": "YzM2N2Q5NTU2YjI4M2Q4MG",
  "status": 200,
  "contentType": "application/json; charset=utf-8",
  "bodyBase64": "e30="
}
```

`ping`, `pong`, `bye`는 양방향이며 `requestId`가 필수이고 선택적인 `payload`만 허용합니다.
Worker는 envelope의 타입, 필드형, 방향과 크기만 검사하고 `bodyBase64`나 `payload`의 의미를
읽거나 다시 직렬화하지 않은 채 원본 JSON text를 중계합니다.

Durable Object는 브라우저 RPC에 다음 room별 제한을 둡니다. 이 상태는 WebSocket attachment에
직렬화되어 hibernation 뒤에도 유지됩니다.

- token bucket: 평균 64 RPC/s, 순간 burst 96
- 최대 in-flight `requestId`: 16개
- 중복 requestId 거부
- Android가 모르는 requestId의 `rpc_response`를 보내면 거부

rate 또는 in-flight 한도를 일시적으로 넘긴 정상 클라이언트에는 해당 RPC만 `429`로 응답하고
WebSocket은 유지합니다. 중복/위조 requestId처럼 세션 무결성을 훼손하는 경우에만 연결을
`1008`로 종료합니다.

Worker system 메시지는 `ready`, `peer_joined`, `peer_left`이며 클라이언트가 보낼 수 없습니다.
Android와 웹 앱은 모르는 system 타입을 무시합니다. `ready`에는 role, roomId,
`peerConnected`, 최대 메시지 크기, rate/in-flight 한도가 포함됩니다.

정책 위반 종료 코드는 `1003`(binary), `1007`(JSON), `1008`(계약/rate/in-flight),
`1009`(크기), `1011`(relay 내부 오류)입니다.

## 로컬 검증

Node.js 20 이상이 필요합니다.

```powershell
Set-Location cloudflare
npm install
Copy-Item .dev.vars.example .dev.vars
npm run check
npm test
```

두 터미널에서 실행합니다.

```powershell
npm run dev:worker
```

```powershell
npm run dev:pages
```

`dev:pages`는 `ws://localhost:8787`를 주입해 `dist/pages`를 만든 뒤 Pages를 8788에서
실행합니다. packaged `app.js`는 보안상 cloud mode에서 `wss:`만 허용하므로 실제 브라우저
통합 검증은 배포된 HTTPS/WSS 주소에서 해야 합니다. localhost 빌드는 복사·CSP 결과 확인용입니다.

테스트용 Android-compatible identity 생성 예:

```powershell
node -e "const{randomBytes,createHash}=require('node:crypto');const b=randomBytes(32),s=b.toString('base64url');console.log('DEVICE_SECRET='+s);console.log('ROOM_ID='+createHash('sha256').update(s,'ascii').digest('base64url').slice(0,22))"
```

출력된 secret을 `.dev.vars`, `wrangler.toml`, Pages 설정, Git 또는 브라우저 저장소에 넣지
마십시오. Android 앱의 사설 저장소에만 둡니다.

## Cloudflare 배포

현재 Worker 이름은 `navonweb-signaling-staging`이지만 브라우저 제품 경로는
`https://navonweb.com/_nw/*`, Android 장치 경로는 `wss://signal.navonweb.com` custom domain을
사용합니다. `workers.dev` 별칭은 추가 공개 진입점을 만들지 않도록 비활성화되어 있습니다.
실제 출시 전에는 staging과 production 리소스를 분리하고 다음 순서로 production 값을
명시적으로 교체합니다.

1. `worker/wrangler.toml`의 `ALLOWED_BROWSER_ORIGINS`를 실제 Pages Origin으로 설정합니다.
   여러 Origin은 쉼표로 구분하며 wildcard는 지원하지 않습니다. 빈 값이면 fail-closed로
   브라우저 연결을 `503` 거부합니다.
2. Worker를 먼저 배포합니다.
3. 배포된 WSS Origin을 환경 변수로 넣고 Pages를 빌드·배포합니다.

```powershell
npm run deploy:worker
$env:NAVONWEB_SIGNALING_WEBSOCKET_ORIGIN='wss://navonweb.com'
$env:NAVONWEB_PLAY_STORE_URL='https://play.google.com/store/apps/details?id=com.eigenkodex.navonweb'
npm run deploy:pages
Remove-Item Env:NAVONWEB_SIGNALING_WEBSOCKET_ORIGIN
Remove-Item Env:NAVONWEB_PLAY_STORE_URL
```

Pages의 CSP는 빌드 시 그 WSS Origin 하나를 정확히 삽입합니다. custom signaling domain으로
바꾸면 Pages도 다시 빌드해야 합니다. `NAVONWEB_SIGNALING_WEBSOCKET_ORIGIN`은 공개 주소이지
비밀키가 아닙니다.

## 비밀값 및 운영 전 확인

- Cloudflare API token, device secret, TURN key는 어떤 추적 파일에도 넣지 않습니다.
- `.dev.vars`, `.env*`, `dist/`, `.wrangler/`, `node_modules/`는 이 디렉터리의 `.gitignore`로
  제외됩니다.
- Android secret 회전/폐기 및 브라우저 credential 철회 흐름을 확인합니다.
- 실제 Pages Origin과 Worker allowlist가 정확히 일치하는지 확인합니다.
- 실기기에서 선택 ICE candidate가 `host`이고 `relay`가 아닌지 `getStats()`로 확인합니다.
- TURN을 추가한다면 장기 key는 Worker secret으로만 두고 단기 credential만 발급합니다.

## 공개 소스 오용 방어 경계

- 페어링 코드 등록은 휴대전화 앱에서 열어 둔 10분짜리 창에만 수행합니다. 새 코드는 영속적인
  단조 증가 epoch를 사용하므로, 늦게 도착한 이전 등록이 최신 코드를 다시 활성화할 수 없습니다.
- 사용된 코드 슬롯은 원래 만료 시각까지 tombstone으로 남아 재연결이나 응답 유실 뒤에도 다시
  등록되지 않습니다. 같은 코드의 정상 재시도는 새 발급 횟수로 세지 않지만 네트워크·재생 상한에는
  계속 포함되며, 같은 활성 코드를 반복 등록해도 만료 시각을 연장하지 않습니다.
- 브라우저 lookup은 설치별 10분당 12회, 기기 등록은 서로 다른 코드 발급별 10분당 12회로
  제한합니다. 이동통신사 CGNAT 사용자를 함께 차단하지 않도록 네트워크 단위 상한은 별도로 높게
  두되, 반복 요청이 그 상한을 우회하지는 못합니다.
- 페어링 본문은 `Content-Length` 유무와 관계없이 32바이트까지만 읽고, 존재하지 않는 코드와
  잘못된 코드는 같은 공개 응답을 반환합니다.
- URL에 room ID를 직접 넣는 legacy 브라우저 WebSocket은 기본적으로 닫혀 있습니다.
- 앱이 자체 생성하는 device secret만으로는 공식 Play 설치와 수정된 OSS 빌드를 구분할 수
  없습니다. 대량의 IP와 identity를 순환하는 공격은 Worker 코드만으로 완전히 막을 수 없으므로,
  배포 시 Cloudflare WAF/Rate Limiting을 실제 정상 트래픽 기준으로 설정해야 합니다.
- 규모가 커지면 Play Integrity의 요청 해시에 room ID와 서버 nonce를 결합해 서버에서 검증한 뒤,
  공식 설치에만 짧은 수명의 device admission token을 발급하는 구조로 전환합니다. 공통 비밀을
  APK나 공개 소스에 넣는 방식은 사용하지 않습니다.
