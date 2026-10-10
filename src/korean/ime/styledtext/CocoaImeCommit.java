package korean.ime.styledtext;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.internal.cocoa.NSString;
import org.eclipse.swt.internal.cocoa.OS;

/** Isolated SWT Cocoa bridge; caller checks the tested SWT version and preedit. */
final class CocoaImeCommit {
    private CocoaImeCommit() { }
    static boolean finish(StyledText text, String preedit) {
        if (text.view == null || text.view.id == 0) return false;
        long view = text.view.id;
        long inputContext = OS.sel_registerName("inputContext");
        long discard = OS.sel_registerName("discardMarkedText");
        if (!OS.objc_msgSend_bool(view, OS.sel_respondsToSelector_, inputContext)) return false;
        long context = OS.objc_msgSend(view, inputContext);
        if (context == 0 || !OS.objc_msgSend_bool(context, OS.sel_respondsToSelector_, discard)) return false;
        // Canvas.insertText -> IME.insertText clears SWT's marked range and
        // styles. The owner's filter preserves the already displayed text
        // and suppresses the subsequent native duplicate key insertion.
        OS.objc_msgSend(view, OS.sel_insertText_, NSString.stringWith(preedit).id);
        if (text.getIME().getCompositionOffset() != -1) return false;
        // Apple's API asks the client to clear its marked range first.
        OS.objc_msgSend(context, discard);
        return true;
    }
}
