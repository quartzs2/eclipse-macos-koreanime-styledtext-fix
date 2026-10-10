# Eclipse macOS 한글 입력 보정 플러그인

**버전: 1.0.0**

macOS의 Eclipse 편집기에서 한글을 입력할 때 조합 중인 글자가 사라지거나 앞 글자까지 지워지는 문제를 줄이는 플러그인입니다.

## 해결하는 문제

- 조합 중 **Backspace**를 누르면 앞에 확정된 글자까지 지워지는 문제.
- 조합 중 **방향키**를 누르거나 **마우스로 클릭**하면 마지막 글자가 사라지는 문제.
- 줄 끝에서 **→**를 누르면 커서가 예상보다 한 칸 더 이동하는 문제.
- 조합 중 **다른 앱의 창을 클릭**한 뒤 돌아와 입력하면 마지막 글자가 사라지는 문제.
- 조합 중 **Enter / Shift+Enter**를 누르면 마지막 글자가 사라지는 문제.

플러그인은 중복 삭제를 막고, 실제로 사라진 한글 한 음절을 확인해 복원합니다. 다른 앱으로 이동할 때는 지원하는 SWT 환경에서 조합을 끝내고 글자를 남겨, 돌아와 이어서 입력할 수 있게 합니다. 줄바꿈은 Eclipse의 원래 동작을 사용합니다.

## 사용 환경과 한계

- **macOS용**입니다. Windows와 Linux는 지원하지 않습니다.
- Eclipse를 실행하는 Java는 **21 이상**이어야 합니다.
- 빌드와 자동 검사에 통과한 **JDK 21을 권장**합니다. JDK 27에서는 명령 실행 전 복원 검사 실패가 재현돼 전체 호환성을 확인하지 못했습니다.
- 확인 환경: **Eclipse JEE 4.41.0**, **SWT 3.135.0** (`SWT.getVersion()` 4974), **macOS 27.0.1** (26A434), Apple Silicon, 한국어 두벌식.
- 다른 앱으로 이동할 때 조합을 끝내는 기능은 확인한 SWT 버전 **4974**에서만 동작합니다. 다른 Eclipse/SWT 환경의 호환성은 별도 확인이 필요합니다.
- 범위 선택, 블록 선택, 다중 선택, 읽기 전용 편집기에는 복원을 제한합니다.
- **실행 취소(⌘Z) 오류는 해결하지 않습니다.**
- 기본 설정에서는 진단을 출력하지 않으며, 입력 내용을 파일이나 네트워크에 기록하지 않습니다.

검증 범위와 결과는 [VALIDATION.md](VALIDATION.md)를 참고하세요.

## 설치하기

1. GitHub 저장소의 **Releases**에서 `eclipse.macos.koreanime.styledtext.fix_1.0.0.jar`를 내려받습니다. 소스에서 직접 빌드한 경우 `dist/`에 같은 파일이 생성됩니다.
2. Eclipse를 종료합니다.
3. 설치할 Eclipse의 `dropins` 폴더에 JAR를 넣습니다. macOS Eclipse JEE의 예시는 `/Applications/Eclipse JEE.app/Contents/Eclipse/dropins/`입니다. 설치 위치나 앱 이름이 다르면 경로를 맞춰 주세요.
4. 같은 입력 오류를 고치는 플러그인이 중복 실행되지 않게 확인하고 Eclipse를 다시 실행합니다.
5. 임시 파일에서 아래 항목을 확인합니다.

## 임시 파일에서 확인하기

1. `가나다`를 입력하면서 마지막 **다**가 조합 중일 때 Backspace를 눌러 봅니다. 앞의 확정된 글자가 함께 지워지지 않아야 합니다.
2. 같은 상태에서 ←·→·↑·↓를 각각 눌러 봅니다. **다**가 남고 커서가 의도한 곳으로 이동해야 합니다. 줄 끝에서 →를 한 번 눌렀을 때 다음 줄로 과하게 넘어가지 않는지도 확인합니다.
3. 조합 중인 **다**를 두고 편집기 안의 다른 위치와 편집기 밖의 Eclipse 영역을 각각 클릭해 봅니다. 글자가 남고 커서 위치가 자연스러워야 합니다.
4. 조합 중인 **다**를 두고 다른 앱의 창을 클릭한 뒤 Eclipse로 돌아옵니다. 파란 조합 커서가 남지 않고, 다른 글자를 입력하거나 Enter를 눌러도 **다**가 유지돼야 합니다.
5. 조합 중 **Enter / Shift+Enter**를 각각 눌러 봅니다. 마지막 글자가 남고 원래대로 줄바꿈이 수행돼야 합니다.
6. 일반적인 한글·영어 입력과 삭제, 저장 후 다시 열기도 확인합니다.

## 소스에서 빌드하기

macOS에 **Python 3**, **JDK 21 이상**, **Eclipse**가 필요합니다. `java`, `javac`, `jar` 명령이 PATH에서 실행되어야 합니다. 별도로 Eclipse 라이브러리를 내려받지 않고 설치된 Eclipse의 `plugins` 폴더를 사용합니다.

```bash
PLUGIN_JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$PLUGIN_JAVA_HOME/bin:$PATH"
python3 build.py
python3 test.py
```

Eclipse 설치 경로가 다르면 두 명령에 같은 경로를 지정합니다.

```bash
python3 build.py --eclipse "/Applications/Eclipse.app/Contents/Eclipse"
python3 test.py --eclipse "/Applications/Eclipse.app/Contents/Eclipse"
```

빌드 결과는 `dist/eclipse.macos.koreanime.styledtext.fix_1.0.0.jar`입니다. 테스트는 별도 SWT 창을 열어 합성 IME 프로토콜을 검사합니다. 실제 키보드 검사는 위의 항목을 직접 확인해야 합니다.

## 제거하기

Eclipse를 종료하고 `dropins`의 `eclipse.macos.koreanime.styledtext.fix_1.0.0.jar`를 제거한 뒤 다시 실행합니다.

## 라이선스

[MIT License](LICENSE). 저작권 및 라이선스 고지를 유지하면 사용, 수정, 재배포, 상업적 이용이 가능합니다. 전문은 `LICENSE`에 있습니다.
