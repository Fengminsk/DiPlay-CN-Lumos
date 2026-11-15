package com.ucar.vehiclesdk.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Shadows the SDK's same-named class (classpath shadowing). The bundled SDK resolves
 * {@code com.ucar.sdk.R.drawable.ucar}, which is not part of this APK, so its original
 * {@code decodeResource} returns null and {@code Bitmap.compress} throws during every clientHello.
 *
 * <p>The fallback asset is {@code assets/ic_ucar_default.png} from the icoca module.</p>
 */
public class BitmapUtil {
    private static final String FALLBACK_ASSET = "ic_ucar_default.png";

    public static byte[] decodeResource(Context context, int id) {
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeResource(context.getResources(), id);
        } catch (Throwable ignored) {
            bitmap = null;
        }
        if (bitmap == null) {
            InputStream in = null;
            try {
                in = context.getAssets().open(FALLBACK_ASSET);
                bitmap = BitmapFactory.decodeStream(in);
            } catch (Throwable ignored) {
                bitmap = null;
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (bitmap == null) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }
}
