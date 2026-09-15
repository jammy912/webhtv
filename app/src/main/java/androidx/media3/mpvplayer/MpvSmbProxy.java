package androidx.media3.mpvplayer;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.smb.SmbHttpProxy;

/**
 * The player's own {@link SmbHttpProxy} instance.
 *
 * <p>MPV cannot open {@code smb://} directly — libmpv is built with
 * {@code --disable-smb} and ships no libsmbclient — so {@code MpvPlayer}
 * rewrites such URLs to this proxy before loading them.
 *
 * <p>The implementation moved to {@code com.fongmi.android.tv.smb} once the
 * thumbnail pipeline needed the same bridge; this subclass keeps the player's
 * call sites unchanged so the verified playback path stays untouched.
 */
public final class MpvSmbProxy extends SmbHttpProxy {

    /** Returns true when {@code uri} is an SMB source this proxy can serve. */
    public static boolean isSmb(@Nullable String uri) {
        return SmbHttpProxy.isSmb(uri);
    }
}
