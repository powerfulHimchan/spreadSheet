# 0.1.0 검증 기록

검증일: 2026-10-27 (한국 시간)

| 항목 | 결과 |
|---|---|
| 최신 소스 clean Android 빌드 | 통과 |
| 핵심 로직 단위 테스트 | 24개 통과, 실패 0, 오류 0 |
| Android lint | 오류 0, 경고 1개 |
| APK 서명 검사 | 통과, APK Signature Scheme v2 |
| 앱 패키지 | com.powerfulhimchan.familyledger |
| 버전 | 0.1.0 (versionCode 1) |
| Android 최소 / target | API 26 / 35 |
| Android 10 소프트웨어 에뮬레이터 | 부팅 확인. APK 설치가 120초 제한을 초과하여 화면·입력 흐름 테스트는 미완료 |
| 실제 Android 기기 | 사용자 설치 후 확인 필요 |
| 실제 Google 인증·시트 읽기·쓰기 | 소유자의 Google Cloud 설정 후 검증 필요 |

## 확인한 핵심 동작

- 헤더가 첫 행에 있지 않은 표와 주변의 별도 표를 구분
- 월별 탭+일자, 전체 날짜, 날짜 시리얼, 윤년과 유효하지 않은 날짜
- 정확한 금액 합계, 환불·수입 분리, 숫자 표기 로케일
- 숫자형 드롭다운 값의 타입 유지와 선택지 검증
- 숫자처럼 보이는 식별자의 앞자리 0 유지, 명시적 텍스트 서식 우선
- 수식 열 보호 및 수식이 미리 채워진 빈 행에 거래 추가
- 행 순서 변경 후 ID로 대상 재식별
- 외부 수정·중복 ID·헤더 변경·불완전 조회 시 쓰기 차단
- 거래 삭제 시 행 전체를 삭제하지 않고 상태 셀만 변경
- 문자열 수식 주입 방지와 Google Sheets에 저장 가능한 숫자 정밀도 제한

## 정적 검사 경고

KaptUsageInsteadOfKsp

KAPT를 KSP로 바꾸면 빌드 속도를 개선할 수 있다는 권고입니다. 컴파일 오류나 앱 기능 오류가 아닙니다.

## OAuth Android 클라이언트 등록 정보

패키지명: `com.powerfulhimchan.familyledger`

서명 SHA-1:

```
B7:DF:ED:B7:07:F7:28:DA:71:14:99:06:35:03:B2:DA:9B:3C:9F:30
```

이 값은 최초 preview APK 및 소스 ZIP에 함께 제공한 테스트용 debug 키에 해당합니다. 서명 키는 Git 저장소에 포함하지 않습니다. 저장소를 새로 받아 빌드하면 개발 환경의 기본 debug 키가 사용되므로 `./gradlew :app:signingReport`로 실제 SHA-1을 확인하여 Google Cloud에 등록해야 합니다.

## Git 저장소 반영 시 재확인

기존 단위 테스트 XML(24개 통과), lint XML(오류 0개, 경고 1개), APK의 크기·SHA-256이 위 기록과 일치함을 확인했습니다. 소스 수정 시각은 기존 테스트 실행 전입니다.

현재 작업 환경에는 이전 Android SDK와 Gradle 캐시가 남아 있지 않고 네트워크 제한으로 Gradle 배포본 다운로드도 실패하여, 이번 반영 시 로컬 재빌드는 완료하지 못했습니다. `.github/workflows/android.yml`에서 JDK 17로 핵심 테스트·lint·debug APK 빌드를 실행하도록 구성했습니다. GitHub 실행 결과는 Actions에서 별도로 확인할 수 있습니다. CI의 기본 debug 키는 최초 preview APK의 키와 다를 수 있습니다.

## 배포 파일 무결성

APK 파일 크기: 17,435,089 bytes

APK SHA-256:

```
96df253c7d8bc65867f312c8c3fe404d0a5bb93331a9441044b173cc1719ee4d
```

## 초기 GitHub 검사 실패 수정

최초 실행은 `android-actions/setup-android@v3`의 기본 패키지 `tools`를 설치하려다 `Failed to find package 'tools'`로 중단되었습니다. 앱 컴파일·테스트 이전의 SDK 준비 오류입니다. CI에서 `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`만 설치하도록 명시했습니다. 후속 검사의 최종 결과는 해당 커밋의 Actions 실행에서 확인합니다.

## 남은 확인

Google Cloud 프로젝트에서 Sheets API와 Android OAuth 클라이언트를 설정한 후, 원본의 복사본으로 로그인·조회·매핑·ID 설정·추가·수정·삭제·다른 기기와의 충돌을 확인해야 합니다. 현재 제공 파일은 초기 preview 빌드입니다. 생체 인증, 가족 공통 매핑 저장, 강한 동시성 보장 등의 후속 범위는 README의 제한 사항에 기재했습니다.
