package korean.ime.styledtext;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextInputListener;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.IME;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

/** 한글 IME에서 사라진 한 음절을 복원합니다. 파일이나 네트워크에는 기록하지 않습니다. */
public final class KoreanImeFix implements IStartup, AutoCloseable {
    private static final String INSTALL_KEY = KoreanImeFix.class.getName();
    private static final int RECOVERY_WINDOW_MILLIS = 1000;
    private static final long RECOVERY_WINDOW_NANOS = 1_000_000_000L;
    private static final int CONTEXT_LENGTH = 32;
    private static final int SUPPORTED_COCOA_SWT_VERSION = 4974;
    private static final int DIAGNOSTIC_LIMIT = 128;
    private static final int[] FILTERS = { SWT.ImeComposition, SWT.KeyDown, SWT.MouseDown, SWT.MouseUp,
            SWT.FocusIn, SWT.FocusOut, SWT.Deactivate };

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
        @Override
        public void preExecute(String id, ExecutionEvent event) {
            if (isSmartEnter(id)) {
                guarded(() -> {
                    EditorState state = trackFocusedEditor();
                    if (state != null) {
                        inputSerial++;
                        state.receipt = null;
                        note("smart-enter");
                        restoreMissingCharacter(state, CaretMove.AFTER_RESTORED, null);
                    }
                });
            }
        }

        @Override
        public void postExecuteSuccess(String id, Object result) {}

        @Override
        public void postExecuteFailure(String id, ExecutionException error) {}

        @Override
        public void notHandled(String id, NotHandledException error) {}
    };

    @Override
    public void earlyStartup() {
        Display targetDisplay = PlatformUI.getWorkbench().getDisplay();
        targetDisplay.asyncExec(
                () -> install(targetDisplay, PlatformUI.getWorkbench().getService(ICommandService.class)));
    }

    // 독립된 통합 검사에서도 호출할 수 있도록 패키지 접근을 허용합니다.
    void install(Display targetDisplay, ICommandService service) {
        boolean shouldSkipInstallation = closed || targetDisplay.isDisposed()
                || !"cocoa".equals(SWT.getPlatform()) || Boolean.getBoolean("koreanime.fix.disabled")
                || targetDisplay.getData(INSTALL_KEY) != null;
        if (shouldSkipInstallation) {
            return;
        }

        display = targetDisplay;
        commands = service;
        targetDisplay.setData(INSTALL_KEY, this);

        for (int kind : FILTERS) {
            targetDisplay.addFilter(kind, filter);
        }
        if (commands != null) {
            commands.addExecutionListener(commandListener);
        }

        targetDisplay.disposeExec(this::close);
        trackFocusedEditor();
        note("installed");
    }

    private void dispatch(Event event) {
        if (closed) {
            return;
        }

        guarded(() -> {
            switch (event.type) {
                case SWT.ImeComposition -> onIme(event);
                case SWT.FocusIn -> {
                    if (event.widget instanceof StyledText widget) {
                        trackEditor(widget);
                    }
                }
                case SWT.KeyDown -> onKey(event);
                case SWT.MouseDown -> onMouseDown();
                case SWT.MouseUp -> onMouseUp(event);
                case SWT.FocusOut -> {
                    if (event.widget instanceof StyledText widget) {
                        finishOnFocusLoss(trackEditor(widget));
                    }
                }
                case SWT.Deactivate -> {
                    if (event.widget instanceof Shell shell) {
                        onShellDeactivated(shell);
                    }
                }
                default -> {
                }
            }
        });
    }

    private void onMouseDown() {
        inputSerial++;
        for (EditorState state : editors.values()) {
            state.mouseDownSerial = inputSerial;
        }
    }

    private void onShellDeactivated(Shell shell) {
        for (EditorState state : editors.values()) {
            if (!state.widget.isDisposed() && state.widget.getShell() == shell) {
                finishOnFocusLoss(state);
            }
        }
    }

    private void finishOnFocusLoss(EditorState state) {
        finishNativeComposition(state);
        queueRecoveryAudit(state);
    }

    private EditorState trackFocusedEditor() {
        return display != null && display.getFocusControl() instanceof StyledText widget ? trackEditor(widget)
                : null;
    }

    private EditorState trackEditor(StyledText widget) {
        EditorState state = registerEditor(widget);
        if (state != null) {
            bindWorkbenchViewer(state);
        }
        return state;
    }

    private EditorState registerEditor(StyledText widget) {
        if (widget.isDisposed()) {
            return null;
        }

        EditorState state = editors.get(widget);
        if (state == null) {
            state = new EditorState(widget);
            editors.put(widget, state);
            if (widget.getIME() != null) {
                imes.put(widget.getIME(), state);
            }

            widget.addListener(SWT.Dispose, state.disposeListener);
            widget.addListener(SWT.Modify, state.modifyListener);
            widget.addListener(SWT.Selection, state.selectionListener);
            widget.addCaretListener(state.caretListener);
        }
        return state;
    }

    private void bindWorkbenchViewer(EditorState state) {
        if (state.viewer != null && state.viewer.getTextWidget() == state.widget) {
            bindViewer(state.widget, state.viewer);
            return;
        }
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }

        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference ref : page.getEditorReferences()) {
                    IEditorPart editor = ref.getEditor(false);
                    if (editor == null) {
                        continue;
                    }

                    ITextOperationTarget target = editor.getAdapter(ITextOperationTarget.class);
                    if (target instanceof ITextViewer viewer && viewer.getTextWidget() == state.widget) {
                        bindViewer(state.widget, viewer);
                        return;
                    }
                }
            }
        }
    }

    void bindViewer(StyledText widget, ITextViewer viewer) {
        EditorState state = registerEditor(widget);
        if (state == null) {
            return;
        }

        IDocument document = viewer.getDocument();
        if (state.viewer == viewer && state.document == document) {
            return;
        }

        if (state.document != null) {
            state.document.removeDocumentListener(state.documentListener);
        }
        if (state.viewer != viewer) {
            if (state.viewer != null) {
                state.viewer.removeTextInputListener(state.inputListener);
            }
            viewer.addTextInputListener(state.inputListener);
        }
        state.candidate = null;
        state.viewer = viewer;
        state.document = document;
        state.revision++;
        state.receipt = null;
        if (document != null) {
            document.addDocumentListener(state.documentListener);
        }
        note("document-bound");
    }

    private void onIme(Event event) {
        if (!(event.widget instanceof IME ime) || event.detail != SWT.COMPOSITION_CHANGED) {
            return;
        }
        EditorState state = imes.get(ime);
        if (state == null) {
            EditorState focused = trackFocusedEditor();
            if (focused != null && focused.widget.getIME() == ime) {
                state = focused;
            }
        }
        if (state == null || !isRecoveryAllowed(state.widget)) {
            return;
        }
        boolean isFinishingDisplayedPreedit = state.finishing && ime.getCommitCount() == 1
                && event.text != null && event.text.equals(state.preedit)
                && event.start == state.preeditStart && event.end == event.start + 1;
        if (isFinishingDisplayedPreedit) {
            // 표시 중인 조합 문자를 보존하면서 StyledText가 조합 커서를 초기화하도록 합니다.
            // 삭제 범위를 0으로 만들고, doit을 false로 설정해 같은 문자의 중복 삽입을 막습니다.
            event.end = event.start;
            event.doit = false;
            return;
        }

        bindWorkbenchViewer(state);
        note("ime commit=" + ime.getCommitCount() + " length="
                + (event.text == null ? -1 : event.text.length()) + " replaced=" + (event.end - event.start)
                + " focused=" + (display.getFocusControl() == state.widget) + " kind="
                + textKind(event.text));

        state.lastImeTime = event.time;
        state.lastImeNanos = System.nanoTime();
        if (event.text != null && !event.text.isEmpty()) {
            state.lastHangul = isHangul(event.text.charAt(0));
        }
        state.candidate = null;
        state.receipt = null;
        state.session++;

        String preedit = state.preedit;
        int preeditStart = state.preeditStart;
        state.preedit = null;
        state.preeditStart = -1;

        if (ime.getCommitCount() == 0) {
            boolean isSingleHangulPreedit = event.text != null && event.text.length() == 1
                    && isHangul(event.text.charAt(0));
            if (isSingleHangulPreedit) {
                state.preedit = event.text;
                state.preeditStart = event.start;
            }
            return;
        }
        boolean cannotCaptureCommit = event.text == null || event.text.length() != 1
                || ime.getCommitCount() != 1 || event.end - event.start != 1;
        if (cannotCaptureCommit) {
            return;
        }
        if (isHangul(event.text.charAt(0))) {
            captureRecoveryCandidate(state, event.time, event.start, event.text);
            return;
        }

        boolean isEnterReplacingPreedit = (event.text.charAt(0) == SWT.CR || event.text.charAt(0) == SWT.LF)
                && preedit != null && preeditStart == event.start && event.start >= 0
                && event.start < state.widget.getCharCount()
                && state.widget.getTextRange(event.start, 1).equals(preedit);
        if (isEnterReplacingPreedit) {
            // 앱 전환 후 Cocoa가 조합 중인 한글을 Enter 문자로 덮어쓸 수 있습니다.
            // 확인한 조합 문자를 보존하고, 줄바꿈은 기존 네이티브 키 처리가 수행하도록 합니다.
            note("enter-replaced-preedit");
            captureRecoveryCandidate(state, event.time, event.start, preedit);
        }
    }

    private void captureRecoveryCandidate(EditorState state, int time, int start, String text) {
        StyledText widget = state.widget;
        if (start < 0 || start >= widget.getCharCount()) {
            return;
        }

        String removedText = widget.getTextRange(start, 1);
        if (!isHangul(removedText.charAt(0))) {
            return;
        }

        int modelStart = state.toModel(start);
        boolean isOutsideDocument = state.document != null
                && (modelStart < 0 || modelStart >= state.document.getLength());
        if (isOutsideDocument) {
            return;
        }

        int leftStart = Math.max(0, start - CONTEXT_LENGTH);
        int rightLength = Math.min(CONTEXT_LENGTH, widget.getCharCount() - start - 1);
        state.candidate = new RecoveryCandidate(state.session, time, start, modelStart, widget.getCharCount(),
                inputSerial, removedText, text, widget.getTextRange(leftStart, start - leftStart),
                widget.getTextRange(start + 1, rightLength));
        note("commit-candidate");
        queueRecoveryAudit(state);
    }

    private void onKey(Event event) {
        if (!(event.widget instanceof StyledText widget)) {
            return;
        }
        EditorState state = trackEditor(widget);
        if (state == null || isModifier(event.keyCode)) {
            return;
        }

        inputSerial++;
        state.receipt = null;
        if (isDuplicateBackspace(event, state)) {
            state.candidate = null;
            event.doit = false;
            event.type = SWT.None;
            note("duplicate-backspace-blocked");
            return;
        }

        RecoveryCandidate candidate = state.candidate;
        if (candidate == null) {
            return;
        }
        note("pending-key kind=" + keyKind(event.keyCode) + " character=" + characterKind(event.character)
                + " modifiers=" + event.stateMask + " deleted=" + candidate.deleted);
        if (!event.doit) {
            state.candidate = null;
            return;
        }
        boolean cancelsRecovery = event.keyCode == SWT.ESC || event.keyCode == SWT.BS
                || event.keyCode == SWT.DEL;
        if (cancelsRecovery) {
            state.candidate = null;
            return;
        }

        boolean isArrowCommit = isArrow(event.keyCode) && event.time == candidate.time
                && event.character == candidate.text.charAt(0);
        if (isArrowCommit) {
            CaretMove move = event.keyCode == SWT.ARROW_RIGHT ? CaretMove.BEFORE_RESTORED
                    : CaretMove.AFTER_RESTORED;
            restoreMissingCharacter(state, move, null);
        } else if (isNewlineInput(event)) {
            // 기존 네이티브 명령이 줄바꿈을 삽입하기 전에 문자를 복원합니다.
            restoreMissingCharacter(state, CaretMove.AFTER_RESTORED, null);
        } else if (!isNativeReinsertion(event, candidate)) {
            state.candidate = null;
        }
    }

    private static boolean isDuplicateBackspace(Event event, EditorState state) {
        boolean isImeBackspace = event.keyCode == SWT.BS && isHangul(event.character) && state.lastHangul;
        if (!isImeBackspace) {
            return false;
        }

        boolean isRecentImeEvent = event.time == state.lastImeTime
                && System.nanoTime() - state.lastImeNanos < RECOVERY_WINDOW_NANOS;
        if (!isRecentImeEvent) {
            return false;
        }

        boolean isPlainEditableBackspace = (event.stateMask & (SWT.COMMAND | SWT.CTRL | SWT.ALT)) == 0
                && isRecoveryAllowed(state.widget);
        return isPlainEditableBackspace;
    }

    private static boolean isNewlineInput(Event event) {
        boolean hasNewlineCharacter = event.character == SWT.CR || event.character == SWT.LF;
        if (hasNewlineCharacter) {
            return true;
        }

        boolean isEnterCommit = (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)
                && (event.character == 0 || (event.stateMask & SWT.SHIFT) != 0);
        return isEnterCommit;
    }

    private static boolean isNativeReinsertion(Event event, RecoveryCandidate candidate) {
        boolean isSameCommittedCharacter = isHangul(event.character)
                && event.character == candidate.text.charAt(0) && event.time == candidate.time;
        return isSameCommittedCharacter && (event.stateMask & SWT.MODIFIER_MASK) == 0;
    }

    private void onMouseUp(Event event) {
        inputSerial++;
        if (event.button != 1) {
            return;
        }

        for (EditorState state : editors.values()) {
            RecoveryReceipt receipt = state.receipt;
            state.receipt = null;
            if (canApplyClickAfterRecovery(event, state, receipt)) {
                // 이벤트 처리 후 검사에서 MouseUp보다 먼저 문자를 복원했을 수 있습니다.
                // macOS가 MouseDown을 소비한 경우에도 클릭한 위치로 커서를 이동합니다.
                int intended = offsetAtClick(state.widget, event.x, event.y);
                if (intended >= 0) {
                    state.widget.setSelection(intended);
                }
            }

            RecoveryCandidate candidate = state.candidate;
            boolean isCandidateUnavailable = candidate == null || event.time - candidate.time < 0
                    || event.time - candidate.time > RECOVERY_WINDOW_MILLIS;
            if (isCandidateUnavailable) {
                continue;
            }

            boolean isRecoveryClick = event.widget == state.widget && event.count <= 1
                    && (event.stateMask & SWT.MODIFIER_MASK) == 0
                    && state.mouseDownSerial <= candidate.inputSerial;
            Point click = isRecoveryClick ? new Point(event.x, event.y) : null;
            CaretMove move = click != null ? CaretMove.CLICKED_OFFSET : CaretMove.PRESERVE_POSITION;
            restoreMissingCharacter(state, move, click);
        }
    }

    private boolean canApplyClickAfterRecovery(Event event, EditorState state, RecoveryReceipt receipt) {
        boolean isUnmodifiedSingleClick = receipt != null && event.widget == state.widget && event.count <= 1
                && (event.stateMask & SWT.MODIFIER_MASK) == 0;
        if (!isUnmodifiedSingleClick) {
            return false;
        }

        boolean isCurrentReceipt = state.session == receipt.session && state.revision == receipt.revision
                && state.selectionSerial == receipt.selectionSerial;
        if (!isCurrentReceipt) {
            return false;
        }

        boolean isNextMouseEvent = inputSerial == receipt.inputSerial + 1
                && state.mouseDownSerial <= receipt.inputSerial;
        if (!isNextMouseEvent) {
            return false;
        }

        boolean isWithinRecoveryWindow = event.time - receipt.time >= 0
                && event.time - receipt.time <= RECOVERY_WINDOW_MILLIS;
        if (!isWithinRecoveryWindow) {
            return false;
        }

        boolean canUseWidget = !state.widget.isDisposed() && display.getFocusControl() == state.widget;
        return canUseWidget;
    }

    private void queueRecoveryAudit(EditorState state) {
        boolean cannotQueueRecoveryAudit = state == null || state.candidate == null || display.isDisposed();
        if (cannotQueueRecoveryAudit) {
            return;
        }
        RecoveryCandidate candidate = state.candidate;
        if (candidate.queued) {
            return;
        }
        candidate.queued = true;
        display.asyncExec(() -> guarded(() -> {
            boolean isStaleRecoveryAudit = closed || state.candidate != candidate || state.widget.isDisposed();
            if (isStaleRecoveryAudit) {
                return;
            }
            candidate.queued = false;
            // 네이티브 문자 재삽입은 IME 콜백 뒤에 일어납니다.
            // 문서와 연결된 편집기는 이벤트 처리 후 정확한 삭제 기록을 확인해 복원합니다.

            if (state.document != null) {
                restoreMissingCharacter(state, CaretMove.PRESERVE_POSITION, null);
                if (state.candidate == candidate) {
                    state.candidate = null;
                }
            } else {
                // 문서와 연결되지 않은 위젯은 네이티브 MouseUp이 나중에 도착할 수 있습니다.
                // asyncExec가 먼저 실행됐다는 이유만으로 복원 후보를 폐기하지 않습니다.
                display.timerExec(RECOVERY_WINDOW_MILLIS, () -> {
                    if (state.candidate == candidate) {
                        state.candidate = null;
                    }
                });
            }
        }));
    }

    private void finishNativeComposition(EditorState state) {
        boolean cannotFinishNativeComposition = state == null || state.finishing
                || !isRecoveryAllowed(state.widget) || state.preedit == null
                || SWT.getVersion() != SUPPORTED_COCOA_SWT_VERSION;
        if (cannotFinishNativeComposition) {
            return;
        }
        StyledText widget = state.widget;
        IME ime = widget.getIME();
        int start = state.preeditStart;
        Point selection = widget.getSelection();
        boolean cannotCommitDisplayedPreedit = ime == null || ime.getCommitCount() != 0
                || ime.getCompositionOffset() != start || !ime.getText().equals(state.preedit)
                || start < 0 || start >= widget.getCharCount()
                || !widget.getTextRange(start, 1).equals(state.preedit) || selection.x != selection.y
                || widget.getSelectionRanges().length != 2;
        if (cannotCommitDisplayedPreedit) {
            return;
        }

        int count = widget.getCharCount();
        int caret = widget.getCaretOffset();
        String text = state.preedit;
        state.candidate = null;
        state.receipt = null;
        state.finishing = true;

        try {
            if (!CocoaImeCommit.finish(widget, text)) {
                return;
            }
        } finally {
            state.finishing = false;
        }
        boolean isCompositionFinished = ime.getCompositionOffset() == -1 && ime.getText().isEmpty()
                && widget.getCharCount() == count && widget.getTextRange(start, 1).equals(text);
        if (isCompositionFinished) {
            state.preedit = null;
            state.preeditStart = -1;
            state.session++;
            widget.setSelection(caret);
            note("focus-composition-finished");
        } else {
            note("focus-composition-not-finished");
        }
    }

    private enum CaretMove {
        BEFORE_RESTORED, AFTER_RESTORED, CLICKED_OFFSET, PRESERVE_POSITION
    }

    private boolean restoreMissingCharacter(EditorState state, CaretMove move, Point click) {
        if (state.viewer != null && state.viewer.getDocument() != state.document) {
            bindViewer(state.widget, state.viewer);
        }
        RecoveryCandidate candidate = state.candidate;
        boolean cannotRestoreCandidate = candidate == null || state.restoring
                || !isRecoveryAllowed(state.widget) || state.session != candidate.session;
        if (cannotRestoreCandidate) {
            return false;
        }

        StyledText widget = state.widget;
        if (System.nanoTime() - state.lastImeNanos > RECOVERY_WINDOW_NANOS) {
            state.candidate = null;
            return false;
        }
        boolean hasConflictingDocumentChange = state.document != null
                && (!candidate.deleted || candidate.invalid || state.revision != candidate.deletedRevision);
        if (hasConflictingDocumentChange) {
            return false;
        }
        int start = state.document == null ? candidate.widgetStart : state.toWidget(candidate.modelStart);
        boolean hasUnexpectedDeletionContext = start < 0 || start > widget.getCharCount()
                || widget.getCharCount() != candidate.originalCharCount - 1
                || !hasMatchingContext(widget, start, candidate, 0);
        if (hasUnexpectedDeletionContext) {
            return false;
        }

        Point selection = widget.getSelection();
        int caret = widget.getCaretOffset();
        if (selection.x != selection.y || widget.getSelectionRanges().length != 2) {
            state.candidate = null;
            return false;
        }

        long serial = inputSerial;
        state.candidate = null;
        if (!insertRecoveryText(state, candidate, start)) {
            note("restore-rejected-or-transformed");
            return false;
        }
        note("restored");
        moveCaretAfterRecovery(state, candidate, move, click, start, caret, serial);
        if (move == CaretMove.PRESERVE_POSITION) {
            state.receipt = new RecoveryReceipt(candidate.session, candidate.time, state.revision,
                    state.selectionSerial, inputSerial);
        }
        return true;
    }

    private static boolean insertRecoveryText(EditorState state, RecoveryCandidate candidate, int start) {
        StyledText widget = state.widget;
        state.restoring = true;
        try {
            widget.replaceTextRange(start, 0, candidate.text);
            boolean restoredTextMatches = widget.getCharCount() == candidate.originalCharCount
                    && start < widget.getCharCount() && widget.getTextRange(start, 1).equals(candidate.text)
                    && hasMatchingContext(widget, start, candidate, 1);
            return restoredTextMatches;
        } finally {
            state.restoring = false;
        }
    }

    private void moveCaretAfterRecovery(EditorState state, RecoveryCandidate candidate, CaretMove move,
            Point click, int start, int caret, long serial) {
        StyledText widget = state.widget;
        switch (move) {
            case BEFORE_RESTORED -> widget.setSelection(start);
            case AFTER_RESTORED -> widget.setSelection(start + 1);
            case PRESERVE_POSITION ->
                widget.setSelection(caret == start ? start + 1 : caret > start ? caret + 1 : caret);
            case CLICKED_OFFSET -> {
                int intended = offsetAtClick(widget, click.x, click.y);
                widget.setSelection(start + 1);
                if (intended >= 0) {
                    queueClickedCaret(state, candidate, intended, serial);
                }
            }
        }
    }

    private void queueClickedCaret(EditorState state, RecoveryCandidate candidate, int intended,
            long serial) {
        StyledText widget = state.widget;
        long revision = state.revision;
        long selectionSerial = state.selectionSerial;
        display.asyncExec(() -> guarded(() -> {
            boolean canApplyQueuedClick = !closed && !widget.isDisposed()
                    && state.session == candidate.session && state.revision == revision
                    && inputSerial == serial && state.selectionSerial == selectionSerial
                    && display.getFocusControl() == widget && intended <= widget.getCharCount();
            if (canApplyQueuedClick) {
                widget.setSelection(intended);
            }
        }));
    }

    private static boolean hasMatchingContext(StyledText widget, int start, RecoveryCandidate candidate,
            int inserted) {
        boolean hasContextBounds = start >= candidate.leftContext.length()
                && start + inserted + candidate.rightContext.length() <= widget.getCharCount();
        if (!hasContextBounds) {
            return false;
        }

        boolean matchesLeftContext = widget.getTextRange(
                start - candidate.leftContext.length(), candidate.leftContext.length())
                .equals(candidate.leftContext);
        if (!matchesLeftContext) {
            return false;
        }

        boolean matchesRightContext = widget.getTextRange(start + inserted, candidate.rightContext.length())
                .equals(candidate.rightContext);
        return matchesRightContext;
    }

    private final class EditorState {
        final StyledText widget;
        ITextViewer viewer;
        IDocument document;
        RecoveryCandidate candidate;
        RecoveryReceipt receipt;
        long session;
        long revision;
        long selectionSerial;
        long mouseDownSerial;
        long lastImeNanos;
        int lastImeTime;
        boolean restoring;
        boolean lastHangul;
        boolean finishing;
        String preedit;
        int preeditStart = -1;

        final Listener disposeListener = event -> detach(this);
        final Listener modifyListener = event -> {
            revision++;
            if (!restoring) {
                receipt = null;
            }
            if (!restoring && document == null) {
                candidate = null;
            }
        };
        final Listener selectionListener = event -> selectionSerial++;
        final CaretListener caretListener = event -> selectionSerial++;

        final ITextInputListener inputListener = new ITextInputListener() {
            @Override
            public void inputDocumentAboutToBeChanged(IDocument oldInput, IDocument newInput) {
                candidate = null;
                receipt = null;
                preedit = null;
                preeditStart = -1;
                revision++;
            }

            @Override
            public void inputDocumentChanged(IDocument oldInput, IDocument newInput) {
                if (viewer != null) {
                    bindViewer(widget, viewer);
                }
            }
        };

        final IDocumentListener documentListener = new IDocumentListener() {
            @Override
            public void documentAboutToBeChanged(DocumentEvent event) {
                RecoveryCandidate pending = candidate;
                if (pending == null || restoring) {
                    return;
                }

                try {
                    boolean isExpectedDeletion = !pending.deleted && event.getDocument() == document
                            && event.getOffset() == pending.modelStart && event.getLength() == 1
                            && "".equals(event.getText())
                            && document.get(event.getOffset(), 1).equals(pending.removedText);
                    if (isExpectedDeletion) {
                        pending.deleted = true;
                    } else {
                        pending.invalid = true;
                        candidate = null;
                    }
                } catch (BadLocationException error) {
                    candidate = null;
                    note("document-range-invalid");
                }
            }

            @Override
            public void documentChanged(DocumentEvent event) {
                revision++;
                if (!restoring) {
                    receipt = null;
                }
                if (candidate != null && candidate.deleted) {
                    candidate.deletedRevision = revision;
                }
            }
        };

        EditorState(StyledText widget) {
            this.widget = widget;
        }

        int toModel(int offset) {
            if (viewer instanceof ITextViewerExtension5 ext) {
                return ext.widgetOffset2ModelOffset(offset);
            }
            if (viewer instanceof TextViewer tv) {
                return tv.widgetOffset2ModelOffset(offset);
            }
            return viewer == null || viewer.getVisibleRegion().getOffset() == 0 ? offset : -1;
        }

        int toWidget(int offset) {
            if (viewer instanceof ITextViewerExtension5 ext) {
                return ext.modelOffset2WidgetOffset(offset);
            }
            if (viewer instanceof TextViewer tv) {
                return tv.modelOffset2WidgetOffset(offset);
            }
            return viewer == null || viewer.getVisibleRegion().getOffset() == 0 ? offset : -1;
        }
    }

    private record RecoveryReceipt(long session, int time, long revision, long selectionSerial,
            long inputSerial) {
    }

    private static final class RecoveryCandidate {
        final long session;
        final long inputSerial;
        final int time;
        final int widgetStart;
        final int modelStart;
        final int originalCharCount;
        final String removedText;
        final String text;
        final String leftContext;
        final String rightContext;
        boolean deleted;
        boolean invalid;
        boolean queued;
        long deletedRevision;

        RecoveryCandidate(long session, int time, int widgetStart, int modelStart, int originalCharCount,
                long inputSerial, String removedText, String text, String leftContext, String rightContext) {
            this.session = session;
            this.time = time;
            this.widgetStart = widgetStart;
            this.modelStart = modelStart;
            this.originalCharCount = originalCharCount;
            this.inputSerial = inputSerial;
            this.removedText = removedText;
            this.text = text;
            this.leftContext = leftContext;
            this.rightContext = rightContext;
        }
    }

    private void detach(EditorState state) {
        state.candidate = null;
        state.receipt = null;

        if (state.document != null) {
            state.document.removeDocumentListener(state.documentListener);
        }
        if (state.viewer != null) {
            state.viewer.removeTextInputListener(state.inputListener);
        }
        if (!state.widget.isDisposed()) {
            state.widget.removeListener(SWT.Dispose, state.disposeListener);
            state.widget.removeListener(SWT.Modify, state.modifyListener);
            state.widget.removeListener(SWT.Selection, state.selectionListener);
            state.widget.removeCaretListener(state.caretListener);
        }

        editors.remove(state.widget);
        imes.values().removeIf(value -> value == state);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;

        if (commands != null) {
            commands.removeExecutionListener(commandListener);
        }

        for (EditorState state : editors.values().toArray(EditorState[]::new)) {
            detach(state);
        }
        editors.clear();
        imes.clear();

        if (display != null && !display.isDisposed()) {
            for (int kind : FILTERS) {
                display.removeFilter(kind, filter);
            }

            if (display.getData(INSTALL_KEY) == this) {
                display.setData(INSTALL_KEY, null);
            }
        }

        diagnostics.clear();
    }

    private void guarded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            note("error:" + error.getClass().getSimpleName());
        }
    }

    private void note(String message) {
        if (!trace) {
            return;
        }
        if (diagnostics.size() == DIAGNOSTIC_LIMIT) {
            diagnostics.removeFirst();
        }
        diagnostics.addLast(message);
        System.out.println("KoreanImeFix " + message); // 입력 내용과 파일 경로는 출력하지 않습니다.
    }

    private static boolean isRecoveryAllowed(StyledText widget) {
        return !widget.isDisposed() && widget.getEditable() && widget.isEnabled()
                && !widget.getBlockSelection();
    }

    private static boolean isSmartEnter(String id) {
        return "org.eclipse.ui.edit.text.smartEnter".equals(id)
                || "org.eclipse.ui.edit.text.smartEnterInverse".equals(id);
    }

    private static boolean isModifier(int key) {
        return key == SWT.SHIFT || key == SWT.CTRL || key == SWT.ALT || key == SWT.COMMAND;
    }

    private static boolean isArrow(int key) {
        return key == SWT.ARROW_LEFT || key == SWT.ARROW_RIGHT || key == SWT.ARROW_UP
                || key == SWT.ARROW_DOWN;
    }

    private static boolean isHangul(char character) {
        return (character >= 0x1100 && character <= 0x11FF) || (character >= 0x3130 && character <= 0x318F)
                || (character >= 0xA960 && character <= 0xA97F)
                || (character >= 0xAC00 && character <= 0xD7A3)
                || (character >= 0xD7B0 && character <= 0xD7FF);
    }

    private static String keyKind(int keyCode) {
        return isArrow(keyCode) ? "arrow" : keyCode == SWT.CR ? "enter" : "other";
    }

    private static String characterKind(char character) {
        return isHangul(character) ? "hangul"
                : character == SWT.CR ? "cr" : character == SWT.LF ? "lf" : "other";
    }

    private static String textKind(String text) {
        if (text == null || text.isEmpty()) {
            return "empty";
        }
        return characterKind(text.charAt(0));
    }

    private static int offsetAtClick(StyledText widget, int x, int y) {
        int offset = widget.getOffsetAtPoint(new Point(x, y));
        if (offset >= 0) {
            return offset;
        }

        int line = widget.getLineIndex(y);
        if (line < 0 || line >= widget.getLineCount()) {
            return -1;
        }

        int start = widget.getOffsetAtLine(line);
        int end = start + widget.getLine(line).length();
        if (start == end) {
            return start;
        }

        Point first = widget.getLocationAtOffset(start);
        Point last = widget.getLocationAtOffset(end);
        if (first.y != last.y) {
            return -1;
        }
        return x <= first.x ? start : x >= last.x ? end : -1;
    }
}
