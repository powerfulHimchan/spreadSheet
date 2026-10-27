# 테스트 빌드 서명

이 폴더의 `preview.keystore`는 현재 제공한 **테스트 APK의 디버그 서명을 재현하기 위한 키**입니다. Android의 기본 디버그 비밀번호 `android`와 별칭 `androiddebugkey`를 사용합니다. 실제 서비스용 비밀키가 아니며, release 서명에 사용하지 않습니다.

이 파일은 `.gitignore`로 Git 업로드에서 제외됩니다. 소스 ZIP을 보관하면 후속 테스트 APK도 같은 SHA-1로 빌드하고 설치할 수 있습니다. 이 파일 없이 빌드하면 개발 환경의 기본 debug 키가 사용되어 SHA-1이 달라질 수 있습니다.

실사용 release APK는 소유자가 따로 생성·보관한 서명 키를 사용하고 그 인증서를 Google Cloud에 등록해야 합니다.
