package com.sg.shougongji.ui;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.util.concurrent.CompletableFuture;

final class WindowsTimerHotkeys implements TimerHotkeys {
    private static final System.Logger LOGGER = System.getLogger(WindowsTimerHotkeys.class.getName());
    private static final int OPEN = 1;
    private static final int PAUSE = 2;
    private static final int MODIFIERS = WinUser.MOD_CONTROL | WinUser.MOD_ALT | WinUser.MOD_NOREPEAT;
    private static final int PM_NOREMOVE = 0;

    private final User32 windows;
    private final int threadId;
    private final Thread messageThread;

    private WindowsTimerHotkeys(User32 windows, int threadId, Thread messageThread) {
        this.windows = windows;
        this.threadId = threadId;
        this.messageThread = messageThread;
    }

    static WindowsTimerHotkeys register(int openKey, int pauseKey, Runnable open, Runnable pause) {
        User32 windows = User32.INSTANCE;
        CompletableFuture<Integer> ready = new CompletableFuture<>();
        Thread thread = Thread.ofPlatform().daemon().name("windows-timer-hotkeys").start(
                () -> listen(windows, openKey, pauseKey, open, pause, ready));
        return new WindowsTimerHotkeys(windows, ready.join(), thread);
    }

    private static void listen(User32 windows, int openKey, int pauseKey, Runnable open, Runnable pause,
                               CompletableFuture<Integer> ready) {
        boolean openRegistered = false;
        boolean pauseRegistered = false;
        try {
            WinUser.MSG message = new WinUser.MSG();
            // 注册、收消息和注销都在同一个 Win32 线程上。
            windows.PeekMessage(message, null, 0, 0, PM_NOREMOVE);
            int threadId = Kernel32.INSTANCE.GetCurrentThreadId();
            registerKey(windows, OPEN, openKey);
            openRegistered = true;
            registerKey(windows, PAUSE, pauseKey);
            pauseRegistered = true;
            ready.complete(threadId);

            int result;
            while ((result = windows.GetMessage(message, null, 0, 0)) > 0) {
                if (message.message == WinUser.WM_HOTKEY) {
                    switch (message.wParam.intValue()) {
                        case OPEN -> open.run();
                        case PAUSE -> pause.run();
                        default -> { }
                    }
                }
            }
            if (result < 0) {
                throw new IllegalStateException("Windows hotkey message loop failed (" + Native.getLastError() + ")");
            }
        } catch (RuntimeException | LinkageError failure) {
            if (!ready.completeExceptionally(failure)) {
                LOGGER.log(System.Logger.Level.WARNING, "Windows timer shortcuts stopped", failure);
            }
        } finally {
            if (pauseRegistered) {
                windows.UnregisterHotKey(null, PAUSE);
            }
            if (openRegistered) {
                windows.UnregisterHotKey(null, OPEN);
            }
        }
    }

    private static void registerKey(User32 windows, int id, int key) {
        if (!windows.RegisterHotKey(null, id, MODIFIERS, key)) {
            throw new IllegalStateException("Windows hotkey registration failed (" + Native.getLastError() + ")");
        }
    }

    static void activateWindow(String title) {
        User32 windows = User32.INSTANCE;
        int processId = Kernel32.INSTANCE.GetCurrentProcessId();
        IntByReference owner = new IntByReference();
        char[] caption = new char[128];
        windows.EnumWindows((window, unused) -> {
            if (windows.GetWindowThreadProcessId(window, owner) == 0 || owner.getValue() != processId) {
                return true;
            }
            int length = windows.GetWindowText(window, caption, caption.length);
            if (!title.equals(new String(caption, 0, length))) {
                return true;
            }
            windows.SetForegroundWindow(window);
            return false;
        }, null);
    }

    @Override
    public void close() {
        if (messageThread.isAlive() && windows.PostThreadMessage(threadId, WinUser.WM_QUIT, new WPARAM(), new LPARAM()) != 0) {
            try {
                messageThread.join(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
