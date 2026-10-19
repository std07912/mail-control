# 정산자료 전송 도우미 (Android MVP 소스)

Kotlin · Jetpack Compose Material 3 · MVVM · Coroutines/Flow · Room · SAF · WorkManager.
개인용, 백엔드·광고·분석 SDK 없음. 원본 파일은 읽기만 하며 작업은 앱 전용 저장소 복사본에서 수행합니다.

**최신 상태: GitHub Actions에서 컴파일·단위 테스트·lint·Debug APK 빌드 성공. 기기 실행·실메일 검증 전입니다.**

[APK 다운로드 ZIP](https://github.com/std07912/mail-control/actions/runs/39195154059/artifacts/12161268212) · [성공한 빌드](https://github.com/std07912/mail-control/actions/runs/39195154059)

아래 로컬 환경의 빌드 제한 기록은 이전 상태이며, 최신 결과는 문서 마지막 절을 확인하세요.
이 환경에는 Java 21만 있고 Gradle·Android SDK가 없습니다. 도구 다운로드는 `proxy:8080` 연결 실패로 중단되었습니다. 실제 빌드 시도 기록은 [build-attempt.log](build-attempt.log), 상세 검증 범위는 [검증 보고서](VERIFICATION.md)에 있습니다. 로컬에서는 APK를 생성하지 못했으나 이후 GitHub Actions에서 생성했습니다.

## 포함 기능

- SAF 다중 문서·재귀 폴더 선택, 앱 내부 복사, 스트리밍 SHA-256 중복 제거, 파일명 정렬·위/아래 이동·선택 제외, 복원 가능한 선택 목록.
- ZIP 목록 사전 확인·승인 후 로컬 해제. 원래 하위 경로·파일명 유지. 경로 탈출·절대경로·Windows 경로·심볼릭 링크·중복 경로·압축률·항목 수·예상/실제 해제 크기 검사. 비밀번호 ZIP 거절. 미승인 형식 기본 제외.
- PDFBox 기반 독립 PDF 분할. 목표 1~9MB, 페이지 순서 유지, 각 후보를 실제 저장 후 크기 측정. 단일 초과 페이지는 별도 처리 상태·선택 제외. 원본 보존, 서명 손상 경고. 디스크 임시 저장 사용.
- 순서 유지 묶음 또는 first-fit decreasing 근사 묶음. 첨부 합계·개별 크기·보수적 Base64 추정 및 **완성된 .eml 실제 바이트** 모두 검사. 최종 검토 전에는 발송하지 않음.
- 네이버 STARTTLS 587, 인증·연결 테스트(전송 없음), 앱 비밀번호 AES-GCM/Android Keystore 암호화 저장, 서버 인증서·호스트 검증. SMTP 디버그 로그 비활성화, 앱 백업 금지.
- 고정 MIME/Message-ID, Room 조건부 상태 획득, 작업별 WorkManager 체인과 프로세스 Mutex로 순차 발송. 포그라운드 알림, 전체 작업 취소·실패/취소 건 수동 재시도.
- DATA 전송 시도 후 응답 유실/중단은 `UNCERTAIN`: 자동 재시도 금지, 사용자 별도 접수 확인 필수. SMTP 성공과 실수신은 구분.
- 작업/첨부/수신자/제목/시각/접수 응답/오류 보관, UTF-8 BOM CSV 내보내기(수식 삽입 방어), 완료 작업 임시파일 삭제.
- 한국어 5개 탭. PDF/문서 미리보기는 기기에 설치된 뷰어에 사용자가 명시적으로 열기를 요청하는 방식.

## 빌드 (인터넷 연결이 되는 개발 PC)

1. Android Studio에서 이 폴더를 엽니다. SDK Manager로 **Android SDK Platform 36**, SDK Build Tools, Platform Tools를 설치합니다.
2. JDK 17 이상과 **Gradle 8.13**을 준비합니다. AGP 실행은 JDK 17을 권장합니다.
3. `local.properties`에 해당 PC의 `sdk.dir=/실제/Android/Sdk`를 설정하거나 `ANDROID_HOME`을 설정합니다. 이 파일은 저장소에 포함하지 않습니다.
4. 이 프로젝트의 `gradlew`는 표준 wrapper JAR가 아닌 **소스 전용 다운로드 부트스트랩**입니다. Linux/macOS에서 curl/unzip/sha256sum이 있으면 Gradle 8.13 배포 ZIP과 공식 SHA-256을 받아 검증합니다. Windows `gradlew.bat`는 PATH의 Gradle을 사용합니다.
5. Android Studio 최초 Sync에서 wrapper가 필요하면, 설치한 Gradle 8.13으로 `gradle wrapper --gradle-version 8.13 --distribution-type bin`을 실행하여 표준 wrapper JAR/스크립트를 생성하세요. 또는 IDE의 Gradle distribution을 로컬 Gradle 8.13으로 지정합니다. wrapper JAR는 본 산출물에 없습니다.
6. 다음을 실행합니다.

```sh
./gradlew testDebugUnitTest assembleDebug
# 에뮬레이터/더미자료 전용 기기 연결 후:
./gradlew connectedDebugAndroidTest
# 설치:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

빌드 성공 시 APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug APK는 개인 테스트용입니다. 배포용 서명키는 별도로 관리하고 저장소에 넣지 마세요.

### 버전 선택과 라이선스

빌드 파일은 호환성을 우선한 안정 버전 조합으로 고정했습니다: AGP 8.13.2 / Gradle 8.13 / Kotlin·Compose compiler 2.2.21 / Compose BOM 2025.12.00 / compileSdk 36 / targetSdk 35 / minSdk 26. Room 2.8.4, WorkManager 2.11.0. 이는 2026년 전체 생태계의 최신 버전이라고 주장하지 않습니다. 요청하신 최신 안정 버전 조합의 최종 확인과 의존성 해석은 네트워크가 가능한 환경에서 남은 작업입니다. 현 단계에서 검증 없이 AGP 9의 내장 Kotlin DSL로 전환하지 않았습니다.

- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) 2.0.27.0: Apache-2.0, Android 포팅. 상용 PDF SDK 없이 페이지 추출 가능, 디스크 기반 scratch 지원. 구형 포팅 버전이므로 대형·복잡한 문서 호환성/유지보수 검토 필요.
- [Zip4j](https://github.com/srikanth-lingala/zip4j) 2.11.5: Apache-2.0, ZIP 메타정보·스트림 접근. 앱은 암호 ZIP 해제를 구현하지 않습니다.
- [JavaMail Android](https://javaee.github.io/javamail/Android) android-mail / android-activation 1.6.7: Android 호환 javax.mail 구현. CDDL/GPL with Classpath Exception 등 각 아티팩트 LICENSE를 배포 전에 확인하세요. Jakarta Mail API 네임스페이스와 혼용하지 않습니다. 구형 라이브러리이므로 의존성 취약점 점검도 출고 전 필요합니다.
- AndroidX·Kotlin·Coroutines: 각 배포물의 Apache-2.0 등 라이선스 확인. 원 라이선스/NOTICE는 의존성 배포 조건에 맞게 별도 관리합니다.

## 네이버 공식 정책 조사 (2026-10-19)

공식 안내는 IMAP/SMTP 활성화, smtp.naver.com:587 TLS, 2단계 인증 및 **애플리케이션 비밀번호**를 요구합니다. 2025-06-24 비밀번호 정책 변경 안내도 확인했습니다.

- [IMAP/SMTP 설정 및 해제](https://help.naver.com/service/30029/contents/21344)
- [SMTP 연동 문제 확인](https://help.naver.com/service/30029/contents/21351)
- [비밀번호 정책 변경 FAQ](https://help.naver.com/service/30029/contents/24347?lang=ko&osType=COMMONOS)
- [공식 로그인 API 명세](https://developers.naver.com/docs/login/api/api.md)

공식 로그인 API 문서에는 SMTP 메일 발송용 OAuth scope/API가 확인되지 않았습니다. 공식 로그인만으로 SMTP 발송이 가능하다는 근거가 없어 앱 비밀번호 방식을 선택했습니다. 웹 로그인·스크래핑·CAPTCHA 우회·인증서 무시 기능은 없습니다. 공식 문서 확인은 실제 계정의 인증 가능 여부를 검증한 것과 다릅니다. 실계정 인증·587 연결·서버 빈도/용량 제한은 사용자의 테스트 계정과 실제 네트워크에서 확인해야 합니다. 일일/계정별 제한을 추측하여 하드코딩하지 않았고, 오류 발생 시 해당 작업을 중단합니다.

## 기본값과 제약

- MB는 1,000,000 bytes. 첨부 합계 9MB, 개별 9MB, PDF 목표 9MB, MIME 전체 13MB, 발송 간격 60초(설정 최소 30초). **기본값은 기관 승인 또는 네이버 최대치가 아닙니다.** 기관이 9MB를 전체 메시지로 제한하면 MIME 제한도 9로 설정하고 첨부 기준을 더 낮추세요.
- 승인 확장자는 설정에서 수정합니다. PDF 외 문서는 바이트 분할·확장자 변경하지 않습니다. ZIP 자체 전송은 기본 허용 목록에 없습니다.
- 수신자 한 명, 네이버 `@naver.com` 개인 계정만 지원합니다. 참조/숨은참조, 조직별 정책 서버, OAuth, 네이버웍스는 범위 밖입니다.
- 가져오기 파일당 2GB, 폴더 깊이 32/항목 10,000, ZIP 항목 5,000/파일당 500MB/전체 해제 1.5GB/최대 압축률 200배. 저장소 32MB 여유 검사. 강한 압축의 정상 ZIP도 안전 한도로 거절될 수 있습니다.
- 파일 처리와 SMTP는 IO 디스패처에서 실행합니다. PDF 디스크 scratch를 사용해도 PDF 객체/리소스는 메모리를 쓰므로 임의 크기의 문서 성공을 보장하지 않습니다. 이진 탐색으로 적합한 연속 범위를 찾으며, 모든 선택 결과를 실제 측정합니다. 최소 조각 수·최소 이메일 수의 수학적 최적해는 보장하지 않습니다.
- 최종 승인 후 MIME 파일과 발송 설정은 스냅샷으로 고정됩니다. 설정 변경은 이미 승인된 작업에 소급하지 않습니다. SMTP 서버는 Message-ID에 따른 중복 제거를 보장하지 않습니다. 앱은 접수 완료 행 재발송을 차단하고 불확실 행을 보류하지만, SMTP 자체의 exactly-once 전달 보장은 불가능합니다.
- 취소 버튼은 즉시 취소를 요청합니다. 진행 중 소켓의 종료/타임아웃에는 시간이 걸릴 수 있고, 이미 접수된 메일은 회수되지 않습니다. 강제 종료 후 남은 상태는 자동발송 화면의 중단 상태 확인으로 점검합니다.
- 인증정보는 Keystore 암호화하지만 문서 복사본·MIME 본문·이력 DB는 앱 샌드박스에 보관하며 별도 파일 암호화는 없습니다. 기기 자체 암호화/잠금을 사용하세요. 백업은 비활성화했습니다. CSV는 사용자가 선택한 위치에 평문으로 내보냅니다.
- 임시파일 삭제는 미완료·불확실·실패/취소 작업이 있으면 차단해 재시도 자료를 보존합니다. MVP에는 개별 작업 영구 폐기/계정 삭제 UI가 없습니다. 앱 데이터 삭제는 계정·문서·이력 전체를 지웁니다.
- 앱 내부 문서 선택은 재시작 시 복원합니다. 작업은 Room/WorkManager에 저장됩니다. 강제중지·배터리 최적화·Android 장시간 dataSync 제한은 OS 정책을 따릅니다.

## 파일 안내

- `Core.kt`: 정책·그룹화·해시·ZIP 경로·CSV·불확실 상태 판정
- `Documents.kt`: SAF 복사·폴더·ZIP·PDF
- `Store.kt`: Room·Keystore
- `Mailer.kt`: MIME 스냅샷·SMTP·Worker
- `MainViewModel.kt`: 상태·작업·이력·CSV
- `MainActivity.kt`: 한국어 5개 탭
- [사용 설명서](USER_GUIDE.md), [검증 보고서/기기 테스트 체크리스트](VERIFICATION.md), [진행 현황](PROGRESS.md)


## 로컬 작업 재개

GitHub 도구 없이도 프로젝트 파일 수정과 셸 검증을 계속했습니다. 실제 DAO SQL을 로컬 SQLite에서 실행하는 테스트 10개가 통과했습니다. `python3 -m unittest discover -s scripts -p 'test_*.py' -v`로 재현할 수 있습니다. 최신 수정 및 검증 한계는 VERIFICATION.md의 추가 기록을 참조하세요. 전체 Android 빌드는 여전히 프록시 연결 오류로 차단됩니다.


## GitHub 실제 빌드 성공 (2026-10-19 최신 상태)

저장소: https://github.com/std07912/mail-control
소스 커밋: 5ad890d12c09c3056f7ab451512d930a2e195334
실행 결과: https://github.com/std07912/mail-control/actions/runs/39195154059
APK 다운로드 ZIP: https://github.com/std07912/mail-control/actions/runs/39195154059/artifacts/12161268212

- 최초 CI는 폐기된 Android SDK `tools` 패키지 요청으로 실패했고, `platform-tools`만 요청하도록 수정했습니다.
- GitHub Actions에서 SDK/Gradle 설치, SQLite 테스트 10개, 정적 검사 통과.
- `gradle --no-daemon --stacktrace testDebugUnitTest assembleDebug lintDebug` **성공** (6분 21초, 58개 태스크 실행).
- Kotlin 컴파일·Room 코드 생성·Kotlin/JVM 단위 테스트·Debug APK 빌드·lint가 실제 수행됐습니다. 이 문서의 이전 미컴파일/미생성 기록은 당시 로컬 환경 상태이며 최신 결과는 본 절입니다.
- `settlement-mail-debug-apk` 산출물 ZIP 20,669,257 bytes. 압축을 풀면 `app-debug.apk`가 있습니다. 비공개 저장소 접근을 위해 GitHub 로그인이 필요하며 산출물은 2026-10-26 만료 예정입니다.
- 아직 Android 기기 instrumentation 테스트, 삼성 갤럭시 설치/실행, 100MB PDF, 실제 네이버 인증·발송은 미검증입니다. 더미자료·테스트 계정으로 기기 시험을 진행해야 합니다.
