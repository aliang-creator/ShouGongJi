package com.sg.shougongji.ui;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.Carbon;
import com.sun.jna.ptr.PointerByReference;

final class MacTimerHotkeys implements AutoCloseable {
    private static final int KEYBOARD_EVENT = 0x6b657962; // 'keyb'
    private static final int HOTKEY_PRESSED = 5;
    private static final int DIRECT_OBJECT = 0x2d2d2d2d; // '----'
    private static final int HOTKEY_ID = 0x686b6964; // 'hkid'
    private static final int SIGNATURE = 0x53474a69; // 'SGJi'
    private static final int NOT_HANDLED = -9874;
    private static final int EXCLUSIVE = 1;
    private static final int SPACE = 0x31;
    private static final int P = 0x23;
    private static final int OPEN = 1;
    private static final int PAUSE = 2;

    private final ApplicationEvents carbon;
    private final Runnable open;
    private final Runnable pause;
    private final Carbon.EventHandlerProcPtr callback = this::handle;
    private final PointerByReference handler = new PointerByReference();
    private final PointerByReference openHotkey = new PointerByReference();
    private final PointerByReference pauseHotkey = new PointerByReference();

    private MacTimerHotkeys(ApplicationEvents carbon, Runnable open, Runnable pause) {
        this.carbon = carbon;
        this.open = open;
        this.pause = pause;
    }

    static MacTimerHotkeys register(Runnable open, Runnable pause) {
        MacTimerHotkeys keys = new MacTimerHotkeys(Native.load("Carbon", ApplicationEvents.class), open, pause);
        try {
            Carbon.EventTypeSpec type = new Carbon.EventTypeSpec();
            type.eventClass = KEYBOARD_EVENT;
            type.eventKind = HOTKEY_PRESSED;
            check(keys.carbon.InstallEventHandler(keys.carbon.GetApplicationEventTarget(), keys.callback, 1,
                    new Carbon.EventTypeSpec[]{type}, null, keys.handler));
            Pointer dispatcher = keys.carbon.GetEventDispatcherTarget();
            keys.registerKey(dispatcher, SPACE, OPEN, keys.openHotkey);
            keys.registerKey(dispatcher, P, PAUSE, keys.pauseHotkey);
            return keys;
        } catch (RuntimeException | LinkageError failure) {
            keys.close();
            throw failure;
        }
    }

    private void registerKey(Pointer target, int key, int action, PointerByReference reference) {
        Carbon.EventHotKeyID.ByValue id = new Carbon.EventHotKeyID.ByValue();
        id.signature = SIGNATURE;
        id.id = action;
        check(carbon.RegisterEventHotKey(key, Carbon.controlKey | Carbon.shiftKey, id, target, EXCLUSIVE, reference));
    }

    private int handle(Pointer next, Pointer event, Pointer userData) {
        Carbon.EventHotKeyID id = new Carbon.EventHotKeyID();
        if (carbon.GetEventParameter(event, DIRECT_OBJECT, HOTKEY_ID, null, id.size(), null, id) != 0
                || id.signature != SIGNATURE) {
            return NOT_HANDLED;
        }
        switch (id.id) {
            case OPEN -> open.run();
            case PAUSE -> pause.run();
            default -> { return NOT_HANDLED; }
        }
        return 0;
    }

    @Override
    public void close() {
        unregister(openHotkey);
        unregister(pauseHotkey);
        if (handler.getValue() != null) {
            carbon.RemoveEventHandler(handler.getValue());
            handler.setValue(null);
        }
    }

    private void unregister(PointerByReference reference) {
        if (reference.getValue() != null) {
            carbon.UnregisterEventHotKey(reference.getValue());
            reference.setValue(null);
        }
    }

    private static void check(int status) {
        if (status != 0) {
            throw new IllegalStateException("macOS hotkey registration failed (" + status + ")");
        }
    }

    static void activateApplication() {
        ObjectiveC objc = ObjectiveC.INSTANCE;
        Pointer app = objc.objc_msgSend(objc.objc_getClass("NSApplication"), objc.sel_registerName("sharedApplication"));
        Pointer activate = objc.sel_registerName("activate");
        if (objc.objc_msgSend(app, objc.sel_registerName("respondsToSelector:"), activate) != 0) {
            NativeLibrary.getInstance("objc").getFunction("objc_msgSend").invokeVoid(new Object[]{app, activate});
        } else {
            objc.objc_msgSend(app, objc.sel_registerName("activateIgnoringOtherApps:"), (byte) 1);
        }
    }

    public interface ApplicationEvents extends Carbon {
        Pointer GetApplicationEventTarget();
    }

    public interface ObjectiveC extends Library {
        ObjectiveC INSTANCE = Native.load("objc", ObjectiveC.class);

        Pointer objc_getClass(String name);
        Pointer sel_registerName(String name);
        Pointer objc_msgSend(Pointer receiver, Pointer selector);
        byte objc_msgSend(Pointer receiver, Pointer selector, Pointer value);
        void objc_msgSend(Pointer receiver, Pointer selector, byte value);
    }
}
