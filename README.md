# 정산자료 전송 도우미

문서를 정리하고 네이버 메일로 나누어 보내는 개인용 Android·Windows 앱입니다.

## 다운로드

**[Android APK 다운로드](https://github.com/std07912/mail-control/releases/download/v0.2.2-debug/app-debug.apk)**

**[Windows ZIP 다운로드](https://github.com/std07912/mail-control/releases/download/windows-v0.1.1/SettlementMail-Windows-x64.zip)** · Windows 10/11 x64에서 압축을 풀고 `SettlementMail.exe`를 실행하세요. [PC 사용 설명서](Windows/README.md)

Android 8.0 이상에서 다운로드한 APK를 눌러 설치하세요. 압축 해제는 필요 없습니다.

**v0.2.1 이하 사용자는:** 서명키 보존 오류 때문에 기존 앱에 업데이트 설치할 수 없습니다. 필요한 이력을 CSV로 내보낸 뒤 기존 앱을 삭제하고 설치하세요. 계정·앱 내부 이력이 초기화됩니다. v0.2.2부터 실제 서명키를 명시적으로 저장하며, 게시 후 키 복원 실패 시 새 키를 자동 생성하지 않고 빌드를 중단합니다.

발송 간격은 **기본 30초·최소 10초**입니다. 기존 저장값은 유지되므로 필요하면 설정에서 변경하세요.

## 사용 방법

1. **설정·이력:** 네이버 이메일과 애플리케이션 비밀번호를 보안 저장하고 SMTP 연결을 테스트합니다. 비밀번호는 처음 한 번 저장하면 됩니다.
2. **파일 선택·처리:** 문서를 추가하고 필요한 ZIP 해제·PDF 분할을 진행합니다. 미승인 파일은 `미승인 형식 선택 해제`로 한 번에 제외합니다.
3. **메일 구성:** 수신자·제목·첨부 목록을 확인한 뒤 최종 승인해 발송합니다.

네이버의 2단계 인증과 IMAP/SMTP 사용 설정이 필요합니다. 첨부 형식·용량은 수신기관의 승인 기준을 따르세요.

테스트용 Debug 버전입니다. 컴파일·단위 테스트·lint·Android 에뮬레이터 테스트를 통과했으며, 더미 파일로 먼저 확인하세요. SMTP 접수 성공은 최종 수신을 보장하지 않습니다.

[사용 설명서](USER_GUIDE.md) · [개발·빌드 안내](DEVELOPMENT.md) · [검증 보고서](VERIFICATION.md)
