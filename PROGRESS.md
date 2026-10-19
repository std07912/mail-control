# 진행 현황

2026-10-19 현재. 아래 '소스 작성'은 컴파일·기기 검증 완료를 뜻하지 않습니다.

| 단계 | 상태 |
|---|---|
| 1 Android 프로젝트 구성 | 소스/Gradle 설정 작성 |
| 2 파일 선택·목록·중복·정렬 | 소스 작성 |
| 3 ZIP 목록·안전 해제 | 소스 작성 |
| 4 PDF 실제 크기 기반 분할 | 소스 작성 |
| 5 첨부 그룹화·MIME 측정 | 소스 작성 |
| 6 네이버 정책 조사·연결 테스트 구현 | 조사 및 소스 작성, 실제 인증 미검증 |
| 7 승인·순차 발송·취소·재시도 | 소스 작성, 실제 발송 미검증 |
| 8 이력·오류·불확실 상태·CSV | 소스 작성 |
| 9 한국어 5개 화면 | 소스 작성, 화면 실행 미검증 |
| 10 테스트·APK | 자동화 테스트 코드 작성. 정적 검사 통과. Gradle 다운로드 실패로 컴파일/테스트 실행/APK 생성 차단 |

남은 핵심 작업: 인터넷이 되는 Android Studio 환경에서 의존성 확인·컴파일 오류 수정·자동화 테스트 실행·더미자료 실기기 검증·테스트 계정 SMTP 검증·Debug APK 빌드. 자세한 재현 절차는 README.md, VERIFICATION.md에 있습니다.


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


## v0.2.0 업데이트

미승인 형식 일괄 선택 해제와 UI 개편을 완료했습니다. 하단 5개 메뉴, 문서 현황 카드, 파일 상태 배지·필터, 계정 저장 표시, 접을 수 있는 세부 설정, 작업별 발송 상태를 제공합니다. 컴파일·단위 테스트·lint·API 29 에뮬레이터 테스트가 통과했습니다. 최신 검증은 VERIFICATION.md의 v0.2.0 절을 참조하세요.
