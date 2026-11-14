# 실행·테스트 가이드

Windows 기준이고, Mac은 ☞ 표시로 따로 적었습니다.

---

## 0. 준비물

| 항목 | 기준 |
|---|---|
| PC | Pleos 권장 사양(그래픽카드 RTX 4060급 8GB) |
| Android Studio | 최신 버전 (developer.android.com/studio). 첫 실행 마법사는 **Standard** |
| 인터넷 | 첫 빌드 때 Pleos SDK·라이브러리를 내려받습니다 |

압축은 **한글·공백 없는 경로**에 푸세요. 예: `C:\dev\gachitan`
사용자 폴더 이름이 한글이면 에뮬레이터가 멈출 수 있습니다. 환경 변수 `ANDROID_AVD_HOME=C:\Android\avd`, `TEMP`·`TMP=C:\Temp`로 바꿉니다.

---

## 1. 프로젝트 열기 + 엔진 테스트 (10분)

1. Android Studio → **File → Open** → `gachitan` 폴더 선택
2. 오른쪽 아래 **Gradle sync**가 끝날 때까지 기다립니다. "Install missing SDK" 같은 안내가 뜨면 눌러서 설치합니다.
3. Gradle JVM이 25 등으로 맞지 않는다고 나오면 **Use JVM 21**을 고릅니다. AGP 업그레이드 안내는 누르지 않습니다.
4. 아래쪽 **Terminal** 탭에서:
   ```
   gradlew.bat :core:test :app:testDebugUnitTest
   ```
   ☞ Mac: `./gradlew :core:test :app:testDebugUnitTest`

   **BUILD SUCCESSFUL** = 정산 엔진 50개 + 세션·화면 규칙 35개 테스트 통과입니다.
   결과 보고서: `core/build/reports/tests/test/index.html`, `app/build/reports/tests/testDebugUnitTest/index.html`

---

## 2. Pleos 에뮬레이터 만들기 (20~30분, 처음 한 번)

1. **Tools → SDK Manager → SDK Update Sites** 탭 → **+**
   - Name: `Pleos Connect System Image`
   - URL: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - ☞ Mac: `.../2.x/crp-arm64-sys-img.xml`
2. **SDK Platforms** 탭 → 오른쪽 아래 **Show Package Details** 체크 → **Android API 34 → Pleos Connect System Image** 체크 → OK → 설치 후 **Android Studio 재시작**
3. **Tools → Device Manager → Create device** → 왼쪽 **Automotive** → **Pleos Connect v2.1.0** → Next
4. 시스템 이미지 **API 34 / Pleos Connect v2.1.0** 선택
5. **Advanced Settings**: Boot = **Cold** (필수, Quick이면 차량 신호 사용 불가), RAM = **4 GB** → Finish
6. 만든 기기 옆 **▶**로 켭니다. 첫 부팅은 몇 분 걸립니다.

---

## 3. 앱 실행

1. 위쪽 실행 대상에 **app**과 **Pleos Connect 에뮬레이터**가 선택돼 있는지 확인
2. 초록색 **▶ Run** → 에뮬레이터에 **같이 탄 만큼** 화면이 뜨면 성공
3. 오른쪽 위 설정(톱니) → **차량 연결 상태**에서 확인:
   - ● 좌석 탑승 / ● 계기판 누적거리 / ● 기어 → 차량 신호 정상
   - ○ 내비 연동 → 에뮬레이터에 연동 내비 앱이 없는 것. **시뮬레이터로 대체**하면 됩니다

adb 명령은 아래 경로로 실행합니다(PATH에 없을 때).
```
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
```
☞ Mac: `~/Library/Android/sdk/platform-tools/adb`

---

## 4. 차량을 조작하는 두 가지 방법

### A. Virtual Console (실제 차량 신호, 권장)
```
adb forward tcp:8088 tcp:8088
```
PC 브라우저에서 `http://127.0.0.1:8088/` 을 엽니다.

| 하고 싶은 것 | Virtual Console 위치 |
|---|---|
| 좌석에 사람 태우기·내리기 | Convenience → **Seat Occupancy** (Empty / Occupied) |
| 출발·정차 | Motion → **Gear Selection** (D / P) |
| 거리 늘리기 | Motion → **Odometer** |
| 배터리 줄이기 | Energy → **Battery SOC** |

### B. 시뮬레이터 (앱 안, 시연용)
설정 → **시연 → 시뮬레이터** → 화면 아래 줄:
`D 출발/P 정차` `+5km` `+20km` / 좌석 ○● / `도착 판교역·정자역·수원역·강릉 숙소·안목해변` / `다음 날` `초기화`

- 시뮬레이터 기록은 실제 차량 기록과 **따로 저장**됩니다. 운행을 기록하는 중에는 모드를 바꿀 수 없습니다.
- 시뮬레이터는 '가짜 차량'이라 앱 화면이 잠겨 있어도 조작할 수 있습니다. 플랫폼 주행 규제가 켜지면 숨겨집니다.

---

## 5. 시나리오별 테스트

각 시나리오의 **기대 결과**가 나오면 통과입니다. (시뮬레이터 기준. Virtual Console로도 같은 순서)

### 시나리오 1. 대표 흐름: 퇴근 카풀
1. 좌석 ● 조수석, ● 뒷좌석 왼쪽 → 앱에서 조수석을 눌러 **민지**, 뒷좌석 왼쪽 **준호**
2. **D 출발** → 화면이 **기록 중**(버튼 없음, 거리·사람만) → **+20km**
3. **도착 정자역** → **P 정차** → 뒷좌석 왼쪽 ○(준호 하차) → 뒷좌석 오른쪽 ●(새 사람)
4. ✅ **정차 중**: 뒷좌석 오른쪽이 "이름 없음", 왼쪽은 빈 자리
5. **D 출발** → **+20km** → **P 정차** → **통행료·주차비** → 1,200원(나눌 사람이 미리 3명 선택돼 있음) → 추가
6. **운행 끝내기** → ✅ **정산** 탭: 왼쪽 총액·"추정액"·구간 막대(20km · 3명 | 20km · 3명, 사이에 "준호 하차 · …탑승"), 오른쪽 사람별 부담액
7. **기록 수정** → 탑승 막대에서 이름 없는 줄을 눌러 **서연**
8. **확정하고 공유** → ✅ 공유 패널: 민지 이름·탄 거리·금액·QR. 아래에서 준호·서연으로 바꿀 수 있음
9. ✅ **기록** 탭: 오늘 날짜 아래 이 운행 한 줄(시간·40.0km·인원·4,000원). 누르면 같은 정산 상세

기대 금액(기본 설정 350원/kWh·70kWh, 시뮬레이터 배터리 감소로 계산 → km당 70원): **합계 4,000원 · 민지 1,330원 · 준호 470원 · 서연 870원 · 운전자 1,330원**
(첫 20km 1,400원 ÷3, 뒤 20km 1,400원 ÷3, 통행료 1,200원 ÷3. 동승자는 10원 단위, 차액은 운전자. `TripSessionTest`에 같은 값으로 고정돼 있습니다)

### 시나리오 2. 같은 좌석 재탑승 확인
1. 조수석 ● → **민지** → D → +5km → P → 조수석 ○ → 조수석 ●
2. ✅ 조수석이 주황색 "민지?" → 눌러서 **민지 맞아요** 또는 다른 이름
3. 확인하지 않고 **운행 끝내기** → ✅ 확정 버튼이 "확인할 좌석 1곳"으로 잠김

### 시나리오 3. 주행 중 잠금
```
adb shell cmd hmg_car_service --service drivingstate driving_regulation on
```
✅ P 상태여도 주행 화면으로 바뀌고 버튼과 시뮬레이터가 사라짐. `off`로 되돌리면 원래 화면
✅ 시뮬레이터에서 D만 누른 상태(계기판 대기 포함)에서도 입력이 잠김

### 시나리오 4. 묶음 정산 (2박 3일·한 주 카풀)
1. 탑승 탭의 **여행 시작** → "강릉 2박 3일" → 시작 (상단바에 여행 이름이 붙음)
2. 운행을 정산할 때마다 ✅ "강릉 2박 3일 1일차에 추가", **QR 없음**("송금은 묶음을 끝낼 때 한 번에")
3. **다음 날** → 운행 → 정산 / 머리글의 묶음 이름 → **경비 추가**: 숙박 300,000원(민지 결제, 모두)
4. 마지막 날은 정차한 채로 **끝내고 정산** → ✅ 정차 중이던 운행도 포함
5. ✅ 사람별 받을 돈·보낼 돈, "송금 n번으로 정리(많아야 n-1번)". 이름을 누르면 **그 사람 몫만** 담긴 QR
6. **전체 내역 공유**는 확인을 한 번 더 받은 뒤 전체 QR

### 시나리오 5. 앱이 꺼졌다 켜질 때
1. 기록 중(D) 또는 정차 중 앱 강제 종료: `adb shell am force-stop kr.gachitan` → 다시 실행
2. ✅ 같은 운행·이름·거리로 복원. 정차 중에는 "앱이 꺼져 있던 동안의 승하차는 기록되지 않았어요" 안내

### 시나리오 6. 연결 오류
1. 실제 차량 모드에서 Virtual Console을 끄거나 권한을 빼고 실행
2. ✅ "주행거리를 받지 못하고 있어요" 등 상태 + **다시 연결**·**진단 정보**

### 시나리오 7. 창 크기와 화면 점검
- 앱 창을 1:1(좁은 창)과 단독(넓은 창)으로 바꿔 봅니다. 탑승·주행·정산·공유·기록 다섯 화면을 각각 캡처합니다.
- ✅ 넓은 창: 왼쪽 중심 요소(좌석 배치도·총액), 오른쪽 정보와 아래 행동. 좁은 창: 위에서 아래로 쌓이고 주 버튼은 아래 고정. 글자 크기는 같음
- 확인할 것
  - 한글·금액 잘림(특히 뒷좌석 칸의 이름, 상단바의 여행 이름)
  - 아래쪽이 크게 비거나, 한 화면이 빽빽하지 않은지
  - 주 버튼(블루)이 한 화면에 하나인지
  - 좌석 수와 탑승 상태가 시뮬레이터 ●○와 같은지
  - 정산 탭 금액 = 공유 패널 금액 = 기록 탭 금액
  - D로 바꾸면 메뉴·설정·버튼이 사라지는지
- Android Studio에서 `ui/screens/Previews.kt`를 열면 예시 데이터(4,800원 사례)로 정산·공유·기록 화면을 넓은 창·좁은 창 크기로 미리 볼 수 있습니다. 예시 데이터는 미리보기 전용이라 앱 실행 중에는 나오지 않습니다.

### 시나리오 8. 휴대폰에서 열기
1. `docs/index.html`을 GitHub Pages 등에 올리고 설정 → 받는 곳 → **정산 화면 주소**에 입력 (또는 PC에서 `docs` 폴더에서 `python -m http.server 8000`)
2. 묶음 정산 결과 → 이름 → QR을 휴대폰 카메라로 촬영
3. ✅ 그 사람 몫, 보낼/받을 송금, 토스 아이디가 있으면 **토스로 보내기** 버튼. 다른 사람끼리의 송금·장소는 보이지 않음

---

## 6. 시연 영상 녹화

```
adb exec-out screenrecord --output-format=h264 - > demo.h264
```
Ctrl+C로 종료 후 변환:
```
ffmpeg -framerate 60 -i demo.h264 -c copy demo.mp4
```
시뮬레이터의 **▶ 시연 시나리오**를 누르고 녹화하면 README 4장의 흐름이 그대로 담깁니다.

---

## 7. 제출용 APK

```
gradlew.bat :app:assembleRelease
```
`app/build/outputs/apk/release/app-release.apk` → `apk/N분의1_같이탄만큼_release.apk`

---

## 8. 문제 해결

| 증상 | 해결 |
|---|---|
| Gradle sync에서 `ai.pleos.playground:...` 를 찾을 수 없음 | 인터넷 연결 확인, `settings.gradle.kts`에 Pleos Maven 주소가 있는지 확인 |
| `minSdk` 관련 manifest 병합 오류 | 오류 메시지의 버전으로 `app/build.gradle.kts`의 `minSdk`를 올림 |
| 에뮬레이터가 안 켜짐 | 그래픽 드라이버 업데이트, RAM 4GB, Cold boot 확인 |
| 차량 연결 상태에서 좌석·계기판이 ○ | 에뮬레이터를 **Cold boot**로 다시 켜기(Quick boot면 VHAL 안 됨) |
| 좌석을 바꿔도 반응 없음 | 에뮬레이터를 Cold boot로 다시 켜기 |
| 에뮬레이터가 바로 꺼짐 | 사용자 폴더가 한글이면 `ANDROID_AVD_HOME`·`TEMP` 변경(0장) |
| Virtual Console 접속 안 됨 | `adb devices`로 연결 확인 → `adb forward tcp:8088 tcp:8088` 다시 실행 |
| 내비 연동 ○ | 정상(연동 내비 앱 없음). 시뮬레이터 '도착' 버튼 사용 |
