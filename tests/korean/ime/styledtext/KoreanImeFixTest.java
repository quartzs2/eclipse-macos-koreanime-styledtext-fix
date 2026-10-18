package korean.ime.styledtext;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.commands.*;
import org.eclipse.jface.text.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.commands.ICommandService;

/**
 * 합성 IME 프로토콜을 검사합니다. 실제 macOS 키보드 입력 검사를 대신하지 않습니다.
 */
public final class KoreanImeFixTest {
    static int passed;
    static Display display;
    static final List<IExecutionListener> executions = new ArrayList<>();
    static final ICommandService service = (ICommandService) Proxy.newProxyInstance(
            KoreanImeFixTest.class.getClassLoader(), new Class<?>[] { ICommandService.class }, (p, m, a) -> {
                if (m.getName().equals("addExecutionListener")) {
                    executions.add((IExecutionListener) a[0]);
                }
                if (m.getName().equals("removeExecutionListener")) {
                    executions.remove(a[0]);
                }
                return null;
            });

    static final class Fixture implements AutoCloseable {
        final Shell shell = new Shell(display);
        final TextViewer viewer;
        final StyledText text;
        final Document document;
        final KoreanImeFix fix = new KoreanImeFix();

        Fixture(String value) {
            shell.setLayout(new FillLayout());
            shell.setSize(640, 320);
            viewer = new TextViewer(shell, SWT.MULTI);
            document = new Document(value);
            viewer.setDocument(document);
            text = viewer.getTextWidget();
            shell.open();
            text.setFocus();
            drain();
            fix.install(display, service);
            fix.bindViewer(text, viewer);
        }

        void commit(int start, String value) throws Exception {
            text.setSelection(start + 1);
            IME ime = text.getIME();
            set(ime, "commitCount", 1);
            set(ime, "startOffset", start);
            set(ime, "caretOffset", 1);
            set(ime, "text", value);
            Event e = new Event();
            e.detail = SWT.COMPOSITION_CHANGED;
            e.start = start;
            e.end = start + 1;
            e.text = value;
            e.time = 123;
            ime.notifyListeners(SWT.ImeComposition, e);
            set(ime, "commitCount", 0);
            set(ime, "startOffset", -1);
            set(ime, "caretOffset", 0);
            set(ime, "text", "");
        }

        void key(int code, char character, int mask) {
            Event e = new Event();
            e.keyCode = code;
            e.character = character;
            e.stateMask = mask;
            e.time = 123;
            text.notifyListeners(SWT.KeyDown, e);
        }

        void preedit(int start, String value) throws Exception {
            IME ime = text.getIME();
            set(ime, "commitCount", 0);
            set(ime, "startOffset", start);
            set(ime, "text", value);
            Event e = new Event();
            e.detail = SWT.COMPOSITION_CHANGED;
            e.start = start;
            e.end = start + 1;
            e.text = value;
            e.time = 122;
            ime.notifyListeners(SWT.ImeComposition, e);
        }

        @Override
        public void close() {
            fix.close();
            shell.dispose();
            drain();
        }
    }

    public static void main(String[] args) throws Exception {
        display = new Display();
        try {
            run("lost commit recovers once", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    eq("가나", f.document.get());
                    drain();
                    eq("가나다", f.document.get());
                    eq(3, f.text.getCaretOffset());
                    drain();
                    eq("가나다", f.text.getText());
                }
            });
            run("normal native reinsertion does not duplicate", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.key(0, '다', 0);
                    drain();
                    eq("가나다", f.document.get());
                }
            });
            run("plain Enter repairs before newline", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.key(SWT.CR, '\r', 0);
                    drain();
                    eq("가나다\n", f.document.get());
                }
            });
            run("CR commit replacing observed Hangul preserves preedit", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.preedit(2, "다");
                    f.commit(2, "\r");
                    f.key(SWT.CR, '\r', 0);
                    drain();
                    eq("가나다\n", f.document.get());
                }
            });
            run("CR commit without observed Hangul does not invent a recovery", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "\r");
                    f.key(SWT.CR, '\r', 0);
                    drain();
                    eq("가나\n", f.document.get());
                }
            });
            run("LF repairs before newline", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.key(0, '\n', 0);
                    drain();
                    eq("가나다\n", f.document.get());
                }
            });
            run("plain Enter carrying native Hangul does not duplicate", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.key(SWT.CR, '다', 0);
                    drain();
                    eq("가나다", f.document.get());
                }
            });
            run("commit on return from another window followed by Enter", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    Shell other = new Shell(display);
                    Text input = new Text(other, SWT.SINGLE);
                    other.setSize(250, 120);
                    other.open();
                    input.setFocus();
                    drain();
                    f.text.setFocus();
                    f.commit(2, "다");
                    f.key(SWT.CR, '\r', 0);
                    drain();
                    eq("가나다\n", f.document.get());
                    other.dispose();
                }
            });
            for (int code : new int[] { SWT.ARROW_LEFT, SWT.ARROW_RIGHT, SWT.ARROW_UP, SWT.ARROW_DOWN }) {
                run("arrow " + code, () -> {
                    try (Fixture f = new Fixture("가나다\nabc")) {
                        f.commit(2, "다");
                        f.key(code, '다', 0);
                        drain();
                        eq("가나다\nabc", f.document.get());
                        if (code == SWT.ARROW_RIGHT) {
                            eq(3, f.text.getCaretOffset());
                        }
                        if (code == SWT.ARROW_LEFT) {
                            eq(2, f.text.getCaretOffset());
                        }
                    }
                });
            }
            run("smartEnter repairs before existing command", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    for (IExecutionListener l : List.copyOf(executions)) {
                        l.preExecute("org.eclipse.ui.edit.text.smartEnter", new ExecutionEvent());
                    }
                    eq("가나다", f.document.get());
                    f.document.replace(f.document.getLength(), 0, "\n    ");
                    drain();
                    eq("가나다\n    ", f.document.get());
                }
            });
            run("Shift modifier does not consume candidate", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.key(SWT.SHIFT, (char) 0, 0);
                    for (IExecutionListener l : List.copyOf(executions)) {
                        l.preExecute("org.eclipse.ui.edit.text.smartEnter", new ExecutionEvent());
                    }
                    eq("가나다", f.document.get());
                    drain();
                }
            });
            run("focus loss restores without stealing focus", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    Shell other = new Shell(display);
                    Text input = new Text(other, SWT.SINGLE);
                    other.setSize(250, 120);
                    other.open();
                    f.text.setFocus();
                    f.commit(2, "다");
                    input.setFocus();
                    drain();
                    eq("가나다", f.document.get());
                    eq(input, display.getFocusControl());
                    other.dispose();
                }
            });
            run("focus loss finishes observed preedit without changing text", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.preedit(2, "다");
                    f.text.setSelection(3);
                    Shell other = new Shell(display);
                    Text input = new Text(other, SWT.SINGLE);
                    other.setSize(250, 120);
                    other.open();
                    input.setFocus();
                    drain();
                    eq("가나다", f.document.get());
                    eq(-1, f.text.getIME().getCompositionOffset());
                    eq(input, display.getFocusControl());
                    eq(3, f.text.getCaretOffset());
                    f.text.setFocus();
                    f.key(0, '라', 0);
                    drain();
                    eq("가나다라", f.document.get());
                    other.dispose();
                }
            });
            run("shell deactivation finishes preedit without stealing focus", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.preedit(2, "다");
                    f.text.setSelection(3);
                    f.shell.notifyListeners(SWT.Deactivate, new Event());
                    drain();
                    eq("가나다", f.document.get());
                    eq(-1, f.text.getIME().getCompositionOffset());
                }
            });
            run("ordinary Backspace is respected", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.text.setSelection(3);
                    f.key(SWT.BS, '\b', 0);
                    drain();
                    eq("가나", f.document.get());
                }
            });
            run("intentional delete after commit is not undone", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.key(SWT.BS, '\b', 0);
                    drain();
                    eq("가", f.document.get());
                }
            });
            run("composition duplicate Backspace is suppressed", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.key(SWT.BS, '다', 0);
                    drain();
                    eq("가나", f.document.get());
                }
            });
            run("Escape invalidates a pending recovery", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.key(SWT.ESC, (char) 0, 0);
                    drain();
                    eq("가나", f.document.get());
                }
            });
            run("late IME completion is bound to old editor", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    Shell other = new Shell(display);
                    Text input = new Text(other, SWT.SINGLE);
                    other.setSize(250, 120);
                    other.open();
                    input.setFocus();
                    f.commit(2, "다");
                    drain();
                    eq("가나다", f.document.get());
                    eq(input, display.getFocusControl());
                    other.dispose();
                }
            });
            run("same-length unrelated edit cancels recovery", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.document.replace(0, 1, "라");
                    drain();
                    eq("라나", f.document.get());
                }
            });
            run("Verify rejection leaves caret untouched", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.text.addVerifyListener(e -> e.doit = false);
                    int caret = f.text.getCaretOffset();
                    drain();
                    eq("가나", f.document.get());
                    eq(caret, f.text.getCaretOffset());
                }
            });
            run("new editor input cancels recovery", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    Document replacement = new Document("새 문서");
                    f.viewer.setDocument(replacement);
                    drain();
                    eq("새 문서", replacement.get());
                }
            });
            run("disposed editor cancels queued work", () -> {
                Fixture f = new Fixture("가나다");
                f.commit(2, "다");
                f.close();
                drain();
                eq("가나", f.document.get());
            });
            run("read-only widget does not get recovery", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.text.setEditable(false);
                    f.commit(2, "다");
                    drain();
                    eq("가나다", f.document.get());
                }
            });
            run("selection is not collapsed", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.text.setSelection(0, 1);
                    drain();
                    eq("가나", f.document.get());
                    eq(new Point(0, 1), f.text.getSelection());
                }
            });
            run("CRLF preserved", () -> {
                try (Fixture f = new Fixture("가나다\r\nabc")) {
                    f.commit(2, "다");
                    f.key(SWT.ARROW_RIGHT, '다', 0);
                    drain();
                    eq("가나다\r\nabc", f.document.get());
                    eq(3, f.text.getCaretOffset());
                }
            });
            run("later MouseUp retains clicked caret after audit", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    drain();
                    Point p = f.text.getLocationAtOffset(0);
                    Event mouse = new Event();
                    mouse.button = 1;
                    mouse.count = 1;
                    mouse.time = 124;
                    mouse.x = p.x;
                    mouse.y = p.y;
                    f.text.notifyListeners(SWT.MouseUp, mouse);
                    drain();
                    eq("가나다", f.document.get());
                    eq(0, f.text.getCaretOffset());
                }
            });
            run("new input cancels old click receipt", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    drain();
                    f.key(0, 'x', 0);
                    Event mouse = new Event();
                    mouse.button = 1;
                    mouse.count = 1;
                    mouse.time = 124;
                    f.text.notifyListeners(SWT.MouseUp, mouse);
                    drain();
                    eq("가나다x", f.document.get());
                    eq(4, f.text.getCaretOffset());
                }
            });
            run("MouseUp before audit restores text and clicked caret", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");

                    mouseUpAtStart(f);
                    drain();

                    eq("가나다", f.document.get());
                    eq(0, f.text.getCaretOffset());
                }
            });
            run("new key cancels queued clicked caret", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    mouseUpAtStart(f);

                    f.key(0, 'x', 0);
                    drain();

                    eq("가나다x", f.document.get());
                    eq(4, f.text.getCaretOffset());
                }
            });
            run("new selection cancels queued clicked caret", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    mouseUpAtStart(f);

                    f.text.setSelection(1);
                    drain();

                    eq("가나다", f.document.get());
                    eq(1, f.text.getCaretOffset());
                }
            });
            run("new caret cancels old click receipt", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    drain();
                    f.text.setSelection(1);
                    Event mouse = new Event();
                    mouse.button = 1;
                    mouse.count = 1;
                    mouse.time = 124;
                    f.text.notifyListeners(SWT.MouseUp, mouse);
                    drain();
                    eq(1, f.text.getCaretOffset());
                }
            });
            run("Verify transformed insertion is not retried", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.text.addVerifyListener(e -> {
                        if ("다".equals(e.text)) {
                            e.text = "마";
                        }
                    });
                    drain();
                    eq("가나마", f.document.get());
                    drain();
                    eq("가나마", f.document.get());
                }
            });
            run("visible region offsets are mapped", () -> {
                try (Fixture f = new Fixture("XX가나다YY")) {
                    f.viewer.setVisibleRegion(2, 3);
                    f.commit(f.text.getText().indexOf("다"), "다");
                    drain();
                    eq("XX가나다YY", f.document.get());
                }
            });
            run("another widget key does not discard owner candidate", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    Text other = new Text(f.shell, SWT.SINGLE);
                    Event key = new Event();
                    key.keyCode = 'x';
                    key.character = 'x';
                    other.notifyListeners(SWT.KeyDown, key);
                    drain();
                    eq("가나다", f.document.get());
                }
            });
            run("block selection declines recovery", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    f.commit(2, "다");
                    f.text.setBlockSelection(true);
                    drain();
                    eq("가나", f.document.get());
                }
            });
            run("duplicate installer does not double recover", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    KoreanImeFix another = new KoreanImeFix();
                    another.install(display, service);
                    f.commit(2, "다");
                    drain();
                    eq("가나다", f.document.get());
                    eq(1, executions.size());
                    another.close();
                }
            });
            run("repair supports Undo Redo round trip", () -> {
                try (Fixture f = new Fixture("가나다")) {
                    TextViewerUndoManager undo = new TextViewerUndoManager(20);
                    undo.connect(f.viewer);
                    f.viewer.setUndoManager(undo);
                    f.commit(2, "다");
                    drain();
                    eq("가나다", f.document.get());
                    if (!undo.undoable()) {
                        throw new AssertionError("recovery should be tracked by document undo manager");
                    }
                    undo.undo();
                    undo.redo();
                    drain();
                    eq("가나다", f.document.get());
                    eq(f.document.get(), f.text.getText());
                    undo.disconnect();
                }
            });
            run("generic StyledText legacy arrow fallback", () -> {
                Shell shell = new Shell(display);
                shell.setLayout(new FillLayout());
                shell.setSize(600, 250);
                StyledText text = new StyledText(shell, SWT.MULTI);
                text.setText("가나다\nabc");
                shell.open();
                text.setFocus();
                drain();
                KoreanImeFix fix = new KoreanImeFix();
                fix.install(display, null);
                IME ime = text.getIME();
                set(ime, "commitCount", 1);
                set(ime, "startOffset", 2);
                set(ime, "text", "다");
                Event commit = new Event();
                commit.detail = SWT.COMPOSITION_CHANGED;
                commit.start = 2;
                commit.end = 3;
                commit.text = "다";
                commit.time = 123;
                ime.notifyListeners(SWT.ImeComposition, commit);
                Event key = new Event();
                key.keyCode = SWT.ARROW_RIGHT;
                key.character = '다';
                key.time = 123;
                text.notifyListeners(SWT.KeyDown, key);
                drain();
                eq("가나다\nabc", text.getText());
                eq(3, text.getCaretOffset());
                fix.close();
                shell.dispose();
                drain();
            });
            run("close removes command listener", () -> eq(0, executions.size()));
            System.out.println("PASS " + passed + " integration scenarios (synthetic IME protocol)");
        } finally {
            display.dispose();
        }
    }

    interface Checked {
        void run() throws Exception;
    }

    static void mouseUpAtStart(Fixture fixture) {
        Point location = fixture.text.getLocationAtOffset(0);
        Event mouse = new Event();
        mouse.button = 1;
        mouse.count = 1;
        mouse.time = 124;
        mouse.x = location.x;
        mouse.y = location.y;
        fixture.text.notifyListeners(SWT.MouseUp, mouse);
    }

    static void run(String name, Checked test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }

    static void set(IME ime, String name, Object value) throws Exception {
        Field f = IME.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(ime, value);
    }

    static void drain() {
        int limit = 1000;
        while (limit-- > 0 && display.readAndDispatch()) {
        }
    }
}
