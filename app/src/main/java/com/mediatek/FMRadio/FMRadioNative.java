package com.mediatek.FMRadio;

/**
 * Debug-only shim for the stock MediaTek FM stack.
 *
 * libfmjni.so registers its natives in JNI_OnLoad via
 * FindClass("com/mediatek/FMRadio/FMRadioNative"), so the package and class
 * name are load-bearing. loadLibrary has to run from this class's own static
 * initialiser so FindClass resolves against the right classloader.
 *
 * Declarations mirror the vendor class the registration tables were built
 * against, recovered from the stock launcher. Methods beyond the ones the
 * probe calls are declared for completeness; calling one the library did not
 * register throws UnsatisfiedLinkError.
 */
public final class FMRadioNative {

    /** Null when the library loaded, otherwise the failure it raised. */
    public static final Throwable LOAD_ERROR;

    static {
        Throwable error = null;
        try {
            System.loadLibrary("fmjni");
        } catch (Throwable t) {
            error = t;
        }
        LOAD_ERROR = error;
    }

    private FMRadioNative() {
    }

    // Receiver
    public static native boolean opendev();
    public static native boolean closedev();
    public static native boolean powerup(float freq);
    public static native boolean powerdown(int type);
    public static native boolean tune(float freq);
    public static native boolean tunenew(int a, int b, int c, int d);
    public static native float seek(float freq, boolean isUp);
    public static native int seeknew(int a, int b, int c, int d, int e, int f);
    public static native short[] autoscan();
    public static native short[] scannew(int a, int b, int c);
    public static native boolean stopscan();
    public static native int readRssi();
    public static native int setmute(boolean mute);
    public static native int getchipid();
    public static native int isFMPoweredUp();
    public static native int switchAntenna(int antenna);
    public static native boolean stereoMono();
    public static native boolean setStereoMono(boolean isMono);
    public static native short readCapArray();
    public static native int[] getHardwareVersion();
    public static native boolean setFMViaBTController(boolean viaBt);
    public static native boolean emsetth(int a, int b);
    public static native short[] emcmd(short[] cmd);

    // RDS
    public static native int rdsset(boolean enable);
    public static native short readrds();
    public static native short readRdsBler();
    public static native int isRDSsupport();
    public static native short getPI();
    public static native byte getPTY();
    public static native byte[] getPS();
    public static native byte[] getLRText();
    public static native short activeAF();
    public static native short[] getAFList();
    public static native short activeTA();
    public static native short deactiveTA();

    // Transmitter
    public static native int isTXSupport();
    public static native boolean powerupTX(float freq);
    public static native boolean tuneTX(float freq);
    public static native short[] getTXFreqList(float freq, int a, int b);
    public static native int isRDSTXSupport();
    public static native boolean setRDSTXEnabled(boolean enable);
    public static native boolean setRDSTX(short pi, char[] ps, short[] lrText, int n);
}
