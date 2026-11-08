package com.truesim.effects.post;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.scene.render.TextureData;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * A 3D color lookup table authored in a grading tool, as {@link ColorGrading} applies it: over the grading's log
 * encoding of scene light ({@link #encode}), in and out. Read from Adobe's and Resolve's {@code .cube} format
 * ({@code LUT_3D_SIZE}, red fastest); {@link #identity} written as a {@code .cube} is the neutral table to grade from.
 * Tables load as assets: {@link PostTextures#install} registers the {@code .cube} importer, which makes each a 3D
 * texture ({@link #texture}).
 *
 * @param size cells per axis
 * @param values rgb per cell, red fastest, then green, then blue
 */
public record ColorLut(int size, float[] values) {

    /** Middle gray, at the encoding's middle. */
    static final double MIDDLE = 0.18;
    /** The stops the encoding spans. */
    static final double STOPS = 24;
    /** Its toe: 0 encodes to 0. */
    static final double FLOOR = MIDDLE / 4096;

    public ColorLut {
        if (size < 2 || size > 256 || values.length != 3 * size * size * size) {
            throw new IllegalArgumentException("A table of 2 to 256 cells per axis, 3 values each: " + size + ", "
                + values.length);
        }
    }

    /** Scene light {@code c} (0 and up) in the grading's log encoding: 0 at black, 1 at 737, middle gray near 0.5. */
    public static double encode(double c) {
        return Maths.log2((Math.max(c, 0) + FLOOR) / MIDDLE) / STOPS + 0.5;
    }

    /** The encoding's inverse. */
    public static double decode(double x) {
        return Math.max(MIDDLE * Maths.pow(2, (x - 0.5) * STOPS) - FLOOR, 0);
    }

    /** The table that changes nothing. */
    @AllowAllocation("loading or writing a table")
    public static ColorLut identity(int size) {
        float[] values = new float[3 * size * size * size];
        int i = 0;
        for (int b = 0; b < size; b++) {
            for (int g = 0; g < size; g++) {
                for (int r = 0; r < size; r++) {
                    values[i++] = r / (size - 1f);
                    values[i++] = g / (size - 1f);
                    values[i++] = b / (size - 1f);
                }
            }
        }
        return new ColorLut(size, values);
    }

    /** The table of a {@code .cube} file's text: {@code LUT_3D_SIZE}, an optional domain of 0 to 1, rgb lines. */
    @AllowAllocation("loading or writing a table")
    public static ColorLut parseCube(String text) {
        int size = 0;
        float[] values = null;
        int at = 0;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE")) {
                continue;
            }
            if (line.startsWith("LUT_3D_SIZE")) {
                size = Integer.parseInt(line.substring("LUT_3D_SIZE".length()).strip());
                if (size < 2 || size > 256) {
                    throw new IllegalArgumentException("A table of 2 to 256 cells per axis: " + line);
                }
                values = new float[3 * size * size * size];
            } else if (line.startsWith("DOMAIN_MIN") || line.startsWith("DOMAIN_MAX")) {
                String[] v = line.split("\\s+");
                float expected = line.startsWith("DOMAIN_MIN") ? 0 : 1;
                for (int c = 1; c < 4; c++) {
                    if (Float.parseFloat(v[c]) != expected) {
                        throw new IllegalArgumentException("Only a domain of 0 to 1: " + line);
                    }
                }
            } else if (line.startsWith("LUT_1D_SIZE")) {
                throw new IllegalArgumentException("A 3D table, not a 1D one");
            } else {
                if (values == null) {
                    throw new IllegalArgumentException("Values before LUT_3D_SIZE: " + line);
                }
                String[] v = line.split("\\s+");
                if (at + 3 > values.length || v.length < 3) {
                    throw new IllegalArgumentException("More lines than a table of " + size + " cells per axis: "
                        + line);
                }
                for (int c = 0; c < 3; c++) {
                    values[at++] = Float.parseFloat(v[c]);
                }
            }
        }
        if (values == null || at != values.length) {
            throw new IllegalArgumentException("A table of " + size + " cells per axis needs " + (values == null ? 0
                : values.length / 3) + " lines, found " + at / 3);
        }
        return new ColorLut(size, values);
    }

    /** The table as a {@code .cube} file's text. */
    @AllowAllocation("loading or writing a table")
    public String toCube() {
        StringBuilder out = new StringBuilder("# true-sim color grading table: log2((c + 0.18 / 4096) / 0.18) / 24 + "
            + "0.5 in and out\nLUT_3D_SIZE " + size + "\n");
        for (int i = 0; i < values.length; i += 3) {
            out.append(String.format(Locale.ROOT, "%.6f %.6f %.6f%n", values[i], values[i + 1], values[i + 2]));
        }
        return out.toString();
    }

    /**
     * The table as a 3D texture of half floats, linear: {@code size} slices of {@code size} x {@code size}, red along
     * x, green along y, blue across slices, as the shader samples it (trilinear between the cells' centers).
     */
    @AllowAllocation("loading a table")
    public TextureData texture() {
        int cells = size * size * size;
        MemorySegment texels = Arena.ofAuto().allocate(8L * cells);
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < cells; i++) {
            for (int c = 0; c < 3; c++) {
                texels.set(half, 8L * i + 2L * c, Float.floatToFloat16(values[3 * i + c]));
            }
            texels.set(half, 8L * i + 6, Float.floatToFloat16(1));
        }
        return new TextureData(size, size, TextureData.ColorSpace.LINEAR, TextureData.Format.RGBA16F, texels, size);
    }
}
