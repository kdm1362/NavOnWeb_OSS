# OpenAuto Android 이식 상태

## 원본과 Android 대응 관계

| OpenAuto 원본 | Android P0 대응 | 상태 |
|---|---|---|
| `App` + TCP endpoint | `ProjectionService` + Kotlin AASDK transport | VERSION·TLS·7채널 SDP·channel open·AV setup/start 실기 검증 완료 |
| `IVideoOutput::write()` | `OpenAutoVideoSink` → `MediaCodecVideoSink` → `SurfaceTextureHelper` → WebRTC `VideoSource` + 앱 `EglRenderer`; PixelCopy JPEG fallback | 동일 GPU frame fan-out, `AUTO` H.264와 호스트 브라우저 800×480 연속 frame 직접 LAN 실기 검증 완료 |
| `IInputDevice`/`InputService` | 브라우저 pointer → `TouchMapper` → `OpenAutoInputSink` → AASDK input | 단일 포인터 DOWN/MOVE/UP/CANCEL 구현; 브라우저 클릭으로 실제 화면 전환 검증 완료 |
| Qt UI | 프로필·codec·진단용 Compose UI + 페어링·AA 화면·전체화면만 제공하는 최소 HTML client | 완료 |
| Qt/RtAudio output | `OpenAutoPcmSink` → 권한별 PCM16 processor → 인증된 HTTP stream → 브라우저 Web Audio; 향후 휴대전화 AudioTrack | 브라우저 출력 구현·자동 테스트 완료, 휴대전화 로컬 출력 미구현 |
| Qt/OMX video | Android `MediaCodec` Surface decoder | 에뮬레이터 `c2.goldfish.h264.decoder` 실기 검증 완료 |
| libusb hotplug | Android USB Host + AOA v1/v2 + bulk IN/OUT | 기반 계층 완료; 서비스 런타임은 현재 TCP 검증 경로 |
| `SensorService` | `SafetyGate`의 UNKNOWN/MOVING 거부 + 시간/대략적 위치 기반 `NIGHT_DATA` | 구현·자동 테스트 완료, 센서 채널·야간 fallback 실기 검증 완료 |

## 현재 구현된 프로젝션 기반

- 고정 기준인 AASDK `046b3b381595509d0939fa84b14a90978f46ff63`와 OpenAuto `aa90412bf93b5a5078495ea85ac9270c6297d369`의 bootstrap 순서에 맞춰 control-channel VERSION 요청/응답과 big-endian 프레임 헤더를 Kotlin으로 구현했습니다.
- VERSION 수락 뒤 head unit이 TLS 1.2 client로 동작합니다. TLS record는 평문 control message `SSL_HANDSHAKE(0x0003)`에 실어 교환하고, 완료 뒤 정확한 `AuthComplete(OK)` wire `00 03 00 04 00 04 08 00`을 전송합니다.
- 평문은 최대 `0x4000` 바이트로 먼저 분할하고 TLS가 각 조각을 암호화한 뒤 최대 `0xFFFF` 바이트 wire payload로 감쌉니다. Kotlin runtime은 ServiceDiscovery 응답, channel open, AV setup/start, ping/pong과 H.264 media 수신까지 처리합니다.
- 입력 채널 open과 binding 응답이 완료된 뒤에만 64개 FIFO 큐를 활성화합니다. 최신 Android Auto의 proto2 입력 스키마에 맞춰 `pointer_id=0`, `action_index=0`, `DOWN=0`도 명시적으로 직렬화하고 monotonic nanosecond timestamp를 사용합니다.
- 센서 채널이 NIGHT_DATA를 시작하면 권한이 허용된 대략적 위치의 일출·일몰과 현재 시각으로 첫 값을 전송하고 60초마다 재평가합니다. 위치가 없거나 사용할 수 없으면 기기 UI night hint, 그마저 없으면 fail-dark 순서로 처리합니다. 좌표는 process memory에서 약 1.1 km 단위로만 보존하고 브라우저와 UI에는 전달하지 않습니다.
- 채널 4 MEDIA(48 kHz stereo), 5 GUIDANCE와 6 SYSTEM(16 kHz mono)의 PCM16LE 상태기계를 구현했습니다. setup/start/stop/focus를 추적하고 sink가 PCM frame을 수용한 뒤에만 media ACK를 전송합니다. 무료 권한은 서버에서 mono 16 kHz로 강제 변환하고 유료 권한은 원본 형식을 유지합니다.
- 브라우저 오디오는 기존 연결 키로 인증된 `/api/audio/media|speech|system` chunked PCM과 Web Audio를 사용합니다. bounded fan-out은 느린 client의 오래된 chunk를 버려 AASDK 수신을 막지 않습니다. 유휴 heartbeat와 10초 write-progress watchdog이 stale client를 정리하고 서버 종료 시 추적 중인 accepted socket을 모두 닫습니다. 현재 영상만 WebRTC track이고 오디오는 별도 동일-origin HTTP이므로 정확한 A/V lip-sync는 보장하지 않습니다.
- WebRTC를 사용할 때는 `SurfaceTextureHelper`의 Surface가 MediaCodec 출력을 받고, 동일 OES texture `VideoFrame`을 libwebrtc `VideoSource`와 앱 디버그 `EglRenderer`에 fan-out합니다. 이 주 경로에는 PixelCopy·Bitmap·JPEG 할당이 없습니다.
- 휴대전화 앱에서 WebRTC 송출용 `AUTO`, `H264`, `VP8`, `VP9`, `AV1`을 선택합니다. 변경 시 저장된 preference로 활성 WebRTC 세션만 닫아 자동 재협상합니다. Android encoder와 브라우저 decoder capability의 교집합을 사용하며 실제 codec·software/fallback 진단은 휴대전화 앱이 수집합니다. `AUTO`는 hardware H.264 → VP8 → VP9 → AV1 순으로 찾고 software VP9/AV1은 앱에서 명시적으로 선택했을 때만 사용합니다. encoder factory와 sender/browser capability에 H.264와 연관 RTX를 다시 포함하되 orphan RTX 제거 방어는 유지합니다. 이 정책은 Android Auto 입력 H.264 decoder와 AV setup/media ACK 프로토콜을 변경하지 않습니다.
- WebRTC를 시작할 수 없거나 협상·연결이 실패하면 기존 UI Surface의 PixelCopy로 800×480 JPEG 품질 70 프레임을 제공합니다. 최신 프레임 한 장만 1MiB 상한으로 보관하고 기본 5fps·최대 10fps 제한을 적용하는 인증된 내부 fallback이며, 웹에는 별도 JPEG·FPS·디버그 표시 없이 동일한 AA 화면으로만 보입니다.
- 기억된 브라우저 연결 키로 capabilities와 non-trickle SDP session 생성·조회·종료 API를 보호합니다. SDP와 body 크기를 검증하고 불투명 session ID와 단일 활성 송출 세션을 사용합니다.
- TCP 5277 런타임은 debug 빌드 속성으로 명시적으로 켠 경우에만 실행됩니다. 연결·VERSION·TLS·인증 완료·서비스 탐색·채널·미디어 단계를 구분하고 제한 시간을 적용해 무한 대기하지 않습니다.
- 현재 실기기 TCP 경로는 TB520FU에서 전화기 Head Unit Server `192.168.31.231:5277`로 직접 LAN 연결하며 ADB `5277` forward/reverse에 의존하지 않습니다. EOF·소켓·접속·읽기·ping timeout은 기존 stream을 폐기한 뒤 1/2/4/8/15초 상한 backoff로 VERSION부터 자동 재협상합니다. peer shutdown·TLS·version·protocol 오류는 terminal입니다.
- Android 앱과 foreground/별도 알림은 연결됨·다시 연결 중·연결 끊김, 안전한 원인·마지막 frame·활성 프로필·WebRTC 진단을 제공합니다. 최소 브라우저 화면은 AA 영역 안의 일반 연결 대기만 표시하고 재연결 중 touch를 잠그며, native WebRTC decoder Surface가 유효하면 Activity UI Surface가 없어도 background runtime을 유지합니다.
- Android USB Host에서는 장치 탐색과 권한 요청, AOA `GET_PROTOCOL → SEND_STRING 0..5 → START`, 재열거된 accessory의 유일한 bulk IN/OUT endpoint 선택과 interface claim을 구현했습니다.
- USB 모드 전환과 bulk open은 선택한 장치의 이름·ID·VID·PID가 모두 일치하고 정차 벤치 안전 게이트가 확인된 경우에만 허용합니다. 단순 탐색은 제어 전송을 보내지 않습니다.

개별 상태인 `VERSION_ACCEPTED`, `AUTH_COMPLETE_SENT`, `SERVICE_DISCOVERY_RECEIVED` 또는 `MODE_SWITCH_REQUESTED`만으로는 Android Auto 화면 표시를 판정하지 않습니다. 화면 성공은 MediaCodec 출력 frame과 ADB screenshot을 함께 확인해 판정합니다.

## 역사적 2026-08-01 에뮬레이터 프로젝션 결과

- 소스 전화기: `SM-S938N`, Android Auto `17.2.662634`
- debug runtime 경로: 전화기 Head Unit Server → Windows ADB forward → 에뮬레이터 `10.0.2.2:5277`
- VERSION peer `1.7`과 TLS를 통과하고 198바이트·7채널 ServiceDiscovery 응답을 전송
- channel open 요청/응답과 AV setup/start를 완료한 뒤 H.264 media 수신
- 에뮬레이터 `c2.goldfish.h264.decoder`에서 800×480@60으로 디코딩해 Surface에 표시
- ADB screenshot [../artifacts/android-auto-projection-rendered.png](../artifacts/android-auto-projection-rendered.png)에서 TMAP과 Android Auto 런처 화면 직접 확인
- 호스트 브라우저의 기존 JPEG 경로에서 같은 800×480 프레임을 확인하고 우하단 런처를 클릭해 추천 패널로 실제 전환
- 입력 로그 `TOUCH_SENT phase=DOWN/UP x=755 y=444 pointer=0`과 ADB screenshot [../artifacts/tecom-browser-touch-success.png](../artifacts/tecom-browser-touch-success.png)으로 앱 Surface의 전환도 확인
- 관찰 표본: 600 rendered frames, pong 20회, 앱 런타임 오류 0건
- audio focus `RELEASE` 1회 요청에 `LOSS`로 응답
- HOME 전환 시 Surface generation 1 detach와 decoder release를 완료하고 TCP 연결을 fail-closed로 종료해 세션이 `SURFACE_LOST`가 됨; 앱 재진입 시 generation 2 Surface attach까지만 확인했으며 자동 새 TCP 세션/영상 복구는 없음. 사용자가 벤치 세션을 중지한 뒤 다시 시작해야 함

이 절은 에뮬레이터와 ADB forward를 사용한 역사적 프로젝션 증거입니다. 현재 실기기 Android Auto transport는 아래 direct LAN 정책으로 교체됐으며, 이 600-frame 결과를 TB520FU direct LAN 장시간 복구 성공으로 해석하지 않습니다.

## 현재 실기기 direct LAN·재연결 정책

- topology: Android Auto 전화기 `SM-S938N` `192.168.31.231:5277` → direct LAN TCP → 수신 앱 `TB520FU` `192.168.31.101`; 브라우저도 `http://192.168.31.101:8787` direct LAN
- Android Auto 연결은 ADB `5277` forward/reverse에 의존하지 않습니다. ADB는 기기 설치·로그·screenshot 검증 수단으로만 사용합니다.
- EOF·socket disconnect·connect failure·read timeout·ping timeout 때 손상 가능성이 있는 socket/TLS/decoder session을 닫고 1/2/4/8/15초 backoff로 VERSION·TLS·ServiceDiscovery를 모두 다시 수행합니다. 정상 video media 뒤 backoff를 초기화합니다.
- 명시적 peer shutdown, TLS policy/handshake, version mismatch, AASDK protocol, safety/credential 실패는 terminal이며 자동 반복하지 않습니다.
- Android 앱에 `CONNECTED`/`RECONNECTING`/`DISCONNECTED`, 원인과 마지막 frame 시각을 표시하고 foreground 상태를 갱신합니다. 이전에 정상 연결됐던 세션이 끊기면 별도 Android 알림을 한 번 게시합니다. 브라우저는 일반 연결 대기만 표시하고 재연결 중 touch를 잠급니다.
- native WebRTC decoder Surface가 유효하면 Activity UI Surface가 사라져도 background 프로젝션과 브라우저 송출을 유지하고, UI 복귀 시 preview만 재부착합니다. decoder Surface까지 유효하지 않으면 fail-closed로 종료합니다.
- 실기 안정 표본은 15,660 video frame으로 이전 ADB 터널 장애 지점 10,795 frame을 넘겼고 확인 가능한 연속 관찰은 8분 이상입니다. 수 시간·열·다기종 결과로 확대하지 않습니다.
- USB ADB를 유지한 제어 Wi-Fi 단절에서 앱 overlay/카드·별도 알림·touch 잠금이 동작했고, 첫 시험은 약 59초 뒤 새 decoder generation과 영상이 자동 복구됐습니다. 복구 뒤 별도 끊김 알림도 취소됐습니다.
- 반복 시험 한 번에서는 전화기 개발자용 Head Unit Server가 새 연결의 VERSION 10바이트를 `Recv-Q=10`에 둔 채 읽지 않아 서버 중지/시작이 필요했습니다. 이 비공개 전화기 서비스를 앱이 강제 재시작할 수 없으므로 현재 무인 복구 한계입니다. 서버가 정상화되자 앱은 별도 세션 버튼 조작 없이 재협상했습니다.
- launcher가 전면인 background 표본에서도 AASDK frame과 브라우저 video 시간이 계속 증가했고 앱 복귀 뒤 preview가 유지됐습니다. 반복 단절·HUS 정체를 무인 복구한 뒤 수 시간 유지하는 실기 결과는 아직 검증 전입니다.
- 외부망·서로 다른 네트워크·인터넷/NAT 연결은 현재 범위 밖입니다.

## 2026-08-02 현재 WebRTC H.264 송출 경계

- Android Auto channel 3의 H.264 Baseline selector, ServiceDiscovery, Annex-B access unit, AVC decoder와 media ACK는 그대로 유지합니다. 이 입력은 `c2.qti.avc.decoder`에서 raw OES texture로 디코딩된 뒤 브라우저용 WebRTC encoder에 전달됩니다.
- 브라우저 송출 allowlist는 VP8/VP9/AV1이고 H264는 저장값·wire parser 호환용 enum에만 남습니다. factory capability와 `createEncoder`, 선택 정책, 앱 UI, 브라우저 offer preference에서 모두 거부합니다. H264 primary payload에 연결된 RTX도 제거합니다.
- SM-S938N 실기에서 `WEBRTC_CORE_STARTED ... codecs=VP8, VP9, AV1`, `WEBRTC_SESSION_READY codec=VP8`, answer payload `96 98 45 123 125`, ICE/peer `CONNECTED`를 확인했습니다. 같은 세션에서 AVC decoder와 rendered frame이 계속 증가했고 브라우저는 800×480 `readyState=4`로 재생했습니다.
- H264-only WebRTC 기기는 송출을 unavailable/JPEG fallback으로 낮추되 AA 프로필 ServiceDiscovery 재협상은 계속합니다. 이 동작은 outbound 기능 차단일 뿐 libwebrtc native H.264 코드나 AA inbound AVC 특허 위험의 물리적 제거가 아닙니다.

## 역사적 2026-08-01 WebRTC 이식 상태

- dependency: `io.github.webrtc-sdk:android:144.7559.09`; Maven artifact/libwebrtc는 BSD 3-Clause, 배포 저장소 build script는 MIT
- native payload: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`의 `libjingle_peerconnection_so.so`
- 최종 artifact: debug APK `59,798,801` bytes, SHA-256 `62A4D01492B83009DDDFE192801A9F586A7F3F5EFE42D0667D898FEDB120484A`; unsigned release APK `48,878,766` bytes, SHA-256 `CE3B8C77ADCE70B60D76DBEA271179694A20ACC50631CEC0FE42B48CE37A3918`
- 송출 설정: 800×480@60, 최소/시작/최대 0.5/2.5/4Mbps, 해상도 유지 우선
- TB520FU/API 36 probe: `c2.qti.avc.encoder` hardware H.264, `c2.android.vp9.encoder` software VP9, `c2.android.av1.encoder` software AV1. 따라서 `AUTO`는 H.264 hardware를 선택하도록 정책과 fixture test를 고정
- 앱 runtime: shared EGL, `PeerConnectionFactory`, `SurfaceTextureHelper`, preview `EglRenderer` 초기화와 `WEBRTC_CORE_STARTED size=800x480 fps=60 codecs=H264, AV1, VP9, VP8` 확인
- 휴대전화 앱 runtime: 닫힌 영상 프로필·codec 선택과 연결/프로필/WebRTC 진단을 표시합니다. 프로필 변경은 HTTP 서버·페어링을 유지한 채 새 decoder/WebRTC source를 설치하고 AA ServiceDiscovery부터 재연결하며, codec 변경은 활성 WebRTC만 닫아 자동 재협상합니다.
- 브라우저 runtime: 페어링 뒤 AA 화면·터치와 영상 바깥 전체화면 버튼만 제공합니다. `RTCRtpReceiver` capability, `recvonly` transceiver, 앱 codec preference, non-trickle offer/answer와 JPEG fallback은 화면 뒤에서 자동 처리합니다. ICE `failed`/`closed`, AA 연결 복귀, online/visibility/pageshow에서는 단일 timer와 in-flight guard 및 1.5~15초 상한 backoff로 저장 자격증명을 사용해 계속 자동 재협상하며 수동 WebRTC 제어를 노출하지 않습니다.
- 실기 토폴로지: Android Auto 전화기 `SM-S938N` → 수신 앱 `TB520FU` `192.168.31.101` → 호스트 브라우저 `192.168.31.220`; 브라우저 직접 LAN URL `http://192.168.31.101:8787`
- 기억된 브라우저 연결 키로 새로고침 뒤 코드 재입력 없이 자동 연결했고 앱의 `AUTO` 정책이 H.264로 협상됨
- answer는 video `sendonly`와 `msid`를 포함했고 앱 ICE/Peer와 브라우저 connection이 모두 `CONNECTED`
- 인증된 session `POST`의 실제 `Socket.inetAddress`가 RFC1918이고 offer에 숫자형 IPv4 UDP host candidate가 없을 때만 `UUID.local` mDNS candidate를 그 실제 원격 주소 `192.168.31.220`으로 재작성; 동일 서브넷 여부는 별도로 검사하지 않음
- 브라우저 `<video>` `readyState=4`, 800×480, 관찰 사이 `currentTime` 증가로 연속 frame을 확인하고 ADB screenshot [../artifacts/tecom-webrtc-final.png](../artifacts/tecom-webrtc-final.png)에서 앱 디버그 Surface 동시 유지 확인
- 제어 LAN 단절과 전화기 HUS 재시작 뒤 브라우저 버튼 조작 없이 H.264, `readyState=4`, 800×480 재생과 터치로 돌아왔습니다. 활성 page 새로고침 때 남은 ICE session은 서버 정리 뒤 자동 재시도가 새 session을 생성함
- ping/pong pending queue 불일치를 해소한 뒤 10,795 frame까지 진행했고 이후 15초 무전송에서 기존 timeout 경계를 확인. 최종 watchdog은 ping 5초 간격, 최대 7 pending·35초 window, TCP read timeout 45초이며 recoverable timeout은 1/2/4/8/15초 backoff 전체 재협상으로 연결
- 최종 통합 빌드 110 tasks `BUILD SUCCESSFUL`, release 자격증명 누출 검사 `CLEAN`

TB520FU와 호스트 브라우저 사이의 직접 LAN WebRTC H.264 종단 간 경로와 위 제어 장애 뒤 브라우저 자동 재협상은 당시 성공으로 판정했습니다. 현재 송출 허용 목록도 H264/VP8/VP9/AV1로 복원됐고 SM-S938N 단독 모드에서 AUTO와 명시 H.264 모두 재검증했습니다. ICE server 기본 목록은 비어 있고 candidate 재작성은 인증된 session `POST`의 실제 원격 주소가 RFC1918이며 숫자형 IPv4 UDP host candidate가 없는 경우에만 적용됩니다. `127.0.0.1:18787` ADB forward는 HTTP signaling TCP만 전달하지만 이번 단독 모드 검증은 offer의 실제 호스트 LAN candidate와 전화기의 LAN candidate로 ICE가 연결됐습니다. 외부망·인터넷/NAT·서로 다른 네트워크는 현재 범위 밖입니다. 한 번에 하나의 브라우저 WebRTC 세션만 지원하고 JPEG 진단 경로는 장애 복구를 위해 유지합니다. 전화기 HUS 정체의 무인 복구, 수 시간 연속 운전과 현재 codec별 열·CPU·bitrate 결과는 아직 검증 전입니다.

## 역사적 이전 단계: 2026-08-01 자체서명 TLS 실패와 첫 요청 진단

- 소스 전화기: `SM-S938N`, Android 16/API 36
- Android Auto: `17.2.662634-release`
- ADB forward와 에뮬레이터의 `10.0.2.2:5277` 경로에서 실제 VERSION 수락 및 peer `1.7` 확인
- 동적으로 공급한 7일 자체서명 debug clientAuth 인증서의 SHA-256 앞 12자리: `4273C4E68F57`
- 전화기 결과: `PROTOCOL_AUTH_FAILED`/`AUTH_FAILED`; 앱 결과: `TLS_HANDSHAKE_FAILED`

이 결과는 자체서명 개발 신원이 전화기의 인증 정책을 통과하지 못했음을 보여줍니다.

그 다음의 역사적 첫-request-only 단계에서는 AASDK 자격증명을 APK에 패키징하지 않고 debug에 동적으로 공급했습니다. 지문 `1C0E0EF9E672…`, 전화기 `SSL_SUCCESS`·`SDP_REQUEST_SENT`, 앱 `SERVICE_DISCOVERY_RECEIVED peer=1.7`까지 확인한 뒤 요청 직후 진단 연결을 닫았기 때문에 전화기에 `FRAMER_READ_END_OF_STREAM`/`io error`가 기록됐습니다. 이 기록은 위 최신 full runtime 결과 이전 단계이며 삭제하지 않고 구분해 보존합니다.

현재도 공개 AASDK 개발 자격증명은 debug 벤치 전용입니다. `build.ps1`과 `build-play-aab.ps1`은 빌드·배포만 orchestration하고, canonical `certInjectNverif.py`가 공개 테스트·개발 표식 경고, 승인된 인증서 체인·peer trust 정책, 서명 및 artifact 누출 검사를 담당합니다. 배포에는 별도의 비추출 Android Keystore 키와 fail-closed 검증이 필수입니다.

## 에뮬레이터 TCP 시험 경로

에뮬레이터 하나만으로는 Android Auto를 송신할 별도 전화기를 대신할 수 없습니다. Android Auto 개발자 설정에서 Head Unit Server를 시작한 실제 전화기를 Windows ADB에 별도로 연결한 뒤 다음 경로를 사용합니다.

```text
별도 Android Auto 전화기 :5277
  → ADB forward
Windows 127.0.0.1:5277
  → Android Emulator의 호스트 별칭
에뮬레이터 앱 10.0.2.2:5277
```

```powershell
.\tools\forward-android-auto-phone.ps1 -PhoneSerial <실제-전화기-ADB-serial>
.\tools\build.ps1 -GradleArguments '-PaasdkTcpHost=10.0.2.2','-PaasdkTcpPort=5277'
```

release 빌드에서는 TCP probe 호스트가 항상 비어 있어 자동 외부 연결을 하지 않습니다. Google의 공식 DHU도 개발용 연결에 전화기의 Head Unit Server와 `adb forward tcp:5277 tcp:5277`을 사용합니다. 에뮬레이터에서 `10.0.2.2`는 Windows 호스트 loopback의 특수 별칭입니다.

## 이후 이식 순서

1. 구현된 브라우저 audio service를 실제 Android Auto MEDIA/GUIDANCE/SYSTEM 재생으로 장시간 검증하고, 필요하면 휴대전화 AudioTrack/AAudio 출력과 음악 품질용 anti-alias FIR resampler를 추가합니다.
2. 구현된 브라우저 마이크 권한·수명주기·AEC 요청과 AV-input 채널 7을 실제 음성비서 호출로
   장시간 검증하고, LAN 배포용 브라우저 신뢰 HTTPS를 별도 프로비저닝합니다.
3. 신뢰된 로컬 LAN에서 WebRTC 경로를 실제 Tesla 브라우저로 codec별 지연·열·복구까지 검증합니다. 이후 다중 탭 제어권·MOVE 병합·terminal event 재시도 정책을 구현합니다. 외부망은 현재 범위에 포함하지 않습니다.
4. 구현된 USB AOA bulk transport를 `ProjectionService`의 런타임 transport 선택지에 연결해 실물 USB-host 하드웨어에서 검증합니다.
5. direct LAN에서 장시간·자동 재협상·다기종 Android Auto 호환성과 실제 정차 차량 환경을 검증합니다.
6. 차량 상태가 확인되지 않으면 제한 상태를 보고하고 영상·터치를 중단합니다.

## 선택 빌드

`app/src/main/cpp`에는 JNI 포트의 컴파일 가능한 최소 경계가 들어 있습니다. NDK와 CMake를 설치한 후 다음 속성으로 활성화할 수 있습니다.

```powershell
.\gradlew.bat assembleDebug -PenableNativeOpenAuto=true
```

현재 JNI 코드는 포트 상태와 좌표 매핑만 제공하고 `OPENAUTO_RUNTIME_LINKED=false`를 반환합니다. 이 플래그는 선택적인 네이티브 OpenAuto 런타임 결합 상태이며, 실기 검증된 Kotlin AASDK runtime의 VERSION·TLS·서비스·H.264 경로를 부정하는 전체 앱 상태가 아닙니다. 네이티브 원본 런타임을 실제로 결합하고 별도 검증하기 전에는 플래그를 바꾸지 않습니다.

## P0 합격 조건

- 대상 Tesla에서 로컬 페이지 접속 50회 중 95% 이상 성공
- 휴대전화 앱에서 선택한 codec 또는 정책상 fallback으로 WebRTC가 자동 연결되고 브라우저에 활성 프로필의 실제 frame을 표시하며, 실패 시 인증된 JPEG 화면으로 자동 복귀
- 브라우저 pointer 이벤트가 현재 활성 프로필의 content rect 좌표로 정확히 변환
- 8자리 등록 코드 오입력과 브라우저 연결 키가 없는 보호 API 요청이 모두 401 처리
- 앱 화면 종료·서비스 중지 후 2초 이내 서버 포트 닫힘
- UNKNOWN/MOVING 상태에서 session start와 touch가 모두 거부
- Android 12~16의 실제 기기에서 foreground-service 시작·종료 확인
