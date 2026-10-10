# 1.0.0 검증 기록

검증일: 2026-10-10 (Asia/Seoul).

## 검증 환경

- macOS 27.0.1 (26A434), Apple Silicon.
- JDK: Temurin 21.0.11. Java 21을 대상으로 컴파일합니다 (`--release 21`).
- Eclipse JEE 4.41.0, SWT 3.135.0 (`SWT.getVersion()` 4974), JFace Text 3.31.100.

## 이번 배포의 검사 결과

- JDK 21.0.11의 `javac --release 21 -Xlint:all,-path` 빌드 통과.
- 최종 빌드의 SWT/JFace 합성 IME 통합 검사 **40개 모두 통과**.
- JAR ZIP 무결성, 내부 `Bundle-Version: 1.0.0`, Java 21 클래스 형식(major version 65), MIT LICENSE 포함 검사 통과.
- 배포 소스와 문서에서 이전 릴리스 번호가 남지 않았음을 확인.
- 소스 ZIP 무결성 및 패키지 내부 파일과 배포 소스의 바이트 동일성 확인.
- 최종 자동 검사 원문은 `TEST_RESULTS.txt`, 배포 파일 SHA-256은 `dist/SHA256SUMS.txt`에 기록.

## 추가 환경에서 발견한 제한

JDK 27에서 자동 검사를 실행하면 `smartEnter repairs before existing command`가 `expected 가나다 but got 가나`로 실패했습니다. 네이티브 라이브러리 접근을 명시한 재검사에서도 같은 실패가 재현됐습니다. JDK 21에서는 40개 검사를 모두 통과했습니다. JDK 27의 실패 원인은 아직 확정하지 않았으며, 전체 호환성을 확인한 환경으로 표기하지 않습니다.

샌드박스 안에서 처음 실행한 검사는 macOS 네이티브 초기화 중 종료됐습니다. 위의 런타임별 결과는 샌드박스 밖에서 실행한 SWT 검사 결과입니다.

## 실제 키보드 확인

이번 1.0.0 패키지에 대한 실제 macOS 한국어 키보드 입력과 Eclipse 설치·재실행 검사는 아직 수행하지 않았습니다. README의 임시 파일 확인 항목을 실제 키보드로 확인해야 합니다.

자동 검사는 테스트 코드에서 IME 내부 상태를 구성하고 SWT 이벤트를 전달합니다. 실제 macOS 입력기의 네이티브 이벤트 순서 전체를 재현한다고 보장하지 않습니다. 운영 코드의 Cocoa 조합 종료 기능은 SWT 4974와 조합 문자·위치 일치 조건으로 제한됩니다.

## 자동 검사 범위

포커스 상실과 창 비활성화 시 조합 종료 및 후속 입력, 한 번만 복원, 정상 재삽입 중복 방지, 네 방향키, Enter/LF/한글을 전달하는 Enter, 외부 창 복귀 후 Enter, smartEnter 명령, 단독 Shift, 포커스 유지, 정상·조합 Backspace 구분, 의도적 삭제와 Escape 취소, 늦은 IME 이벤트의 원래 편집기 귀속, 무관한 문서 수정, Verify 거절·변형, 문서 교체와 편집기 종료, 읽기 전용·블록·범위 선택, CRLF, 지연 MouseUp과 새 입력·커서 무효화, 부분 표시 좌표, 다른 위젯 입력, 중복 설치, 문서 Undo/Redo 왕복, 일반 StyledText 방향키, 리스너 해제.

자동 Undo/Redo 검사는 문서 수준의 왕복 검사입니다. Eclipse의 실제 ⌘Z 동작 전체가 해결됐다는 뜻은 아닙니다.
