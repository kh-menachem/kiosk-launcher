package com.mk.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/**
 * Where a silent install reports back.
 *
 * <p>Nothing has to be done with the answer - a device owner's install needs no
 * approval - but an install that fails otherwise fails invisibly, and an app
 * that never appears with no reason given is a bad afternoon.
 */
public class InstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(LauncherPolicy.TAG, "installed " + pkg);
            return;
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Only reachable when this app is not device owner. Nobody may be
            // standing at a kiosk to answer, so say why rather than popping a
            // dialog into an empty room.
            Log.w(LauncherPolicy.TAG, "install of " + pkg
                    + " wants confirmation - not device owner, so it cannot be silent");
            return;
        }
        Log.w(LauncherPolicy.TAG, "install of " + pkg + " failed: status "
                + status + (message == null ? "" : (" - " + message)));
    }
}
