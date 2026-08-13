# WebRTC Android Auto 키 입력 프로토콜 명세

- 문서 상태: 구현 계약 1.0
- 대상 DataChannel: `navonweb-control-v1`
- 대상 API: `POST /api/key`
- 문서 범위: WebRTC 제어 채널에서 받은 Android Auto 키 입력을 NavOnWeb Android 앱의 AASDK Input 채널로 전달하는 경로

## 1. 목적과 범위

이 문서는 NavOnWeb WebRTC 피어가 키 입력 한 건을 Android 앱에 보내고, 앱이 이를 현재 연결된 Android Auto 세션의 AASDK Input 채널로 전달하는 규약을 정의한다.

이번 구현 범위는 다음과 같다.

- 기존 `navonweb-control-v1` DataChannel RPC에 `POST /api/key`를 추가한다.
- 버튼 클릭 한 건을 단일 큐 항목으로 전달하고, Android 쪽에서 AASDK `PRESS`와 `RELEASE`를 인접하게 전송한다.
- 스크롤 휠의 왼쪽·오른쪽 한 단계를 전달한다.
- Android Auto 서비스 검색에서 지원 scan code를 광고한다.
- Android Auto의 Input binding 요청을 지원 목록과 대조한다.
- 인증된 `CONTROL` 세션만 키 입력을 보낼 수 있다.

이번 구현 범위에 포함되지 않는 항목은 다음과 같다.

- 브라우저 화면에 버튼, 키보드 이벤트 수집기 또는 설정 UI를 추가하는 작업
- 키 조합, modifier, 텍스트 입력, IME, 음성 데이터 또는 임의 HID report 전달
- 임의의 Android `KeyEvent` 값을 Android Auto scan code로 자동 변환하는 기능
- HTTP 또는 Cloudflare signaling relay를 통한 키 입력 우회 경로
- 분리된 `press`/`release`, held-key 상태, 자동 반복 또는 long press 기능

## 2. 전체 흐름

키 입력은 아래 순서로 이동한다.

1. 클라이언트가 Android 앱과 WebRTC 세션을 맺는다.
2. 클라이언트가 신뢰성 있는 ordered DataChannel을 `navonweb-control-v1` label로 연다.
3. 클라이언트가 키 입력 JSON을 Base64로 인코딩해 기존 RPC request envelope에 넣는다.
4. Android 앱이 envelope, 요청 속도, route, 세션 권한, 브라우저 자격증명과 DataChannel owner를 검증한다.
5. Android Auto 연결과 AASDK Input binding 준비 상태를 확인한다.
6. Android 앱이 키 JSON의 필드와 scan code/action 조합을 엄격하게 검증한 뒤 입력 큐에 이벤트 한 건을 넣는다.
7. AASDK runtime이 같은 순서로 스크롤 indication 한 건 또는 버튼 `PRESS`→`RELEASE` indication 두 건을 전송한다.
8. Android 앱은 queue admission 결과를 RPC response envelope로 돌려준다.

HTTP 상태 `202`는 앱의 bounded input queue가 이벤트를 인수했다는 뜻일 뿐이다. 버튼 pair가 AASDK transport로 모두 전송됐다는 확인도, Android Auto 화면에서 해당 입력의 UI 효과가 실제로 발생했거나 처리가 끝났다는 end-to-end 확인도 아니다.

## 3. 전송 계층 계약

### 3.1 DataChannel

| 속성 | 값 |
|---|---|
| label | `navonweb-control-v1` |
| 방향 | WebRTC peer가 만들고 Android 앱이 받음 |
| ordering | `ordered: true` |
| reliability | 기본 reliable SCTP, `maxRetransmits`와 `maxPacketLifeTime` 미지정 |
| 메시지 형식 | UTF-8 JSON text message |
| binary message | 허용하지 않음 |
| 한 메시지 상한 | 192 KiB |
| 동시 처리 중 request | 세션당 최대 16개 |
| RPC admission rate | 세션당 초당 64개, burst 96개 |

binary, 잘못된 UTF-8, malformed JSON, 잘못된 envelope, 중복 처리 중 `requestId`, 또는 메시지 상한 초과는 제어 채널 protocol violation이다. 구현은 해당 메시지를 애플리케이션 요청으로 처리하지 않으며, protocol rejection 종류에 따라 DataChannel을 닫을 수 있다.

### 3.2 RPC request envelope

request envelope는 정확히 다음 여섯 필드만 갖는다.

```json
{
  "type": "rpc_request",
  "requestId": "keyevt_01J8K3B9NZ6ATQ",
  "method": "POST",
  "target": "/api/key",
  "headers": {
    "content-type": "application/json",
    "x-browser-credential": "PAIRING_ISSUED_CREDENTIAL"
  },
  "bodyBase64": "eyJzY2FuQ29kZSI6NCwiYWN0aW9uIjoiY2xpY2sifQ=="
}
```

필드 계약은 다음과 같다.

| 필드 | 계약 |
|---|---|
| `type` | 정확히 `rpc_request` |
| `requestId` | 정규식 `^[A-Za-z0-9_-]{16,64}$` |
| `method` | codec이 대소문자를 정규화하므로 `post`도 수락되지만 클라이언트는 `POST`를 보냄 |
| `target` | 정확히 `/api/key`; query와 fragment를 붙이지 않음 |
| `headers` | 문자열 값만 사용; codec이 이름을 소문자로 정규화하며 클라이언트도 소문자 이름을 사용 |
| `content-type` | media type은 `application/json`; parameter가 있으면 `charset=utf-8` 하나만 허용. media type, parameter name과 charset 값의 대소문자는 구분하지 않음 |
| `x-browser-credential` | 페어링으로 발급받은 현재 브라우저 자격증명 |
| `bodyBase64` | 4장의 UTF-8 JSON body를 표준 Base64로 인코딩한 값 |

envelope에는 위 여섯 필드 외의 필드를 추가하지 않는다. headers도 Android 앱이 허용한 이름만 사용할 수 있다. origin이 포함된 absolute URI, authority, fragment, user info가 있는 target은 허용하지 않는다.

### 3.3 RPC response envelope

응답도 같은 DataChannel의 UTF-8 JSON text message 한 건이다.

```json
{
  "type": "rpc_response",
  "requestId": "keyevt_01J8K3B9NZ6ATQ",
  "status": 202,
  "contentType": "application/json; charset=utf-8",
  "bodyBase64": "eyJhY2NlcHRlZCI6dHJ1ZX0="
}
```

클라이언트는 `requestId`로 응답을 원 요청과 연결하고, `bodyBase64`를 표준 Base64로 해독한 후 `contentType`에 따라 해석한다. 응답을 받았거나 요청이 명시적으로 실패한 뒤에만 같은 논리 입력의 다음 상태로 진행하는 것을 권장한다.

## 4. `/api/key` body

v1 body 계약은 버튼의 `click`과 휠의 `scroll`만 정의한다. 분리된 버튼 상태를 표현하는 다른 action은 호환 확장이 아니라 잘못된 입력이다.

### 4.1 버튼 이벤트

일반 버튼은 정확히 `scanCode`와 `action` 두 필드만 사용한다.

```json
{"scanCode":4,"action":"click"}
```

계약은 다음과 같다.

- `scanCode`는 5장의 지원 목록 중 `SCROLL_WHEEL`이 아닌 정수다.
- `action`은 대소문자를 구분하며 정확히 `click`이다.
- `delta`를 넣으면 안 된다.
- 한 요청에는 논리적인 클릭 한 건만 넣는다.
- unknown field, duplicate field, 누락 필드, 문자열 scan code, 소수, `null`, 배열과 중첩 객체를 허용하지 않는다.
- 분리된 `press`/`release`, `longPress`, `repeat`, held-key 상태, `meta`, `modifiers`는 현 버전에 존재하지 않는다.

서버는 수락된 클릭을 단일 bounded queue 항목으로 유지한다. AASDK sender가 해당 항목을 dequeue하면 동일 scan code의 `PRESS` indication과 `RELEASE` indication을 다른 입력이 사이에 끼지 않도록 바로 이어서 보낸다. 따라서 클라이언트는 버튼 클릭마다 요청 하나만 보내며 별도의 해제 요청을 만들지 않는다.

### 4.2 스크롤 휠 이벤트

스크롤 휠은 정확히 세 필드를 사용한다.

```json
{"scanCode":65536,"action":"scroll","delta":-1}
```

```json
{"scanCode":65536,"action":"scroll","delta":1}
```

계약은 다음과 같다.

- `scanCode`는 정확히 `65536`이다.
- `action`은 정확히 `scroll`이다.
- `delta`는 정확히 정수 `-1` 또는 `1`이다.
- `-1`은 AASDK relative input의 왼쪽 한 단계, `1`은 오른쪽 한 단계다.
- 큰 delta, 0, 소수, 문자열, `click`, `press`, `release`는 허용하지 않는다.

### 4.3 body와 media type 경계

- body는 비어 있으면 안 된다.
- endpoint 전용 body 상한은 128 bytes다.
- UTF-8 이외의 인코딩을 사용하지 않는다.
- `Content-Type`이 JSON이 아니면 요청을 처리하지 않는다.
- JSON object 외의 top-level 값은 허용하지 않는다.

## 5. 지원 Android Auto scan code

`NONE=0`은 광고하거나 입력으로 받지 않는다. 현 버전의 allowlist는 다음과 같다.

| 이름 | 10진수 | 16진수 | 허용 action |
|---|---:|---:|---|
| `MICROPHONE_2` | 1 | `0x01` | `click` |
| `MENU` | 2 | `0x02` | `click` |
| `HOME` | 3 | `0x03` | `click` |
| `BACK` | 4 | `0x04` | `click` |
| `PHONE` | 5 | `0x05` | `click` |
| `CALL_END` | 6 | `0x06` | `click` |
| `UP` | 19 | `0x13` | `click` |
| `DOWN` | 20 | `0x14` | `click` |
| `LEFT` | 21 | `0x15` | `click` |
| `RIGHT` | 22 | `0x16` | `click` |
| `ENTER` | 23 | `0x17` | `click` |
| `MICROPHONE_1` | 84 | `0x54` | `click` |
| `TOGGLE_PLAY` | 85 | `0x55` | `click` |
| `NEXT` | 87 | `0x57` | `click` |
| `PREV` | 88 | `0x58` | `click` |
| `PLAY` | 126 | `0x7E` | `click` |
| `PAUSE` | 127 | `0x7F` | `click` |
| `SCROLL_WHEEL` | 65536 | `0x10000` | `scroll`과 `delta=-1/+1` |

이 값은 Android의 `android.view.KeyEvent` keyCode 표가 아니라 AASDK `ButtonCode` scan code다. 클라이언트는 플랫폼 키 코드를 이 표로 명시적으로 변환해야 하며, 알 수 없는 값을 그대로 전달하면 안 된다.

## 6. 인증과 권한

키 입력은 WebRTC DataChannel이 연결되어 있다는 사실만으로 승인되지 않는다. 앱은 각 RPC마다 다음을 모두 검사한다.

1. `x-browser-credential`이 현재 trust store에서 유효해야 한다.
2. credential의 owner key가 현재 DataChannel 세션의 owner key와 같아야 한다.
3. WebRTC session access mode가 `CONTROL`이어야 한다.
4. 저장된 기기 권한도 `CONTROL`이어야 한다.
5. route가 `POST /api/key` allowlist에 있어야 한다.

`READ_ONLY` 세션은 키 입력을 보낼 수 없다. 다른 기기의 credential을 복사해 현재 DataChannel에서 쓰는 경우 owner 불일치로 거부한다. 키 입력 endpoint는 Cloudflare signaling relay route에 등록하지 않으며 연결 이후의 로컬 WebRTC 제어 채널에서만 사용한다.

credential은 URL query, body, 로그, 오류 메시지에 복제하지 않는다. 클라이언트는 페이지 저장소 정책에 따라 credential을 보호하고, 교체·해지 후 이전 값을 재사용하지 않는다.

## 7. Android Auto 연결과 AASDK mapping

### 7.1 Service Discovery

Android 앱은 AASDK Service Discovery Response의 Input channel descriptor에 다음을 함께 광고한다.

- 현재 projection viewport와 일치하는 touch screen width와 height
- 5장의 지원 scan code 전체

클라이언트가 `/api/key`를 호출할 수 있는지와 무관하게 Android Auto에 광고하는 목록은 앱 빌드의 고정 allowlist다. 브라우저가 임의 scan code를 추가해 광고 목록을 바꿀 수 없다.

### 7.2 Input binding

Android Auto의 `BindingRequest.scan_codes`를 다음처럼 처리한다.

- 요청된 모든 scan code가 광고된 allowlist에 포함되어 있으면 `BindingResponse.status=OK`를 보낸다.
- 빈 scan code 목록도 touch-only binding으로서 성공시킨다.
- 하나라도 미지원 값 또는 `NONE=0`이 있으면 binding 전체를 실패시킨다.
- packed 또는 legacy unpacked field 1을 모두 읽되, 요청 scan code는 최대 64개까지만 받는다.
- malformed varint, 음수로 해석되는 값 또는 32-bit scan code 범위 밖 값은 binding을 준비 상태로 만들지 않는다.
- duplicate binding 또는 Input channel open 이전 binding은 protocol error다.
- 키와 touch는 성공한 Input binding 이후에만 AASDK wire로 전송한다.

### 7.3 InputEventIndication

버튼 `click` 큐 항목 하나는 AASDK `InputEventIndication` 두 건으로 확장한다. 두 indication은 동일 scan code를 사용하며 단일 input sender가 다른 touch, key 또는 scroll을 사이에 끼우지 않고 다음 순서로 보낸다.

| 순서 | AASDK `button_event.button_events`의 단일 항목 |
|---:|---|
| 1 | `scan_code=<요청 scanCode>`, `is_pressed=true`, `meta=0`, `long_press=false` |
| 2 | `scan_code=<요청 scanCode>`, `is_pressed=false`, `meta=0`, `long_press=false` |

이 인접성은 입력 큐와 sender 수준의 ordering 계약이다. 두 indication이 별도 transport write라는 사실까지 원자적 네트워크 transaction으로 바꾸지는 않는다. 중간 transport 단절 경계는 8.4를 따른다.

스크롤 이벤트는 AASDK `InputEventIndication.relative_input_event.relative_input_events`의 단일 항목으로 변환한다.

| WebRTC 값 | AASDK 값 |
|---|---|
| `scanCode=65536` | `scan_code=SCROLL_WHEEL` |
| `delta=-1` | `delta=-1` |
| `delta=1` | `delta=1` |

각 indication의 timestamp는 앱 runtime에서 생성한다. WebRTC peer가 timestamp를 정하거나 과거 시간을 주입할 수 없다.

현재 구현은 기존 touch wire 계약과의 호환성을 위해 `InputEventIndication.timestamp`에 **nanoseconds 단위 값을 그대로 유지**한다. 보존된 공식 OpenAuto/AASDK reference 구현이 microseconds 단위를 사용하는 것과 차이가 있다는 사실은 알려진 호환성 관찰 항목이다. 문서만 보고 microseconds로 바꾸거나 단위를 혼용하지 않는다. 실기기 Android Auto에서 버튼·스크롤·touch timestamp 수용을 검증한 뒤에만, 필요하면 별도 호환성 변경으로 단위를 교정한다.

## 8. 상태, 순서와 복구

### 8.1 상태 기계

| 상태 | 조건 | `/api/key` 처리 |
|---|---|---|
| `NO_WEBRTC_CONTROL` | control DataChannel 없음 | 호출 불가 |
| `UNAUTHORIZED` | credential 또는 owner 불일치 | `401` |
| `READ_ONLY` | 세션 또는 기기 권한이 읽기 전용 | `403` |
| `AA_DISCONNECTED` | Android Auto 미연결 | `409 android_auto_not_connected` |
| `INPUT_NOT_READY` | AA 연결됨, Input channel/binding 미완료 | `409 input_not_ready` |
| `READY` | AA 연결 및 Input binding 완료 | 검증 뒤 queue admission |
| `QUEUE_REJECTED` | runtime이 입력을 인수하지 못함 | `409 queue_rejected` |
| `ACCEPTED` | runtime queue가 인수함 | `202 accepted=true` |

### 8.2 순서

- control DataChannel은 reliable ordered로 만든다.
- endpoint는 요청 한 건에 논리적인 click 또는 scroll 한 건만 받는다.
- AASDK runtime은 touch와 key를 같은 입력 전송 순서에 놓아 dequeue 순서를 wire 순서로 보존해야 한다.
- 하나의 click 큐 항목에서 생성된 `PRESS`와 `RELEASE` indication 사이에는 다른 입력을 넣지 않는다.
- 서로 다른 request를 동시에 열면 ordered DataChannel의 수신 순서는 보존되지만 응답 처리와 애플리케이션 작업 완료 순서를 추측하지 말아야 한다.

### 8.3 requestId와 replay

`requestId`는 RPC 응답 대응과 현재 처리 중인 중복 요청 방지용이다. 영구 idempotency key가 아니다.

- 같은 `requestId`가 아직 처리 중이면 duplicate request로 거부된다.
- 처리가 끝난 `requestId`를 다시 보내지 않는다.
- 서버는 완료된 모든 request의 무기한 replay cache를 유지하지 않는다.
- response 유실 시 원 이벤트가 queue에 들어갔는지는 알 수 없다.
- `click`을 timeout이나 response 유실만 보고 자동 재전송하면 동일 동작이 중복될 수 있으므로 재전송하지 않는다.
- `scroll`도 자동 재전송하면 한 단계가 중복될 수 있으므로 사용자에게서 새 입력이 발생한 경우에만 새 requestId로 보낸다.

### 8.4 단일 click 큐 항목의 경계와 연결 종료

v1에는 외부에 노출된 held-key 상태가 없다. 클라이언트의 click 한 건은 서버 queue 항목 하나이고, 정상 전송에서는 AASDK `PRESS`와 `RELEASE`가 인접한다. 다만 두 indication은 서로 다른 transport message이므로 물리 전송 자체가 하나의 transaction은 아니다.

- `PRESS`와 `RELEASE` 사이에서 AASDK transport가 끊기면 현재 Android Auto session 자체를 종료한다.
- 해당 session의 이전 input queue는 닫아 폐기하며 새 session으로 이월하지 않는다.
- 새 transport, 채널 open과 Input binding이 완료되기 전에는 새 입력을 받지 않는다.
- 끊어진 session의 `RELEASE`를 새 session에서 합성하거나 보정 전송하지 않는다.
- 클라이언트는 outstanding release 집합을 관리하지 않으며 별도 `release` 요청도 보내지 않는다.
- 서버의 queue admission `202`는 Android Auto 실행 또는 pair 전체 전송 완료 확인이 아니다.

이 경계는 단절된 AA transport 안에 반쪽 click을 남긴 채 같은 session을 계속 사용하는 상황을 피한다. 새 session에서 과거 click을 다시 실행하지 않는 것이 복구 원칙이다.

### 8.5 Android Auto 재연결

- AA가 끊기면 대기 중인 이전 세션 입력을 새 AA 세션으로 이월하지 않는다.
- AA가 다시 연결되더라도 새 Input channel open과 binding 성공 전에는 `input_not_ready`다.
- 클라이언트는 `409`에서 tight loop를 돌지 않는다. 상태 조회 또는 제한된 backoff로 준비 상태를 확인한다.
- 재연결 뒤 과거 `click`이나 `scroll`을 replay하지 않는다. release 보정 단계는 존재하지 않는다.

## 9. 오류 계약

endpoint 응답 body는 별도 표기가 없는 한 UTF-8 JSON이다.

| HTTP 상태 | body 예 | 의미 | 클라이언트 처리 |
|---:|---|---|---|
| `202` | `{"accepted":true}` | 입력 큐가 인수함 | 다음 상태로 진행 |
| `400` | `{"error":"invalid_relay_request"}` | 잘못된 RPC-shaped request | 구현 오류 수정 |
| `401` | `{"error":"invalid_browser_credential"}` | credential 무효 또는 owner 불일치 | 재페어링 또는 세션 폐기 |
| `403` | `{"error":"read_only_session"}` 또는 `{"accepted":false,"reason":"read_only_session"}` | WebRTC session gate 또는 재인증된 device gate에서 제어 권한 없음 | 입력 중단 |
| `403` | `{"error":"control_route_forbidden"}` | route allowlist 밖 | 구현 오류 수정 |
| `404` | `Not found` | Android endpoint route 없음 | 서버 capability/version 재확인 |
| `409` | `{"accepted":false,"reason":"android_auto_not_connected"}` | AA 미연결 | AA 연결 후 새 입력 |
| `409` | `{"accepted":false,"reason":"input_not_ready"}` | Input binding 미완료 | 제한된 backoff 뒤 새 입력 |
| `409` | `{"accepted":false,"reason":"queue_rejected"}` | 입력 큐가 인수하지 못함 | 상태 재확인, 무조건 replay 금지 |
| `413` | `{"error":"key_payload_too_large"}` | endpoint body 128 bytes 초과 | body 축소, 재시도 전 수정 |
| `415` | `{"error":"unsupported_key_content_type"}` | 허용된 JSON media type 아님 | header 수정 |
| `422` | `{"error":"invalid_key_input"}` | JSON 또는 scanCode/action 조합 오류 | payload 수정 |
| `429` | `{"error":"cloud_relay_rate_limited"}` | RPC token bucket 초과 | 지수 backoff |
| `429` | `{"error":"cloud_relay_busy"}` | 동시 처리 중 16개 초과 | in-flight 축소 |
| `500` | `{"error":"relay_request_failed"}` | dispatcher 내부 오류 | 상태 재확인, 입력 자동 replay 금지 |

알 수 없는 오류 값은 보수적으로 요청 실패로 처리한다. `click` 또는 `scroll`의 성공 여부가 불확실하면 자동 재전송하지 않는다.

## 10. 부하와 자원 경계

- 한 RPC에는 논리적인 `click` 또는 `scroll` 한 건만 들어간다.
- `/api/key` body는 128 bytes 이하로 제한한다.
- control envelope 전체는 192 KiB 이하이지만 endpoint의 더 작은 128-byte 경계를 먼저 적용한다.
- control codec은 초당 64 request, burst 96, in-flight 16으로 제한한다.
- 키 endpoint 전용 UI 자동 반복은 제공하지 않는다.
- v1은 repeat, long press, held-key 상태와 분리된 press/release를 모두 금지한다. 이 의미가 필요하면 기존 `click`을 반복 전송하지 말고 별도 versioned protocol을 정의해야 한다.
- malformed 요청은 AASDK input queue에 들어가지 않아야 한다.
- AASDK input queue 용량은 touch와 key를 합쳐 64개다.
- queue가 찬 경우 기존 입력을 밀어내거나 임의 병합하지 않고 새 요청을 `queue_rejected`로 돌려준다.

## 11. 보안 요구사항

### 11.1 신뢰 경계

- WebRTC DTLS/SCTP는 전송 보호 계층이지만 애플리케이션 권한을 대신하지 않는다.
- 각 요청의 credential과 DataChannel owner를 다시 결합 검증한다.
- `CONTROL` 권한은 페어링 trust store가 정하며 payload가 요청할 수 없다.
- scan code allowlist는 앱 코드가 소유한다.
- Cloud signaling relay에는 `/api/key`를 노출하지 않는다.

### 11.2 입력 검증

- JSON 필드 집합과 타입을 엄격히 검사한다.
- field name과 action의 대소문자를 구분한다.
- duplicate JSON key를 허용하지 않는다.
- 정수 범위 밖 값, `NaN`, infinity, 지수 표기와 소수를 허용하지 않는다.
- `NONE=0`, 음수 scan code, 미지원 양수와 임의 Android keyCode를 거부한다.
- AASDK binding에서도 peer 요청 값을 광고 allowlist와 다시 대조한다.

### 11.3 로그와 개인정보

- credential과 원문 request body를 운영 로그에 남기지 않는다.
- 필요한 진단은 requestId의 비밀이 아닌 축약값, 결과 상태, 오류 코드와 scan code 이름 수준으로 제한한다.
- 키 입력을 사용자의 텍스트 입력으로 확대 수집하지 않는다.
- 현재 프로토콜에는 텍스트, 연락처, 전화번호 또는 음성 payload가 없다.

### 11.4 위협과 대응

| 위협 | 대응 |
|---|---|
| 다른 세션 credential 복사 | credential owner와 DataChannel owner 일치 검사 |
| 읽기 전용 뷰어의 입력 | session 및 device `CONTROL` 이중 검사 |
| 임의 AA 명령 주입 | 고정 scan code allowlist와 action 조합 검사 |
| 대형 JSON/파서 DoS | 192 KiB envelope, 128-byte endpoint body, strict parser |
| 이벤트 flood | token bucket, in-flight 한도, bounded input queue |
| timeout replay | 자동 replay 금지, requestId 규칙 |
| click pair 중 transport 단절 | 현재 AA session 종료, 이전 queue 폐기, 새 binding 전 입력 거부, replay 금지 |
| 이전 AA 세션 입력 누출 | 세션 종료 시 queue 폐기, 새 binding 전 입력 거부 |

## 12. 클라이언트 구현 절차

1. `/api/webrtc/capabilities`에서 `controlDataChannelV1=true`를 확인한다.
2. 기존 페어링과 WebRTC signaling 계약으로 `CONTROL` 세션을 만든다.
3. `navonweb-control-v1`을 reliable ordered로 만든다.
4. channel `open` 이후에만 RPC를 보낸다.
5. UI 또는 물리 장치 입력을 5장의 명시적 표로 변환한다.
6. 이벤트 JSON을 공백 없이 UTF-8로 만들고 표준 Base64로 인코딩한다.
7. 매 요청에 새 16~64자 requestId를 만든다.
8. `x-browser-credential`과 JSON content type을 넣는다.
9. response requestId, status, contentType과 body를 검증한다.
10. response 유실 또는 timeout 시 해당 click/scroll을 자동 replay하지 않는다.
11. channel 종료·AA 재연결에서 8장의 새 session·binding 복구 규칙을 적용한다.

## 13. 검증 명세

### 13.1 codec과 route

- [ ] `navonweb-control-v1`, ordered text message만 수락한다.
- [ ] 완전한 request envelope 예제를 decode한다.
- [ ] binary, invalid UTF-8, extra envelope field, 짧은 requestId를 거부한다.
- [ ] `POST /api/key`는 control route allowlist를 통과한다.
- [ ] 같은 경로의 `GET`과 Cloud signaling relay 호출은 route 단계에서 통과하지 않는다.
- [ ] `/api/key`에 query를 붙인 요청은 입력으로 처리하지 않고 endpoint에서 `422`로 거부한다.
- [ ] 응답이 같은 requestId의 `rpc_response`로 돌아온다.

### 13.2 인증과 권한

- [ ] 유효한 같은-owner `CONTROL` credential만 `202`에 도달한다.
- [ ] credential 없음·오류·해지·owner 불일치는 `401`이다.
- [ ] `READ_ONLY` session과 device는 `403`이다.
- [ ] credential 원문이 로그와 오류 body에 없다.

### 13.3 JSON 경계

- [ ] 지원 버튼의 `click`만 수락한다.
- [ ] `SCROLL_WHEEL`의 `delta=-1`과 `delta=1`을 각각 수락한다.
- [ ] `scanCode=0`, 미지원 값, 문자열과 소수를 `422`로 거부한다.
- [ ] unknown·duplicate·missing field를 `422`로 거부한다.
- [ ] `CLICK`, 분리된 `press`/`release`, button+delta, wheel+click을 `422`로 거부한다.
- [ ] 128 bytes 초과 body는 `413`이다.
- [ ] JSON이 아닌 media type은 `415`다.

### 13.4 Android Auto/AASDK

- [ ] Service Discovery Input descriptor가 touch geometry와 18개 scan code를 광고한다.
- [ ] 빈 binding과 광고 목록의 packed/unpacked subset binding은 성공한다.
- [ ] scan code 64개 경계는 수락하고 65개는 거부한다.
- [ ] 미지원 scan code가 하나라도 있는 binding은 실패한다.
- [ ] binding 전 key와 touch는 wire로 나가지 않는다.
- [ ] click 큐 항목 하나가 동일 scan code의 `is_pressed=true` 뒤 `false`를 다른 입력 없이 인접 전송하며 양쪽 모두 `meta=0`, `long_press=false`다.
- [ ] wheel delta가 relative input에 정확히 보존된다.
- [ ] touch와 key가 단일 input queue 순서를 보존한다.
- [ ] touch·button·scroll timestamp가 기존 nanoseconds wire 단위를 유지한다.
- [ ] 공식 reference의 microseconds 단위와 차이를 실기기 호환성 관찰 항목으로 기록하고, 이번 버전에서 암묵적으로 변환하지 않는다.

### 13.5 상태와 복구

- [ ] AA 미연결은 `android_auto_not_connected`다.
- [ ] AA 연결 후 binding 전은 `input_not_ready`다.
- [ ] queue admission 실패는 `queue_rejected`다.
- [ ] `202`가 Android Auto 처리 완료로 표현되지 않는다.
- [ ] DataChannel 종료 시 이전 세션 queue가 새 AA 세션으로 넘어가지 않는다.
- [ ] click의 `PRESS`와 `RELEASE` 사이 transport 단절 시 현재 AA session이 종료되고 이전 queue가 폐기된다.
- [ ] 재연결 뒤 release 보정 요청을 만들지 않는다.
- [ ] timeout 또는 response 유실 뒤 click과 scroll을 자동 replay하지 않는다.

### 13.6 실기기 시나리오

- [ ] `BACK` click 한 건이 Android Auto에서 한 번만 동작한다.
- [ ] `HOME` click 한 건이 Android Auto home 전환을 한 번만 만든다.
- [ ] `UP/DOWN/LEFT/RIGHT/ENTER`가 focus 이동과 선택을 정확히 만든다.
- [ ] `NEXT/PREV/PLAY/PAUSE/TOGGLE_PLAY`가 지원 앱에서 중복 없이 동작한다.
- [ ] wheel 왼쪽·오른쪽 한 단계가 각각 한 단계만 이동한다.
- [ ] `READ_ONLY` 브라우저가 영상은 유지하면서 키 입력은 거부된다.
- [ ] WebRTC reconnect와 AA reconnect 후 과거 키가 재실행되지 않는다.

## 14. 호환성과 변경 규칙

- DataChannel label은 그대로 `navonweb-control-v1`을 사용한다.
- 현행 브라우저 UI가 `/api/key`를 호출하지 않아도 기존 video, touch, audio와 signaling 계약은 바뀌지 않는다.
- 지원 scan code를 추가할 때는 allowlist, Service Discovery 광고, binding 검증, JSON validator, 이 문서와 단위·실기기 테스트를 한 변경으로 갱신한다.
- action 또는 body 구조를 바꾸거나 batch, repeat, long press, modifier를 추가할 때는 기존 strict parser와 충돌하므로 새 versioned endpoint 또는 명시적인 capability가 필요하다.
- 알 수 없는 field를 무시하는 방식으로 확장하지 않는다. 구형 구현은 새 payload를 명확히 `422`로 거부해야 한다.

## 15. 구현 추적 지점

이 계약을 바꾸는 구현 지점은 다음과 같다.

- `app/src/main/java/com/pebble/tecomheadunit/browser/webrtc/WebRtcControlDataChannelCodec.kt`
- `app/src/main/java/com/pebble/tecomheadunit/browser/webrtc/WebRtcProjectionController.kt`
- `app/src/main/java/com/pebble/tecomheadunit/browser/BrowserProbeServer.kt`
- `app/src/main/java/com/pebble/tecomheadunit/openauto/AasdkProjectionRuntime.kt`
- `app/src/main/java/com/pebble/tecomheadunit/openauto/protocol/AasdkOpenAutoProtocol.kt`
- 대응 단위 테스트와 이 문서

## 16. 적용 버전

이 기능을 처음 포함하는 Android 앱 버전은 `0.1.14-p0` (`versionCode 24`)다.

- 브라우저 asset과 브라우저 UI는 이번 변경에서 수정하지 않는다.
- 별도의 프로토콜 클라이언트 또는 검증 도구에서 `/api/key`를 호출할 수 있다.
