# 정산자료 전송 도우미 · Windows

Windows 10/11 x64용 독립 실행형 앱입니다. ZIP을 풀고 `SettlementMail.exe`를 실행합니다. .NET을 별도로 설치할 필요가 없도록 빌드합니다. 관리자 권한은 필요하지 않습니다.

1. **설정·이력:** 네이버 계정과 애플리케이션 비밀번호를 저장하고 SMTP 연결 테스트를 합니다. 네이버 2단계 인증·IMAP/SMTP 사용 설정이 필요합니다.
2. **파일 정리:** 파일·폴더를 추가하거나 창으로 끌어 놓습니다. `미승인 형식 선택 해제`로 전송에서 일괄 제외합니다. 파일은 삭제하지 않습니다.
3. **파일 처리:** ZIP 목록을 확인한 뒤 승인된 문서만 해제합니다. 큰 PDF를 1~9MB 기준으로 분할합니다. 서명·인증정보 보존을 보장하지 않으며 원본은 변경하지 않습니다.
4. **메일 구성:** 수신자·제목·첨부 목록을 확인하고 최종 승인해야 발송합니다.
5. **발송 현황:** 실패·취소된 건만 수동 재시도합니다. 불확실 상태는 서버/관리자 확인 전 재발송하지 않습니다. SMTP 접수 성공은 최종 수신·다운로드 성공이 아닙니다.

최소화해도 발송은 계속됩니다. 창을 닫으면 진행 중 작업을 취소하고 상태를 저장한 뒤 다시 닫도록 안내합니다. 강제 종료 후에는 전송 중 상태를 불확실로 복구하며 자동 발송하지 않습니다.

데이터는 `%LOCALAPPDATA%\SettlementMailWindows`에 저장합니다. 비밀번호는 DPAPI(CurrentUser)로 암호화합니다. SMTP 인증정보를 CSV·로그·배포 파일에 기록하지 않습니다. 문서 복사본·MIME·이력 DB는 현재 사용자의 로컬 폴더에 보관하며 별도 파일 암호화는 없습니다. Android와 계정·이력을 자동 동기화하지 않습니다.

첨부 9MB·개별 9MB·메시지 13MB·간격 30초가 기본값입니다. 발송 간격은 최소 10초까지 설정할 수 있습니다. 저장한 기존 설정과 이미 승인된 발송 작업은 기존 간격을 유지합니다. 네이버가 보장하는 안전 간격은 아닙니다. 기관 승인값이 아닙니다. 원본 단위 그룹화와 실제 MIME 크기 검증을 모두 수행합니다. PDF 외 문서는 분할하거나 확장자를 바꾸지 않습니다. 단일 수신자와 @naver.com 개인 계정만 지원합니다.

가져오기 파일당 2GB, 폴더 깊이 32/항목 10,000, ZIP 항목 5,000/개별 500MB/전체 1.5GB/압축률 200배, PDF 입력 500MB의 안전 한도를 적용합니다. PDFsharp는 객체를 메모리로 읽으므로 임의 크기 문서의 처리 성공을 보장하지 않습니다. 대형 문서는 별도 메모리/기기 검증이 필요합니다.

개인용 미서명 EXE이므로 Windows가 게시자를 확인할 수 없다는 경고를 표시할 수 있습니다. 실제 업무자료 대신 더미 파일·테스트 계정으로 먼저 시험하세요.

## 개발·빌드

.NET 10 SDK / WPF, MailKit 4.18.1(MIT), PDFsharp 6.2.4(MIT), SharpZipLib 1.4.2(MIT), Microsoft.Data.Sqlite.Core·ProtectedData·Pkcs 10.0.13, SQLitePCLRaw 3.0.5 사용. PDFsharp는 MIT 라이선스가 확인된 6.2.4로 고정합니다. Windows 기본 대화상자·고해상도 UI와 DPAPI를 활용하기 위해 C#/.NET을 선택했습니다. 외부 서버·광고·분석 SDK는 없습니다.

```powershell
dotnet test Windows/SettlementMail.Tests -c Release
dotnet publish Windows/SettlementMail.App -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o windows-publish
# 더미자료만 사용하는 GUI/독립 실행형 EXE 검사
Start-Process ./windows-publish/SettlementMail.exe -ArgumentList '--smoke-test','windows-smoke' -Wait
```

GitHub Actions의 `Windows build`가 컴파일·자동화 테스트·배포 EXE 실행 검사 후에만 Release ZIP을 만듭니다. 화면 캡처·테스트 보고서는 같은 실행의 `windows-diagnostics`에 보관합니다. 실제 네이버 SMTP 인증/전송·스마트스크린 설치 경험·100MB 이상 PDF 성능은 사용자 PC에서 별도 검증해야 합니다.

공식 자료: [네이버 SMTP 설정](https://help.naver.com/service/30029/contents/21344), [PDFsharp](https://docs.pdfsharp.net/), [MailKit](https://www.nuget.org/packages/MailKit), [.NET 단일 파일 배포](https://learn.microsoft.com/en-us/dotnet/core/deploying/single-file/overview).
