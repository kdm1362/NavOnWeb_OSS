# 휴대전화 단독 Android Auto 웹 서버 모드

## 목적

`THIS_PHONE` 모드는 별도 태블릿이나 에뮬레이터 없이 한 휴대전화에서 다음 경로를 실행합니다.

```text
Android Auto Developer Head Unit Server (:5277)
  -> 앱 AASDK client (127.0.0.1)
  -> MediaCodec decoder / WebRTC video encoder
  -> PCM16 audio entitlement processor
  -> 앱 HTTP signaling server (:8787)
  -> 같은 LAN 또는 휴대전화 hotspot의 브라우저 (WebRTC video + Web Audio PCM)
```

앱 화면에서 **이 휴대전화에서 바로 연결**을 선택하면 AASDK endpoint는 항상
`127.0.0.1:5277`로 고정됩니다. 브라우저나 저장된 설정이 임의 주소 또는 포트를
주입할 수 없습니다. 선택은 앱 내부에 기억되며 실행 중인 세션에서는 변경할 수 없습니다.

## 정차 debug 벤치 사용 순서

1. Android Auto 개발자 모드를 활성화합니다.
2. Android Auto 개발자 메뉴에서 **Head unit server 시작**을 누릅니다.
3. 앱에서 **이 휴대전화에서 바로 연결**을 선택합니다.
4. 휴대전화 앱에서 영상 프로필과 WebRTC codec을 선택하고 대략적 위치 권한을 허용합니다.
   연결·프로필·codec·주야간·오디오 진단도 앱에서 확인합니다. 위치를 거부해도 기기 화면
   모드 또는 안전한 야간 기본값으로 동작합니다.
5. 차량이 주차 상태이고 이 기기가 운행에 사용되지 않음을 확인한 뒤 첫 연결에서는 **서비스 시작**을 누릅니다.
   실제 Android Auto 영상 프레임을 한 번 수신한 설치는 이후 앱을 새로 열 때 서비스를 자동 시작합니다.
6. 앱이 표시한 `http://<휴대전화-LAN-IP>:8787` 주소를 같은 LAN 또는 hotspot의
   브라우저에서 엽니다.
7. 최초 한 번만 앱의 페어링 코드를 입력합니다. 연결 뒤 웹에는 AA 화면·터치와 영상 바깥
   전체화면 버튼만 표시되며 설정이나 진단 정보는 표시하지 않습니다. AA 화면에서 두 손가락을
   벌리면 전체화면, 오므리면 원본 종횡비의 일반화면으로 전환합니다. 브라우저 자동재생
   정책상 페어링 제출, AA 화면 터치 또는 전체화면 버튼으로 첫 사용자 제스처가 발생한 뒤
   음성이 재생됩니다.

Head Unit Server가 꺼져 있으면 휴대전화 앱과 알림에 구체적인 원인을 표시하고, 브라우저에는
일반 연결 대기를 표시한 채
1/2/4/8/15초 상한 backoff로 자동 재접속합니다. 일반 앱이 Android Auto의 비공개
개발자 서비스를 자동 시작하는 지원 API는 없으므로 사용자가 서버를 수동으로 시작해야 합니다.

영상 프로필을 변경하면 HTTP 서버·브라우저 페어링·foreground service와 오디오 권한 정책은 그대로 유지됩니다.
앱이 새 decoder와 WebRTC source를 준비하고 Android Auto runtime을 ServiceDiscovery부터 다시
협상하며, 브라우저는 같은 URL에서 새 종횡비와 WebRTC 영상을 자동으로 받습니다. codec 변경은
Android Auto 연결을 건드리지 않고 활성 WebRTC 세션만 자동 재협상합니다.

현재 시간과 대략적 위치로 계산한 일출·일몰은 AASDK NIGHT_DATA에 반영되며 60초마다
재평가해 값이 바뀔 때만 전송합니다. 좌표는 메모리에서 약 1.1 km 단위로 축약되고 웹과
진단 화면에는 노출되지 않습니다. 보이는 Activity가 현재 위치를 수집하고 foreground
service도 마지막 위치와 one-shot 현재 위치를 best-effort로 갱신합니다. 잠긴 background
cold start에서 Android가 while-in-use 위치를 거부하면 기기 UI night hint 또는 fail-dark로
안전하게 전환합니다.

MEDIA는 48 kHz stereo, GUIDANCE와 SYSTEM은 16 kHz mono PCM16LE로 받습니다. 무료 권한은
서버에서 모든 트랙을 mono 16 kHz로 강제 변환하고 유료 권한은 원본 형식을 보존합니다.
세 트랙은 기억된 브라우저 연결 키로 인증한 WebRTC DataChannel을 통해 휴대전화에서
브라우저로 직접 전송되어 Web Audio에서 혼합됩니다. 오디오는 WebRTC audio track이 아니라
별도 PCM DataChannel이므로 transport-level lip-sync를 보장하지 않습니다. 로컬 HTTP 호환
경로의 유휴 heartbeat와 write-progress watchdog은 닫히거나 읽지 않는 client의 슬롯을
회수하며 서비스 종료 시 모든 accepted socket을 즉시 닫습니다.

## 빌드

휴대전화 단독 debug APK는 다음과 같이 만듭니다. Android Auto endpoint는 설정이나 Gradle
인자로 선택하지 않으며 항상 같은 단말의 `127.0.0.1:5277`입니다.

```powershell
.\tools\build-source.ps1 -GradleArguments '-PenablePremiumProjectionBench=true'
```

AASDK 개발 자격증명은 APK에 넣지 않고 설치 뒤 앱의 `no_backup` 사설 저장소에
별도로 주입합니다. 검증하지 않는 debug peer trust는 정차 벤치 전용입니다.

## 한계

- 재부팅, Android Auto 업데이트 또는 서비스 종료 뒤 Head Unit Server를 다시 시작해야 할 수 있습니다.
- 동일 휴대전화가 Android Auto 인코딩, 앱 디코딩, WebRTC 재인코딩을 함께 수행하므로
  별도 수신 장치보다 발열과 MediaCodec 경쟁이 큽니다. 기본 권장은 720p이고 1080p는 고부하 옵션입니다.
- 인증된 브라우저 음성 출력과 secure-context 브라우저 마이크 업링크는 구현됐지만 휴대전화 자체
  AudioTrack 출력과 내장 마이크 직접 입력은 아직 완성되지 않았습니다. 평문 LAN 주소의 브라우저
  마이크는 사용할 수 없으며, 개발 시 ADB loopback 또는 배포 시 브라우저가 신뢰하는 HTTPS가
  필요합니다. 무료 선형 16 kHz resampler는 음성 중심이며 음악 품질용 FIR은 아닙니다.
- 현재 `dataSync` foreground service는 target 35 이상에서 앱이 background인 동안 24시간당
  총 6시간 제한을 받습니다. 판매판은 실제 사용 목적과 Play 정책에 맞는 foreground service
  유형 또는 별도 지속 실행 설계를 심사해야 합니다.

