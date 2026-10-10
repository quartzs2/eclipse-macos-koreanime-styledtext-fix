package korean.ime.styledtext;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import org.eclipse.core.commands.*;
import org.eclipse.jface.text.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.commands.ICommandService;

/** One-character Korean IME recovery. No file or network logging. */
public final class KoreanImeFix implements IStartup, AutoCloseable {
    private static final String INSTALL_KEY = KoreanImeFix.class.getName();
    private static final int[] FILTERS = {SWT.ImeComposition, SWT.KeyDown, SWT.MouseDown,
        SWT.MouseUp, SWT.FocusIn, SWT.FocusOut, SWT.Deactivate};
    private final Map<StyledText, EditorState> editors = new IdentityHashMap<>();
    private final Map<IME, EditorState> imes = new IdentityHashMap<>();
    private final ArrayDeque<String> diagnostics = new ArrayDeque<>();
    private final Listener filter = this::dispatch;
    private final boolean trace = Boolean.getBoolean("koreanime.fix.trace");
    private Display display;
    private ICommandService commands;
    private boolean closed;
    private long inputSerial;
    private final IExecutionListener commandListener = new IExecutionListener() {
        @Override public void preExecute(String id, ExecutionEvent event) {
            if (isSmartEnter(id)) guarded(() -> {
                EditorState s = focusedEditor();
                if (s != null) {
                    inputSerial++; s.receipt = null;
                    note("smart-enter"); recover(s, Move.AFTER, null);
                }
            });
        }
        @Override public void postExecuteSuccess(String id, Object result) { }
        @Override public void postExecuteFailure(String id, ExecutionException error) { }
        @Override public void notHandled(String id, NotHandledException error) { }
    };
    @Override public void earlyStartup() {
        Display d = PlatformUI.getWorkbench().getDisplay();
        d.asyncExec(() -> install(d, PlatformUI.getWorkbench().getService(ICommandService.class)));
    }
    // Package-visible entry points also used by the isolated integration tests.
    void install(Display d, ICommandService service) {
        if (closed || d.isDisposed() || !"cocoa".equals(SWT.getPlatform())
                || Boolean.getBoolean("koreanime.fix.disabled") || d.getData(INSTALL_KEY) != null) return;
        display = d; commands = service; d.setData(INSTALL_KEY, this);
        for (int kind : FILTERS) d.addFilter(kind, filter);
        if (commands != null) commands.addExecutionListener(commandListener);
        d.disposeExec(this::close);
        focusedEditor(); note("installed");
    }
    private void dispatch(Event e) {
        if (closed) return;
        guarded(() -> {
            switch (e.type) {
                case SWT.ImeComposition -> onIme(e);
                case SWT.FocusIn -> { if (e.widget instanceof StyledText st) state(st); }
                case SWT.KeyDown -> onKey(e);
                case SWT.MouseDown -> {
                    inputSerial++;
                    for (EditorState s : editors.values()) s.mouseDownSerial = inputSerial;
                }
                case SWT.MouseUp -> onMouse(e);
                case SWT.FocusOut -> {
                    if (e.widget instanceof StyledText st) {
                        EditorState s = state(st); finishComposition(s); queueRecovery(s);
                    }
                }
                case SWT.Deactivate -> {
                    if (e.widget instanceof Shell shell)
                        for (EditorState s : editors.values())
                            if (!s.widget.isDisposed() && s.widget.getShell() == shell) {
                                finishComposition(s); queueRecovery(s);
                            }
                }
                default -> { }
            }
        });
    }
    private EditorState focusedEditor() {
        return display != null && display.getFocusControl() instanceof StyledText st ? state(st) : null;
    }
    private EditorState state(StyledText st) {
        EditorState s = register(st);
        if (s != null) attachViewer(s);
        return s;
    }
    private EditorState register(StyledText st) {
        if (st.isDisposed()) return null;
        EditorState s = editors.get(st);
        if (s == null) {
            s = new EditorState(st); editors.put(st, s);
            if (st.getIME() != null) imes.put(st.getIME(), s);
            st.addListener(SWT.Dispose, s.disposeListener);
            st.addListener(SWT.Modify, s.modifyListener);
            st.addListener(SWT.Selection, s.selectionListener);
            st.addCaretListener(s.caretListener);
        }
        return s;
    }
    private void attachViewer(EditorState s) {
        if (s.viewer != null && s.viewer.getTextWidget() == s.widget) {
            bindViewer(s.widget, s.viewer);
            return;
        }
        if (!PlatformUI.isWorkbenchRunning()) return;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            for (IWorkbenchPage page : window.getPages())
                for (IEditorReference ref : page.getEditorReferences()) {
                    IEditorPart editor = ref.getEditor(false);
                    if (editor == null) continue;
                    ITextOperationTarget target = editor.getAdapter(ITextOperationTarget.class);
                    if (target instanceof ITextViewer viewer && viewer.getTextWidget() == s.widget) {
                        bindViewer(s.widget, viewer); return;
                    }
                }
    }
    void bindViewer(StyledText st, ITextViewer viewer) {
        EditorState s = register(st);
        if (s == null) return;
        IDocument doc = viewer.getDocument();
        if (s.viewer == viewer && s.document == doc) return;
        if (s.document != null) s.document.removeDocumentListener(s.documentListener);
        if (s.viewer != viewer) {
            if (s.viewer != null) s.viewer.removeTextInputListener(s.inputListener);
            viewer.addTextInputListener(s.inputListener);
        }
        s.candidate = null; s.viewer = viewer; s.document = doc; s.revision++;
        s.receipt = null;
        if (doc != null) doc.addDocumentListener(s.documentListener);
        note("document-bound");
    }
    private void onIme(Event e) {
        if (!(e.widget instanceof IME ime) || e.detail != SWT.COMPOSITION_CHANGED) return;
        EditorState s = imes.get(ime);
        if (s == null) {
            EditorState focused = focusedEditor();
            if (focused != null && focused.widget.getIME() == ime) s = focused;
        }
        if (s == null || !eligible(s.widget)) return;
        if (s.finishing && ime.getCommitCount() == 1 && e.text != null && e.text.equals(s.preedit)
                && e.start == s.preeditStart && e.end == e.start + 1) {
            // Let StyledText reset its composition caret, but remove zero
            // characters: the displayed preedit is already the desired text.
            // Returning false prevents Control from inserting the same text.
            e.end = e.start; e.doit = false; return;
        }
        attachViewer(s);
        note("ime commit=" + ime.getCommitCount() + " length=" + (e.text == null ? -1 : e.text.length())
            + " replaced=" + (e.end - e.start) + " focused=" + (display.getFocusControl() == s.widget)
            + " kind=" + textKind(e.text));
        s.lastImeTime = e.time; s.lastImeNanos = System.nanoTime();
        if (e.text != null && !e.text.isEmpty()) s.lastHangul = isHangul(e.text.charAt(0));
        s.candidate = null; s.receipt = null; s.session++;
        String preedit = s.preedit; int preeditStart = s.preeditStart;
        s.preedit = null; s.preeditStart = -1;
        if (ime.getCommitCount() == 0) {
            if (e.text != null && e.text.length() == 1 && isHangul(e.text.charAt(0))) {
                s.preedit = e.text; s.preeditStart = e.start;
            }
            return;
        }
        if (e.text == null || e.text.length() != 1 || ime.getCommitCount() != 1
                || e.end - e.start != 1) return;
        if (isHangul(e.text.charAt(0))) capture(s, e.time, e.start, e.text);
        else if ((e.text.charAt(0) == SWT.CR || e.text.charAt(0) == SWT.LF)
                && preedit != null && preeditStart == e.start
                && e.start >= 0 && e.start < s.widget.getCharCount()
                && s.widget.getTextRange(e.start, 1).equals(preedit)) {
            // Cocoa can commit the Enter character over the still-marked
            // Hangul after app switching. Preserve the observed preedit,
            // while the native key path remains responsible for the newline.
            note("enter-replaced-preedit"); capture(s, e.time, e.start, preedit);
        }
    }
    private void capture(EditorState s, int time, int start, String text) {
        StyledText st = s.widget;
        if (start < 0 || start >= st.getCharCount()) return;
        String removed = st.getTextRange(start, 1);
        if (!isHangul(removed.charAt(0))) return;
        int modelStart = s.toModel(start);
        if (s.document != null && (modelStart < 0 || modelStart >= s.document.getLength())) return;
        int leftStart = Math.max(0, start - 32), rightLength = Math.min(32, st.getCharCount() - start - 1);
        s.candidate = new Candidate(s.session, time, start, modelStart, st.getCharCount(),
            inputSerial, removed, text, st.getTextRange(leftStart, start - leftStart),
            st.getTextRange(start + 1, rightLength));
        note("commit-candidate"); queueRecovery(s);
    }
    private void onKey(Event e) {
        if (!(e.widget instanceof StyledText st)) return;
        EditorState s = state(st);
        if (s == null || isModifier(e.keyCode)) return;
        inputSerial++;
        s.receipt = null;
        if (e.keyCode == SWT.BS && isHangul(e.character) && s.lastHangul
                && e.time == s.lastImeTime && System.nanoTime() - s.lastImeNanos < 1_000_000_000L
                && (e.stateMask & (SWT.COMMAND | SWT.CTRL | SWT.ALT)) == 0 && eligible(st)) {
            s.candidate = null; e.doit = false; e.type = SWT.None;
            note("duplicate-backspace-blocked"); return;
        }
        Candidate c = s.candidate;
        if (c == null) return;
        note("pending-key kind=" + (isArrow(e.keyCode) ? "arrow" : e.keyCode == SWT.CR ? "enter" : "other") + " character=" + (isHangul(e.character) ? "hangul"
            : e.character == SWT.CR ? "cr" : e.character == SWT.LF ? "lf" : "other")
            + " modifiers=" + e.stateMask + " deleted=" + c.deleted);
        if (!e.doit) { s.candidate = null; return; }
        if (e.keyCode == SWT.ESC || e.keyCode == SWT.BS || e.keyCode == SWT.DEL) {
            s.candidate = null; return;
        }
        if (isArrow(e.keyCode) && e.time == c.time && e.character == c.text.charAt(0))
            recover(s, e.keyCode == SWT.ARROW_RIGHT ? Move.KEEP : Move.AFTER, null);
        else if (e.character == SWT.CR || e.character == SWT.LF
                || ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR)
                    && (e.character == 0 || (e.stateMask & SWT.SHIFT) != 0))) {
            // A CR/LF event inserts a newline, not the Hangul removed by the
            // preceding commit. Repair first. A plain Enter carrying Hangul
            // remains the normal native re-insertion path below.
            recover(s, Move.AFTER, null);
        }
        else if (!(isHangul(e.character) && e.character == c.text.charAt(0)
                && e.time == c.time && (e.stateMask & SWT.MODIFIER_MASK) == 0))
            s.candidate = null;
    }
    private void onMouse(Event e) {
        inputSerial++;
        if (e.button != 1) return;
        for (EditorState s : editors.values()) {
            Receipt r = s.receipt;
            s.receipt = null;
            if (r != null && e.widget == s.widget && e.count <= 1
                    && (e.stateMask & SWT.MODIFIER_MASK) == 0
                    && s.session == r.session && s.revision == r.revision
                    && s.selectionSerial == r.selectionSerial && inputSerial == r.inputSerial + 1
                    && s.mouseDownSerial <= r.inputSerial && e.time - r.time >= 0 && e.time - r.time <= 1000
                    && !s.widget.isDisposed() && display.getFocusControl() == s.widget) {
                // The dispatch audit may have repaired the text before MouseUp.
                // Preserve the click behavior when macOS consumed MouseDown.
                int intended = offsetAtClick(s.widget, e.x, e.y);
                if (intended >= 0) s.widget.setSelection(intended);
            }
            Candidate c = s.candidate;
            if (c == null || e.time - c.time < 0 || e.time - c.time > 1000) continue;
            Point click = e.widget == s.widget && e.count <= 1
                && (e.stateMask & SWT.MODIFIER_MASK) == 0 && s.mouseDownSerial <= c.inputSerial
                ? new Point(e.x, e.y) : null;
            recover(s, click != null ? Move.CLICK : Move.PRESERVE, click);
        }
    }
    private void queueRecovery(EditorState s) {
        if (s == null || s.candidate == null || display.isDisposed()) return;
        Candidate c = s.candidate;
        if (c.queued) return;
        c.queued = true;
        display.asyncExec(() -> guarded(() -> {
            if (closed || s.candidate != c || s.widget.isDisposed()) return;
            c.queued = false;
            // Native re-insertion occurs after the IME callback. Only audit
            // documented editors with an exact deletion record after dispatch.
            if (s.document != null) {
                recover(s, Move.PRESERVE, null);
                if (s.candidate == c) s.candidate = null;
            } else {
                // Legacy MouseUp may be a later native event. Do not expire
                // its candidate merely because asyncExec ran first.
                display.timerExec(1000, () -> {
                    if (s.candidate == c) s.candidate = null;
                });
            }
        }));
    }
    private void finishComposition(EditorState s) {
        if (s == null || s.finishing || !eligible(s.widget) || s.preedit == null
                || SWT.getVersion() != 4974) return;
        StyledText st = s.widget; IME ime = st.getIME();
        int start = s.preeditStart;
        Point selection = st.getSelection();
        if (ime == null || ime.getCommitCount() != 0 || ime.getCompositionOffset() != start
                || !ime.getText().equals(s.preedit) || start < 0 || start >= st.getCharCount()
                || !st.getTextRange(start, 1).equals(s.preedit) || selection.x != selection.y
                || st.getSelectionRanges().length != 2) return;
        int count = st.getCharCount(), caret = st.getCaretOffset();
        String text = s.preedit;
        s.candidate = null; s.receipt = null; s.finishing = true;
        try {
            if (!CocoaImeCommit.finish(st, text)) return;
        } finally { s.finishing = false; }
        if (ime.getCompositionOffset() == -1 && ime.getText().isEmpty()
                && st.getCharCount() == count && st.getTextRange(start, 1).equals(text)) {
            s.preedit = null; s.preeditStart = -1; s.session++;
            st.setSelection(caret);
            note("focus-composition-finished");
        } else note("focus-composition-not-finished");
    }
    private enum Move { KEEP, AFTER, CLICK, PRESERVE }
    private boolean recover(EditorState s, Move move, Point click) {
        if (s.viewer != null && s.viewer.getDocument() != s.document) bindViewer(s.widget, s.viewer);
        Candidate c = s.candidate;
        if (c == null || s.restoring || !eligible(s.widget) || s.session != c.session) return false;
        StyledText st = s.widget;
        if (System.nanoTime() - s.lastImeNanos > 1_000_000_000L) { s.candidate = null; return false; }
        if (s.document != null && (!c.deleted || c.invalid || s.revision != c.deletedRevision)) return false;
        int start = s.document == null ? c.widgetStart : s.toWidget(c.modelStart);
        if (start < 0 || start > st.getCharCount() || st.getCharCount() != c.oldCount - 1
                || !sameContext(st, start, c, 0)) return false;
        Point selection = st.getSelection(); int caret = st.getCaretOffset();
        if (selection.x != selection.y || st.getSelectionRanges().length != 2) {
            s.candidate = null; return false;
        }
        long serial = inputSerial;
        s.candidate = null; s.restoring = true;
        boolean inserted;
        try {
            st.replaceTextRange(start, 0, c.text);
            inserted = st.getCharCount() == c.oldCount && start < st.getCharCount()
                && st.getTextRange(start, 1).equals(c.text) && sameContext(st, start, c, 1);
        } finally { s.restoring = false; }
        if (!inserted) { note("restore-rejected-or-transformed"); return false; }
        note("restored");
        if (move == Move.KEEP) st.setSelection(start);
        else if (move == Move.AFTER) st.setSelection(start + 1);
        else if (move == Move.PRESERVE)
            st.setSelection(caret == start ? start + 1 : caret > start ? caret + 1 : caret);
        else {
            int intended = offsetAtClick(st, click.x, click.y);
            st.setSelection(start + 1);
            if (intended >= 0) {
                long revision = s.revision, selectionSerial = s.selectionSerial;
                display.asyncExec(() -> guarded(() -> {
                    if (!closed && !st.isDisposed() && s.session == c.session
                            && s.revision == revision && inputSerial == serial
                            && s.selectionSerial == selectionSerial
                            && display.getFocusControl() == st && intended <= st.getCharCount())
                        st.setSelection(intended);
                }));
            }
        }
        if (move == Move.PRESERVE)
            s.receipt = new Receipt(c.session, c.time, s.revision, s.selectionSerial, inputSerial);
        return true;
    }
    private static boolean sameContext(StyledText st, int start, Candidate c, int inserted) {
        return start >= c.left.length() && start + inserted + c.right.length() <= st.getCharCount()
            && st.getTextRange(start - c.left.length(), c.left.length()).equals(c.left)
            && st.getTextRange(start + inserted, c.right.length()).equals(c.right);
    }
    private final class EditorState {
        final StyledText widget;
        ITextViewer viewer; IDocument document; Candidate candidate;
        Receipt receipt;
        long session, revision, selectionSerial, mouseDownSerial, lastImeNanos;
        int lastImeTime; boolean restoring, lastHangul, finishing;
        String preedit; int preeditStart = -1;
        final Listener disposeListener = e -> detach(this);
        final Listener modifyListener = e -> {
            revision++;
            if (!restoring) receipt = null;
            if (!restoring && document == null) candidate = null;
        };
        final Listener selectionListener = e -> selectionSerial++;
        final CaretListener caretListener = e -> selectionSerial++;
        final ITextInputListener inputListener = new ITextInputListener() {
            @Override public void inputDocumentAboutToBeChanged(IDocument oldInput, IDocument newInput) {
                candidate = null; receipt = null; preedit = null; preeditStart = -1; revision++;
            }
            @Override public void inputDocumentChanged(IDocument oldInput, IDocument newInput) {
                if (viewer != null) bindViewer(widget, viewer);
            }
        };
        final IDocumentListener documentListener = new IDocumentListener() {
            @Override public void documentAboutToBeChanged(DocumentEvent e) {
                Candidate c = candidate;
                if (c == null || restoring) return;
                try {
                    if (!c.deleted && e.getDocument() == document && e.getOffset() == c.modelStart
                            && e.getLength() == 1 && "".equals(e.getText())
                            && document.get(e.getOffset(), 1).equals(c.removed)) c.deleted = true;
                    else { c.invalid = true; candidate = null; }
                } catch (BadLocationException ex) { candidate = null; note("document-range-invalid"); }
            }
            @Override public void documentChanged(DocumentEvent e) {
                revision++;
                if (!restoring) receipt = null;
                if (candidate != null && candidate.deleted) candidate.deletedRevision = revision;
            }
        };
        EditorState(StyledText st) { widget = st; }
        int toModel(int offset) {
            if (viewer instanceof ITextViewerExtension5 ext) return ext.widgetOffset2ModelOffset(offset);
            if (viewer instanceof TextViewer tv) return tv.widgetOffset2ModelOffset(offset);
            return viewer == null || viewer.getVisibleRegion().getOffset() == 0 ? offset : -1;
        }
        int toWidget(int offset) {
            if (viewer instanceof ITextViewerExtension5 ext) return ext.modelOffset2WidgetOffset(offset);
            if (viewer instanceof TextViewer tv) return tv.modelOffset2WidgetOffset(offset);
            return viewer == null || viewer.getVisibleRegion().getOffset() == 0 ? offset : -1;
        }
    }
    private record Receipt(long session, int time, long revision, long selectionSerial, long inputSerial) { }
    private static final class Candidate {
        final long session, inputSerial;
        final int time, widgetStart, modelStart, oldCount;
        final String removed, text, left, right;
        boolean deleted, invalid, queued; long deletedRevision;
        Candidate(long session, int time, int widgetStart, int modelStart, int oldCount,
                long inputSerial, String removed, String text, String left, String right) {
            this.session = session; this.time = time; this.widgetStart = widgetStart;
            this.modelStart = modelStart; this.oldCount = oldCount; this.inputSerial = inputSerial;
            this.removed = removed; this.text = text; this.left = left; this.right = right;
        }
    }
    private void detach(EditorState s) {
        s.candidate = null;
        s.receipt = null;
        if (s.document != null) s.document.removeDocumentListener(s.documentListener);
        if (s.viewer != null) s.viewer.removeTextInputListener(s.inputListener);
        if (!s.widget.isDisposed()) {
            s.widget.removeListener(SWT.Dispose, s.disposeListener);
            s.widget.removeListener(SWT.Modify, s.modifyListener);
            s.widget.removeListener(SWT.Selection, s.selectionListener);
            s.widget.removeCaretListener(s.caretListener);
        }
        editors.remove(s.widget); imes.values().removeIf(value -> value == s);
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (commands != null) commands.removeExecutionListener(commandListener);
        for (EditorState s : editors.values().toArray(EditorState[]::new)) detach(s);
        editors.clear(); imes.clear();
        if (display != null && !display.isDisposed()) {
            for (int kind : FILTERS) display.removeFilter(kind, filter);
            if (display.getData(INSTALL_KEY) == this) display.setData(INSTALL_KEY, null);
        }
        diagnostics.clear();
    }
    private void guarded(Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { note("error:" + error.getClass().getSimpleName()); }
    }
    private void note(String message) {
        if (!trace) return;
        if (diagnostics.size() == 128) diagnostics.removeFirst();
        diagnostics.addLast(message);
        System.out.println("KoreanImeFix " + message); // No text or file paths.
    }
    private static boolean eligible(StyledText st) {
        return !st.isDisposed() && st.getEditable() && st.isEnabled() && !st.getBlockSelection();
    }
    private static boolean isSmartEnter(String id) {
        return "org.eclipse.ui.edit.text.smartEnter".equals(id) || "org.eclipse.ui.edit.text.smartEnterInverse".equals(id);
    }
    private static boolean isModifier(int key) { return key == SWT.SHIFT || key == SWT.CTRL || key == SWT.ALT || key == SWT.COMMAND; }
    private static boolean isArrow(int key) { return key == SWT.ARROW_LEFT || key == SWT.ARROW_RIGHT || key == SWT.ARROW_UP || key == SWT.ARROW_DOWN; }
    private static boolean isHangul(char c) {
        return (c >= 0x1100 && c <= 0x11FF) || (c >= 0x3130 && c <= 0x318F)
            || (c >= 0xA960 && c <= 0xA97F) || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xD7B0 && c <= 0xD7FF);
    }
    private static String textKind(String text) {
        if (text == null || text.isEmpty()) return "empty";
        char c = text.charAt(0);
        return isHangul(c) ? "hangul" : c == SWT.CR ? "cr" : c == SWT.LF ? "lf" : "other";
    }
    private static int offsetAtClick(StyledText st, int x, int y) {
        int offset = st.getOffsetAtPoint(new Point(x, y));
        if (offset >= 0) return offset;
        int line = st.getLineIndex(y);
        if (line < 0 || line >= st.getLineCount()) return -1;
        int start = st.getOffsetAtLine(line), end = start + st.getLine(line).length();
        if (start == end) return start;
        Point first = st.getLocationAtOffset(start), last = st.getLocationAtOffset(end);
        if (first.y != last.y) return -1;
        return x <= first.x ? start : x >= last.x ? end : -1;
    }
}
