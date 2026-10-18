package samsungcompat;

import android.telephony.TelephonyManager;

/** Non-privileged fallbacks for call-state features used only to auto-pause playback. */
public final class Phone {
    private Phone() {}
    public static int getCallState(TelephonyManager ignored) { return TelephonyManager.CALL_STATE_IDLE; }
}
