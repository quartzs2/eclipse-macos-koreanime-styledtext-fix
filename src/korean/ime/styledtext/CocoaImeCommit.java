package korean.ime.styledtext;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.internal.cocoa.NSString;
import org.eclipse.swt.internal.cocoa.OS;

/**
 * SWT 내부 Cocoa 호출을 분리한 브리지입니다. 호출 전에 검증된 SWT 버전과 조합 문자를 확인해야 합니다.
 */
final class CocoaImeCommit {
    private CocoaImeCommit() {}

    static boolean finish(StyledText text, String preedit) {
        if (text.view == null || text.view.id == 0) {
            return false;
        }

        long view = text.view.id;
        long inputContext = OS.sel_registerName("inputContext");
        long discard = OS.sel_registerName("discardMarkedText");
        if (!OS.objc_msgSend_bool(view, OS.sel_respondsToSelector_, inputContext)) {
            return false;
        }

        long context = OS.objc_msgSend(view, inputContext);
        if (context == 0 || !OS.objc_msgSend_bool(context, OS.sel_respondsToSelector_, discard)) {
            return false;
        }

        // Canvas.insertText → IME.insertText 호출로 SWT의 조합 범위와 스타일을 초기화합니다.
        // 소유 편집기의 필터는 표시 중인 문자를 보존하고, 뒤따르는 네이티브 키의 중복 삽입을 막습니다.
        OS.objc_msgSend(view, OS.sel_insertText_, NSString.stringWith(preedit).id);
        if (text.getIME().getCompositionOffset() != -1) {
            return false;
        }

        // Apple API의 요구에 따라 클라이언트의 조합 범위를 먼저 해제한 뒤 입력 컨텍스트를 정리합니다.
        OS.objc_msgSend(context, discard);
        return true;
    }
}
