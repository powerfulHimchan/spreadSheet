# 실제 Google 스프레드시트 연결

앱 패키지명: `com.powerfulhimchan.familyledger`

## Google Cloud

1. https://console.cloud.google.com/ 에서 새 프로젝트를 만들거나 기존 프로젝트를 선택합니다.
2. API 라이브러리에서 **Google Sheets API**를 활성화합니다.
3. Google Auth Platform의 Branding/Audience/Data Access를 설정합니다. 테스트 단계에서는 사용할 가족 계정을 테스트 사용자로 등록합니다.
4. 요청 범위는 `https://www.googleapis.com/auth/spreadsheets`입니다. 시트 원본에 쓰기 위한 권한이며 선택한 시트의 공유 권한도 필요합니다.
5. Clients에서 **Android OAuth 클라이언트**를 만들고 패키지명과 설치할 APK의 서명 SHA-1을 등록합니다.
6. APK를 설치하여 URL 연결→탭 선택→표 매핑까지 확인합니다.
7. 실제 저장 검증은 먼저 원본을 복제한 테스트 시트로 진행합니다. ID 열 추가·수정·삭제 플래그·수식 보호·다른 기기의 변경 감지를 확인합니다.

이 앱은 서버 인증 코드나 서버 refresh token을 사용하지 않습니다. 따라서 앱에 Web OAuth client secret, 서비스 계정 JSON, API 비밀키를 넣지 않습니다. Android OAuth 클라이언트는 앱 패키지명과 서명 인증서로 식별됩니다.

## SHA-1 확인

현재 빌드의 값은 `docs/VERIFICATION.md`를 확인하세요. 다른 키로 빌드하면 값이 달라집니다.

```sh
./gradlew :app:signingReport
```

초기 프로젝트의 debug 서명과 정식 release 서명을 혼용하지 마세요. 정식 서명 키는 소유자가 안전하게 관리해야 합니다. 발급된 키·비밀번호·토큰을 Git에 올리지 않습니다.

## 인증 정책

테스트/게시 상태와 검증 정책은 Google 프로젝트 및 요청 범위에 따라 적용됩니다. 서버용 refresh token의 테스트 모드 만료 규칙을 Android AuthorizationClient의 동작과 동일하게 가정하지 않습니다. 이 구현은 사용자 동작 시 authorize()로 단기 access token을 다시 받아 사용합니다.

## 공식 구현 참고

- https://developer.android.com/identity/authorization
- https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets/get
- https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets/cells
- https://developers.google.com/workspace/sheets/api/guides/batchupdate
- https://developer.android.com/build/releases/agp-8-9-0-release-notes
