# 검증 보고서

작성일: 2026-10-19. 실제 업무자료·실계정 비밀번호는 사용하지 않았습니다.

## 이 환경에서 수행한 사항

| 항목 | 결과 / 근거 |
|---|---|
| 환경 점검 | Java 21 확인. Android SDK/Gradle/Kotlin compiler/기기 없음 |
| 공식 네이버 정책 조사 | 공식 고객센터 및 로그인 API 문서 조사. README에 출처 기록 |
| 프로젝트/소스/테스트/문서 생성 | 파일 생성 완료. 런타임 기능 성공을 의미하지 않음 |
| XML 파싱 및 소스 위생 검사 | `python3 scripts/static_check.py` 통과: XML 3개, 백업/평문 HTTP 금지, 인증서 무시/디버그 로그 패턴 검사 |
| 셸 부트스트랩 구문 | `sh -n gradlew` 검사 |
| Gradle 빌드·JVM 테스트 시도 | `GRADLE_USER_HOME=/workspace/tooling/gradle ./gradlew testDebugUnitTest assembleDebug` 실행 시 Gradle 다운로드 단계 실패. `build-attempt.log` 참조 |
| 의존성 해석·Kotlin 컴파일 | **미실행**: 프록시 연결 실패로 Gradle 배포본 다운로드 불가, Android SDK도 없음 |
| Debug APK | **없음** |
| JVM 자동화 테스트 | **코드만 작성, 미실행** |
| Android 기기 테스트 | **코드만 작성, 미실행** |
| SMTP 인증/전송/기관 수신 | **미실행**: 테스트 계정·실기기·실제 SMTP 네트워크 필요 |

`static_check.py`는 Kotlin 문법·API 호환성·실제 의존성 존재 여부를 검증하지 않습니다. 컴파일 오류와 누락 의존성 여부가 확인된 것으로 오해하지 마세요. 빌드 가능한 환경에서 먼저 Gradle Sync, JVM 테스트, APK 빌드, lint를 실행하고 발생하는 오류를 수정해야 합니다. 최신 안정 버전 조합 확인도 남아 있습니다.

## 작성한 자동화 테스트

`CoreTest.kt` (JVM):

- 550개 더미 문서의 무손실 그룹화, 순서 유지, 두 가지 크기 제한
- 제외 파일 처리, 파일 단위 유지, MIME 오버헤드에 따른 추가 그룹
- 단일 초과 파일 거절, 승인 확장자·한글 이름, 근사 묶음 효과
- ZIP 경로 탈출·절대/Windows 경로 차단
- 내용 동일/변경 SHA-256, CSV 따옴표·수식 삽입 방어
- DATA 이후 단절/취소의 불확실 판정, 명시적 거부/전송 이전 취소 구분

`ProcessingDeviceTest.kt` (Android instrumentation):

- 크기가 서로 다른 3페이지 더미 PDF 생성 및 1MB 분할
- 단일 초과 페이지 제외 상태, 분할 크기·페이지 범위·독립 PDF 열기
- 원본 SHA-256 불변, ZIP 경로 탈출을 해제 전에 거절

실행:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew connectedDebugAndroidTest
```

## 출시 전 실기기 수동 테스트 (모두 미실행)

기관 자료가 아닌 더미 파일과 전용 테스트 메일 계정만 사용하세요. 최소 한 대의 삼성 갤럭시와 대상 Android 버전에서 수행합니다.

| 상황 | 절차와 합격 기준 |
|---|---|
| 500개 이상 | 작은 더미 문서 550개 폴더 선택. UI 스크롤/취소 반응, 수/용량/해시/묶음 정확성, ANR 없음 |
| 100MB 이상 PDF | 이미지·공유 폰트/리소스·불균등 페이지의 100~300MB PDF로 측정. OOM/ANR 없음, 각 분할본 실제 크기/독립 열기/원본 해시/페이지 순서 확인. 메모리 프로파일 기록 |
| 중복 선택 | 이름이 다르지만 내용 동일한 파일/동일 폴더 재선택. 한 번만 목록에 남음 |
| 한글·특수문자 | 한글·공백·괄호·쉼표·따옴표 파일명, ZIP 하위 경로. 미리보기/첨부 수신 파일명/CSV 확인 |
| 악성 ZIP | `../`, 절대/Windows 경로, 심볼릭 링크, 중복 경로, 5,000개 초과, 고압축비·크기 메타 위조/CRC 손상. 거절/부분 추출 폴더 정리/원본 불변 |
| 비밀번호 ZIP | 목록 단계에서 안내하고 추출 중단 |
| 단일 초과 PDF 페이지 | 1페이지가 9MB를 넘는 PDF. 오류로 전체 폐기하지 않고 별도 처리 필요·선택 제외 |
| 암호화/손상 PDF | 사용자 암호·권한 제한·손상 문서. 설명 표시, 부분 산출물 정리, 원본 불변 |
| 서명 PDF | 분할 전 경고 표시. 원본 서명 유효성 불변, 분할본 서명 보존을 주장하지 않음 |
| 잘못된 비밀번호·인증 거부 | 일반 비밀번호/잘못된 앱 비밀번호/SMTP 미활성화. 인증 안내 표시, 뒤 메일 중단, 비밀번호 로그/CSV/DB 노출 없음 |
| 네트워크 끊김 (접속 전) | 연결 실패 상태, 명시적 재시도만. 짧은 간격 연속 재시도 금지 |
| 네트워크 끊김 (DATA 이후) | 더미 SMTP fault injection/테스트 네트워크로 최종 250 응답 유실 재현. `UNCERTAIN`, 자동 재발송 0회, 사용자 확인 필수 |
| 최종 250 직후 프로세스 종료 | 이미 `ACCEPTED`면 재발송 안 함. DB 기록 전 중단이면 불확실로 보류 |
| 백그라운드 | 홈/잠금/배터리 제한/Android 15 dataSync 제한 확인. 알림·상태·OS 중단 후 복원 점검 |
| 취소 | 대기/연결/전송 단계 각각 취소. 후속 첨부 중단, 전송 단계 불확실 표시 가능, 접수된 메일은 회수되지 않음 |
| 작업 간 독립성 | 두 작업 등록 후 하나 취소. 다른 작업이 취소되지 않고 단일 SMTP 전송만 실행 |
| 앱 재시작/강제중지 | 선택 복원, WorkManager/Room 상태 확인. 남은 전송 상태 확인으로 불확실 전환, 성공 행 자동 재전송 없음 |
| 저장공간 부족 | 기기 저장공간 제한 후 가져오기/ZIP/PDF/MIME/DB 기록 단계. 사용자 오류 표시, 원본 무변경, 재발송 보수적 처리 |
| 실제 MIME 제한 | 긴 한글 파일명·다수 첨부, 임계값 바로 아래/위. 생성 .eml 크기와 설정 비교, 초과 승인 금지 |
| 서버 빈도/용량 제한 | 네이버/기관 공식 승인 기준으로 시험. 명시적 4xx/5xx 시 중단, 자동 반복 전송 없음 |
| 최종 수신 확인 | 전용 테스트 수신함에서 순번·첨부·파일명·SHA-256 일치 확인. 서버 접수와 최종 수신을 별도 기록 |
| CSV/임시파일 | 수신자·제목·첨부·시각·상태·응답 대조. 수식 주입 방어. 미완료 자료 삭제 차단, 완료 삭제 후 원본/이력 보존 |

테스트 계정이 연결되기 전에는 실제 이메일을 발송하지 않습니다. SMTP 서버에서 전송한 메일이 보낸메일함에 자동 저장된다고 가정하지 않습니다. 불확실 상태의 해제에는 서버/관리자 확인 등 별도 증거가 필요합니다.


## 로컬 개발 재개 결과 (2026-10-19 추가)

GitHub 도구 없이 기존 `/workspace/settlement-mail` 파일을 직접 읽고 수정했습니다. 파일 접근·쓰기·셸 실행 권한은 정상입니다.

수정 사항:

- CSV에서 실제 첨부 파일명과 원본 경로를 별도 열로 기록합니다. 분할 PDF가 원본 이름으로 잘못 기록되던 문제를 수정했습니다.
- ZIP 경로는 정규화한 실제 대상 경로를 기준으로 중복 검사합니다 (`nested/../same.pdf`와 `same.pdf` 충돌 차단).
- 목표 크기 이하 PDF의 불필요한 재분할을 막고, 단일 초과 페이지가 중복 해시로 제거되더라도 원본 행에 별도 처리 필요 상태를 보존합니다.
- 임시파일 삭제 후 PDF scratch 디렉터리를 다시 생성합니다.
- 선택 목록은 AtomicFile로 저장하고, 가져오기·해제·분할 결과는 목록 저장 성공 후 공개합니다. 저장 실패 시 이전 목록을 보존하고 새 파일을 정리합니다.
- 직접 사용하는 AndroidX Core 의존성을 명시했습니다.
- 저장소 루트용 `.github/workflows/android.yml`을 추가했습니다. 로컬에만 작성했으며 GitHub 업로드/실행은 하지 않았습니다.

실행 검증:

- `python3 -m unittest discover -s scripts -p 'test_*.py' -v`: **10개 통과** (`local-tests.log`). Kotlin의 `Store.kt`에서 실제 `@Query` SQL을 추출하여 로컬 SQLite에서 상태 전이를 검증합니다. 성공 행 재시도 차단, 불확실 행 재시도 차단, 동일 메일 이중 획득 차단(별도 DB 연결 포함), 작업별 취소, 중단 복구, 접수 응답 기록을 확인했습니다.
- `python3 scripts/static_check.py`: 통과.
- `sh -n gradlew`: 통과.
- 전체 Android 빌드 재시도: Gradle 다운로드 단계의 `proxy:8080` 연결 실패로 중단 (`build-attempt-latest.log`).

**SQLite 테스트 통과는 Kotlin 컴파일·Room 코드 생성·Android 동작 검증을 대신하지 않습니다. Kotlin/JVM 테스트와 instrumentation 테스트는 여전히 미실행이며 APK는 없습니다.**


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
