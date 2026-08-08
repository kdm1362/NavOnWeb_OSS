# Projection profile and entitlement boundary

현재 결제 또는 원격 entitlement 백엔드는 연결돼 있지 않다. 따라서 일반 debug와 모든 release
빌드는 `FREE`로 시작하며 `800x480`만 사용할 수 있다. 프로필은 휴대전화 앱에서만 선택하고,
브라우저 자격증명은 영상·터치·signaling API 인증에만 사용되며 PREMIUM 권한을 만들지 않는다.

## Closed profiles

| ID | Entitlement | AA source | AA FPS | WebRTC FPS ceiling | WebRTC bitrate |
|---|---|---:|---:|---:|---:|
| `free-800x480` | FREE | 800x480 (5:3) | 60 | 30 | 0.5 / 2.5 / 4 Mbps |
| `premium-720p` | PREMIUM | 1280x720 (16:9) | 60 | 30 | 1 / 4 / 8 Mbps |
| `premium-1080p` | PREMIUM | 1920x1080 (16:9) | 60 | 30 | 2 / 8 / 14 Mbps |

임의 폭·높이·FPS·DPI는 허용하지 않는다. 선택된 프로필은 AA ServiceDiscovery의 video resolution과
input TouchConfig, 로컬 touch mapper, MediaCodec decoder 및 WebRTC source에 함께 적용된다. WebRTC
송출 codec은 휴대전화 앱의 `AUTO`/`H264`/`VP8`/`VP9`/`AV1`에서 선택한다. `AUTO`는 hardware
H.264 → VP8 → VP9 → AV1 순이며 software VP9/AV1은 명시 선택만 허용한다. H.264와 VP8은
baseline fallback 후보로 유지한다. JPEG는 앱의
고정 800x480 Surface에서 복사하는 최대 5fps 디버그 fallback으로만 유지한다. 720p/1080p의 실제
고해상도 송출은 GPU texture 기반 WebRTC 경로에서만 제공한다. 웹 화면에는 프로필·codec·FPS 또는
진단 설정을 표시하지 않고 페어링, AA 화면·터치, 영상 바깥 전체화면 버튼만 제공한다.

## Activation boundary

휴대전화 앱은 서비스 Binder를 통해 현재 entitlement와 닫힌 프로필 목록을 읽고 선택한 프로필을
저장한다. FREE 상태의 PREMIUM 선택은 거부된다. 인증된 `GET /api/projection/profile`은 활성 geometry를
읽는 호환 경로로만 남아 있으며, 웹의 `POST /api/projection/profile`은 `HTTP 405`와
`profile_changes_app_only`로 차단한다. 최소 웹 client는 프로필 제어를 노출하지 않는다.

AA의 영상·입력 크기는 ServiceDiscovery에서 협상되므로 기존 AA runtime 안에서 크기만 hot-swap하지
않는다. 프로필을 선택하면 앱은 가능한 경우 새 WebRTC controller/source와 decoder·touch geometry를 준비한
뒤 기존 AA runtime만 닫고 ServiceDiscovery부터 자동 재연결한다. HTTP 서버, 8자리 코드 gate,
저장된 브라우저 연결 키와 foreground service는 유지되며 브라우저는 같은 주소와 페어링 상태에서
활성 프로필의 새 content rect와 WebRTC 세션을 자동으로 받는다.

앱 Binder 요청은 적용 상태를 `APPLYING`으로 알리고 완료되면 `ACTIVE`로 확정한다. 읽기 API의
`requiresProjectionServiceRestart`는 `false`이며 전체 HTTP/foreground 서비스의 수동 재시작은
필요하지 않다. WebRTC encoder가 없으면 AA 프로필은 적용하고 앱 Surface/JPEG fallback을 사용한다. AA
자체 구성이 준비되지 않으면 요청을 active 프로필로 되돌리고 기존 서버·페어링을 유지한다.

## Dynamic browser aspect ratio

The browser-to-Service-Discovery density contract, bounds, hysteresis, and reference cases are
documented in [`PROJECTION_DENSITY.md`](PROJECTION_DENSITY.md).

### Expanded-view pre-negotiation (current behavior)

The standard authenticated view continuously reports the *anticipated expanded content area*, not
the temporary inline `pad` rectangle. Native Fullscreen uses the current display's oriented
`screen.width`/`screen.height` CSS pixels and subtracts the same safe-area/padding values used by
the fullscreen CSS. Browsers without the Fullscreen API use the current layout viewport for the
theater fallback. The inline `pad` follows this anticipated aspect ratio before entry.

The click, pinch, native Fullscreen, and theater paths also flush that same value before changing
presentation. Consequently the resize after entry has the same request key and resolves to the
same 8-pixel-quantized layout. It does not schedule a second Android Auto viewport reconnect.
The entry target is locked for the whole expanded session. This also covers a browser that exposes
`requestFullscreen()` but rejects it and falls back to theater mode: the fallback cannot replace
the screen-shaped request with a layout-viewport request. On exit the lock is released before the
standard prediction is synchronized. A fullscreen round trip therefore no longer alternates
between inline-profile, native-fullscreen, and fallback-theater AA sessions. Historical bench
notes below that describe one renegotiation on every fullscreen entry/exit document the superseded
behavior.

PREMIUM 브라우저는 `ResizeObserver`, `visualViewport`와 창 크기 이벤트로 실제 AA 표시 가능 영역을
측정한다. 인증된
`POST /api/projection/viewport?width=<css px>&height=<css px>&devicePixelRatio=<bounded DPR>`는
프로필 해상도를 임의 값으로 바꾸지 않고, Android Auto가 정의한 `margin_width`와
`margin_height` 총합으로 고정
`800x480`·`1280x720`·`1920x1080` 프레임 안의 최대 중앙 content rect를 협상한다. 여백은 8px
단위로 양자화하고 16px 미만 진동을 무시한다. content 폭은 최소 216px, 높이는 최소 240px이며
DPR은 0.5~8 범위만 허용한다. 이 경계보다 과도한 종횡비는 fail-closed로 거부한다.

브라우저는 850ms 안정화 후 마지막 크기만 전송하고 서비스도 850ms 동안 마지막 요청을 합친다.
같은 대기 크기를 500ms 상태 폴링이 다시 보내더라도 안정화 타이머를 재시작하지 않는다. 적용 시
AA runtime만 ServiceDiscovery부터 재연결하며 WebRTC controller, HTTP 서버, 저장된 브라우저 연결
키는 유지한다. 영상은 활성 여백을 잘라 content rect만 표시하고, 정규화된 터치는 Android Auto가
광고한 content viewport의 `0..width`, `0..height` 좌표로 직접 매핑한다. 중앙 margin offset은
Service Discovery 레이아웃에만 속하므로 입력 좌표에 다시 더하지 않는다. 빠른 연속 변경의 오래된
적용 완료는 현재 requested layout과 완전히 같을 때만 active로 확정한다.

일반화면의 `pad`는 활성 프로필의 원본 비율(800x480은 5:3, 720p/1080p는 16:9)을 유지하고
세로 여유 공간이 있으면 상단에 놓인다. 이 모드의 실제 `pad` 크기를 보고하므로 이전 전체화면에서
협상한 동적 margin도 원본 비율로 되돌아간다. native fullscreen 또는 fallback theater mode에서만
버튼·safe-area를 제외한 실제 가용 영역을 보고해 동적 비율을 적용한다. AA 화면의 두 손가락 벌리기는
명시적 전체화면 진입, 오므리기는 일반화면 복귀로 처리한다. 둘째 손가락이 닿는 즉시 이미 전달한
첫 AA 포인터를 `CANCEL`하고 모든 손가락이 떨어질 때까지 AA 입력으로 재사용하지 않는다.

AA 영상의 첫 프레임과 input channel binding 완료는 순서가 다를 수 있다. 상태 API의 `touchReady`가
성공한 `INPUT_BINDING_READY`를 확인하기 전에는 브라우저 입력을 보내지 않으며, viewport 재연결 직후
발생하던 영상 준비/입력 미준비 경합을 사용자 터치 실패로 노출하지 않는다.

FREE 또는 release의 결제 미검증 상태는 viewport POST를 `403`으로 거부하고 고정 프로필 종횡비를
유지한다. 개발용 `tesla-driving`/`tesla-cycle` query와 상태 계측 attribute는 debug 빌드에서만
주입되며 release asset의 gate는 항상 `false`다. 로컬 차량 네트워크는 공용 인터넷이 없어도 사용할
수 있으므로 Chromium의 `offline` 신호만으로 중단하지 않고 same-origin `/health` 실패를 확인한 뒤
미디어를 정리한다.

## Debug-only physical bench

실기 검증용 PREMIUM 권한은 아래 빌드 속성으로 만든 debug APK에만 포함할 수 있다.

```powershell
.\tools\build.ps1 -GradleArguments '-PenablePremiumProjectionBench=true'
```

속성 기본값은 `false`다. release BuildConfig 값은 이 속성과 무관하게 항상 `false`이고 release factory는
항상 fail-closed FREE provider를 반환한다. 이 벤치 gate는 판매용 결제 검증을 대신하지 않는다.

## Physical-device result (2026-08-02)

- JPEG fallback은 브라우저에서 `800x480`, 약 `4.8fps`로 측정됐다.
- 당시 H.264 WebRTC는 움직임이 있는 구간에서 free 800x480 약 `29.3fps`, premium 1280x720 약
  `28.2~29.6fps`였다. 정지한 지도 화면에서는 전화기가 변경 프레임을 줄여 수신·표시 FPS가 함께
  약 `6~14fps`로 낮아졌다. 이 값은 당시 시험 계측이며 현재 웹에는 FPS 배지가 없다. 이후 진단은
  휴대전화 앱에서 수집한다.
- 720p 전체 화면의 실제 content rect는 `16:9`(`1.7778`)였고 좌우 letterbox 밖에서 시작한 터치는
  거부되도록 동일 rect를 좌표 변환에 사용한다.
- 1920x1080 H.264는 실제 `videoWidth=1920`, `videoHeight=1080`, 약 `27.3~27.7fps`로 동작했지만
  짧은 측정 구간에 hardware encoder queue-full drop 6회가 기록됐다. 따라서 현재 벤치의 권장
  PREMIUM 프로필은 720p이며 1080p는 선택 가능한 고부하 프로필로 둔다.
- AA 프로필에는 프로토콜 선택지인 60fps를 광고하지만 SM-S938N의 실제 영상 cadence는 안정 구간에서
  약 30fps였다. 광고값과 실제 계측값은 구분하며 웹 화면에는 어느 값도 디버그 정보로 표시하지 않는다.
- 새 앱을 SM-S938N에 설치한 뒤 휴대전화 UI에서 1280x720→800x480→1280x720을 변경했다. 같은 웹 URL과
  저장된 페어링을 유지한 채 브라우저 `<video>`가 각각 실제 800x480과 1280x720, `readyState=4`로
  자동 복구됐다.
- 휴대전화 UI에서 `VP8`을 선택하자 활성 WebRTC만 재협상되어 `codec=VP8`, `CONNECTED`가 확인됐고,
  브라우저 영상도 800x480으로 복구됐다. H.264 비활성화 회귀 시험에서는 AUTO VP8도 별도로 확인했다.
- 현재 재활성화 정책에서 SM-S938N의 명시 H.264와 AUTO가 모두 `codec=H264`, ICE/peer `CONNECTED`로
  재협상됐고 브라우저 800x480 `readyState=4` 연속 재생을 확인했다. Android Auto 입력 AVC decoder와
  프로필 geometry는 유지됐다. VP9/AV1 및 720p/1080p 조합의 최신 실기 성능은 아직 재검증하지 않았다.
- SM-S938N 자체 호스팅 서버 `192.168.31.231:8787`과 LAN 브라우저에서 debug `tesla-cycle`을
  12초 주기로 시험했다. 브라우저 가용 영역 `1414x1147`과 `941x1147`에 대해 800x480 frame의
  중앙 content가 각각 `592x480 + (104,0)`과 `392x480 + (204,0)`으로 적용됐고 AA 로그의 총
  `marginWidth`는 `208 ↔ 408`, `marginHeight=0`으로 일치했다.
- 각 전환은 `HTTP 202`, `VIEWPORT_APPLIED ... WEBRTC_SESSION_PRESERVED`, 새 Android Auto 영상
  수신 순으로 진행됐다. 브라우저 `<video>.currentTime`은 전환 사이 `10.998 → 45.658`초로 증가했고
  같은 WebRTC session이 여러 AA viewport 재협상 동안 유지됐다. 이는 개발 폭 시뮬레이터와 해당
  LAN 벤치 결과이며 실제 Tesla 브라우저 실차 검증은 아직 남아 있다.

### 최종 동적 화면·입력 회귀 (2026-08-02)

- 일반화면은 브라우저의 남는 세로 공간과 무관하게 원본 800x480 프로필의 5:3 surface를 상단에 둔다.
  SM-S938N LAN 벤치에서 pad `941x564.59`, content `938.33x563`, 상단 좌표 `32/33`을 확인했다.
- 전체화면에서만 실제 가용 영역 `2552x1324.81`을 전달하고 일반화면 복귀 시 5:3 surface로 돌아왔다.
- 연결 중 전체화면 왕복마다 viewport 요청과 적용이 한 번씩만 발생했다. 이전 status poll이 현재 AA
  layout과 브라우저 측정을 비교해 2초마다 두 크기를 교대로 다시 요청하던 경로는 제거했다.
- 인증된 페이지마다 viewport client ID를 보내며 한 credential에는 한 controller lease만 허용한다.
  오래된 캐시 페이지는 ID가 없으면 거부되고, 다른 탭은 현재 controller lease가 만료되기 전까지
  viewport를 변경할 수 없다.
- 재협상 뒤 TMAP 아이콘 클릭이 `TOUCH_SENT DOWN/UP`과 실제 AA 화면 전환으로 이어졌다. 영상 연결과
  입력 binding 준비를 분리하고, timeout·MOVE coalescing·CANCEL 복구를 적용한 뒤 입력 무반응은
  재현되지 않았다.
- 두 손가락 확대/축소는 각각 전체화면/일반화면과 같은 상태 함수를 사용한다. desktop in-app browser는
  동시 2포인터 주입을 지원하지 않으므로 실제 touch hardware의 핀치는 실차 검증 항목으로 남긴다.

상세 증거는 [최종 viewport·touch 검증](FINAL_VIEWPORT_TOUCH_VERIFICATION_2026-08-02.md)에 정리했다.

실제 판매판에는 아직 구매 검증이 없다. Play Billing 구매 토큰을 개발자 서버에서 검증하고 만료·취소를
fail-closed로 처리하는 entitlement provider가 연결되기 전까지 release는 FREE만 반환한다.
