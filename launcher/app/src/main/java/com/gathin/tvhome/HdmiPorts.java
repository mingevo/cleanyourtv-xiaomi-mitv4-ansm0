package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import java.util.ArrayList;
import java.util.List;

/** Whether an HDMI port listed in the app library has a device on it, so its jack icon can be lit or left grey the
 *  way the TV's own source panel draws it: white for a port with a sink, grey for an empty one. */
final class HdmiPorts {
    /** The "connected" mark: a filled dot in the green the source panel uses. */
    private static final int DOT_COLOR = 0xFF3ADB6A;

    private HdmiPorts() {
    }

    /** The framework input ids (".../HW6") that `input_sources` maps to this vendor source number. An unmapped
     *  source, or no mapping list at all, means the port cannot be judged. */
    private static List<String> hardwareIds(Context ctx, int source) {
        List<String> hardware = new ArrayList<>();
        String[] mappings;
        try {
            mappings = ctx.getResources().getStringArray(R.array.input_sources);
        } catch (Resources.NotFoundException missing) {
            return hardware;
        }
        for (String mapping : mappings) {
            if (mapping == null) continue;
            int equals = mapping.indexOf('=');
            if (equals <= 0) continue;
            String hardwareName = mapping.substring(0, equals).trim();
            if (hardwareName.isEmpty()) continue;
            try {
                if (Integer.parseInt(mapping.substring(equals + 1).trim()) == source) hardware.add(hardwareName);
            } catch (NumberFormatException ignored) {
                // "-" hides an input; anything else unparsable is simply not this source.
            }
        }
        return hardware;
    }

    /** True when a sink answers on the port.
     *
     *  This firmware (DroidLogic tvinput on a MiTV4) registers an HDMI input with TvInputManager only once a device
     *  is connected: with one set on HDMI 2 the input list holds Hdmi2InputService/HW6 alone, and unplugging it
     *  leaves no HDMI input at all while the HDMI 1/2/3 services keep running unregistered. So a configured port
     *  that no registered HDMI input names is an empty one, and a port that does have an input is decided by that
     *  input's own state.
     *
     *  Where nothing can be judged - no TvInputManager, a list with no input in it (the TV has no tvinput at all),
     *  no mapping for the source - the port stays lit rather than greying out one that works. */
    static boolean connected(Context ctx, int source) {
        List<String> hardware = hardwareIds(ctx, source);
        if (hardware.isEmpty()) return true;
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return true;
        List<TvInputInfo> inputs;
        try {
            inputs = tim.getTvInputList();
        } catch (RuntimeException denied) {
            return true;
        }
        // An empty list means the service has nothing registered at all, not that every port is empty.
        if (inputs == null || inputs.isEmpty()) return true;
        for (TvInputInfo info : inputs) {
            if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
            String id = info.getId();
            if (id == null) continue;
            String last = id.substring(id.lastIndexOf('/') + 1);
            if (!hardware.contains(last)) continue;
            try {
                if (tim.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED) return true;
            } catch (RuntimeException unknown) {
                return true;
            }
        }
        // The TV registers inputs (the tuner is in the list) but none of them is this HDMI port: nothing is on it.
        return false;
    }

    /** The HDMI jack for a port: `ic_hdmi` while a device is connected, the grey `ic_hdmi_dim` while nothing is.
     *  Two bitmaps rather than one tinted in code, because drawables loaded from the same resource share their
     *  state and a filter set on one port's copy would grey out every port. */
    static Drawable icon(Context ctx, int source) {
        return ctx.getDrawable(connected(ctx, source) ? R.drawable.ic_hdmi : R.drawable.ic_hdmi_dim);
    }

    /** True when the source number is one of the HDMI ports the library lists ("HDMI 2:24" -> 24), so a tile can
     *  take the connected mark without one also landing on an AV or tuner row. */
    static boolean isLibraryPort(Context ctx, int source) {
        String[] ports;
        try {
            ports = ctx.getResources().getStringArray(R.array.library_inputs);
        } catch (Resources.NotFoundException missing) {
            return false;
        }
        for (String port : ports) {
            if (port == null) continue;
            int colon = port.lastIndexOf(':');
            if (colon < 0) continue;
            try {
                if (Integer.parseInt(port.substring(colon + 1).trim()) == source) return true;
            } catch (NumberFormatException ignored) {
                // An unparsable port simply cannot be this source.
            }
        }
        return false;
    }

    /** True when the tile for this input should carry the connected mark. */
    static boolean showsDot(Context ctx, int source) {
        return source >= 0 && isLibraryPort(ctx, source) && connected(ctx, source);
    }

    /** The tile text for an input: a drawn dot right after the name while a device answers on the port - the mark
     *  the TV's own source panel puts next to the connected input. The dot is a ReplacementSpan rather than a
     *  "●" character or a compound drawable: the character hangs on the baseline and reads low, while a compound
     *  drawable pins itself to the view's right edge and ends up far from centred text. The span controls both its
     *  own width (so the gap is a fixed part of the text) and its height, drawn on the line's centre. */
    static CharSequence tileName(Context ctx, String name, int source, float textSize) {
        if (name == null) return null;
        if (!showsDot(ctx, source)) return name;
        SpannableStringBuilder out = new SpannableStringBuilder(name).append(' ');
        out.setSpan(new Dot(textSize * 0.6f, textSize * 0.30f), name.length(), out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    /** A filled circle on the middle of the line, with the gap to the text before it baked into its width. */
    private static final class Dot extends ReplacementSpan {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float diameter;
        private final float gap;

        Dot(float diameter, float gap) {
            this.diameter = diameter;
            this.gap = gap;
            paint.setColor(DOT_COLOR);
        }

        @Override
        public int getSize(Paint base, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            return Math.round(gap + diameter);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int baseline,
                int bottom, Paint base) {
            canvas.drawCircle(x + gap + diameter / 2f, (top + bottom) / 2f, diameter / 2f, paint);
        }
    }
}
