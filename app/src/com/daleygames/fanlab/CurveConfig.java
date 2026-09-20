package com.daleygames.fanlab;

/**
 * The fan curve: a piecewise-linear map from LED temperature to duty, one duty column per
 * brightness tier, plus the hysteresis and slew-rate parameters. Pure Java.
 */
public final class CurveConfig {

    /** rgblevel 1 (Eco) and 4 (Super Eco) share this column, as the stock ladder does. */
    public static final int PROFILE_LOW = 0;
    /** rgblevel 2 (Normal). */
    public static final int PROFILE_NORMAL = 1;
    /** rgblevel 3 (Presentation). */
    public static final int PROFILE_HIGH = 2;
    public static final int PROFILES = 3;

    public static final String[] PROFILE_NAMES = {"Eco / Super Eco", "Normal", "Presentation"};

    /** The curves on offer: two families of the same four rungs, quietest rung first. Which family is selectable is decided by the LED drive override - see {@link #curveForDrive}. */
    public static final String[] PRESET_NAMES = {
            "Quiet", "Balanced", "Cool", "Cold",
            "Bright Quiet", "Bright Balanced", "Bright Cool", "Bright Cold",
    };

    /** Rungs per family: preset {@code i} and preset {@code i + RUNGS} are the same rung on the two drive levels. */
    public static final int RUNGS = 4;

    /** {@link #presetOf} for a curve that is none of the presets. Not a destination. */
    public static final int PRESET_CUSTOM = -1;

    /** How each preset's duty rows are built out of Quiet's. */
    public abstract static class PresetShape {

        /** Knee 0, degrees C. Every knee above it is Quiet's, in every preset. */
        public final int floorEdgeC;

        PresetShape(int floorEdgeC) {
            this.floorEdgeC = floorEdgeC;
        }

        /** The duty row this preset carries in {@code profile}, given Quiet's row. */
        public abstract int[] row(int profile, int[] quiet);
    }

    /** Quiet plus a constant at every knee above the floor, in all three columns, clipped at the 83 ceiling. */
    private static final class OffsetAboveFloor extends PresetShape {
        private final int add;

        OffsetAboveFloor(int floorEdgeC, int add) {
            super(floorEdgeC);
            this.add = add;
        }

        @Override
        public int[] row(int profile, int[] quiet) {
            int[] r = new int[quiet.length];
            r[0] = quiet[0];
            for (int k = 1; k < quiet.length; k++) {
                r[k] = Math.min(83, quiet[k] + add);
            }
            return r;
        }
    }

    /** A standard rung with Presentation's row drawn by hand: the shape of every Bright preset. */
    private static final class DrawnHighRow extends PresetShape {
        private final PresetShape standard;
        private final int[] high;

        DrawnHighRow(PresetShape standard, int[] high) {
            this(standard, high, standard.floorEdgeC);
        }

        /** As above, but with a floor edge of its own. Only Bright Cool needs one. */
        DrawnHighRow(PresetShape standard, int[] high, int floorEdgeC) {
            super(floorEdgeC);
            this.standard = standard;
            this.high = high;
        }

        @Override
        public int[] row(int profile, int[] quiet) {
            return profile == PROFILE_HIGH ? high.clone() : standard.row(profile, quiet);
        }
    }

    private static final PresetShape QUIET = new OffsetAboveFloor(47, 0);
    private static final PresetShape BALANCED = new OffsetAboveFloor(47, 5);
    private static final PresetShape COOL = new OffsetAboveFloor(47, 10);
    private static final PresetShape COLD = new OffsetAboveFloor(43, 15);

    /** One shape per preset, in {@link #PRESET_NAMES} order. */
    public static final PresetShape[] PRESET_SHAPES = {
            QUIET,
            BALANCED,
            COOL,
            COLD,
            new DrawnHighRow(QUIET, new int[]{30, 38, 50, 62, 76, 83}),     // Bright Quiet
            new DrawnHighRow(BALANCED, new int[]{30, 43, 55, 67, 81, 83}),  // Bright Balanced
            new DrawnHighRow(COOL, new int[]{30, 48, 60, 72, 83, 83}, 45),      // Bright Cool
            new DrawnHighRow(COLD, new int[]{30, 53, 65, 77, 83, 83}),      // Bright Cold
    };

    /**
     * The presets, encoded: four rungs run twice, the standard family then the Bright one.
     *
     * The two families are never both on offer: {@link #curveForDrive} gates it, because a
     * standard rung at drive 90 in a 28 C room settles past the 60 C override trip, which drops
     * the drive silently. The first entry is byte-identical to what {@link #setDefaults()}
     * encodes, so choosing Quiet and resetting to defaults are the same act.
     */
    public static final String[] PRESETS = {
            // Quiet: floor 30 to 47 C, shelf 38 to 40 across 51-55 C, backstop to 83 by 70 C.
            "v1,47,51,55,60,66,70,30,38,40,50,68,83,30,38,40,50,68,83,30,38,40,50,68,83,"
                    + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5",
            // Balanced: Quiet + 5 above the floor, clipped at 83.
            "v1,47,51,55,60,66,70,30,43,45,55,73,83,30,43,45,55,73,83,30,43,45,55,73,83,"
                    + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5",
            // Cool: Quiet + 10 above the floor, clipped at 83.
            "v1,47,51,55,60,66,70,30,48,50,60,78,83,30,48,50,60,78,83,30,48,50,60,78,83,"
                    + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5",
            // Cold: Quiet + 15 above the floor. Floor edge 43 C, not 47, so the 47-51 C rise is 2.87 duty/C rather than a hunting 5.75.
            "v1,43,51,55,60,66,70,30,53,55,65,83,83,30,53,55,65,83,83,30,53,55,65,83,83,"
                    + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5",
            // The Bright Curve family: each rung is its Curve counterpart with Presentation's row plus
            // 0, 0, 10, 12, 8, 0 at the six knees, clipped at 83, and everything else unchanged.
            // Bright Quiet: Quiet's 30/38 to 51 C, then 50 at 55 and 62 at 60.
            "v1,47,51,55,60,66,70,30,38,40,50,68,83,30,38,40,50,68,83,30,38,50,62,76,83,"
                    + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5",
            // Bright Balanced: Balanced's 30/43, then 55 at 55 C and 67 at 60.
            "v1,47,51,55,60,66,70,30,43,45,55,73,83,30,43,45,55,73,83,30,43,55,67,81,83,"
                    + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5",
            // Bright Cool: Cool's 30/48, then 60 at 55 C and 72 at 60; floor edge 45 C.
            "v1,45,51,55,60,66,70,30,48,50,60,78,83,30,48,50,60,78,83,30,48,60,72,83,83,"
                    + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5",
            // Bright Cold: Cold's floor edge of 43 C and its 30/53, then 65 at 55 C and 77 at 60.
            "v1,43,51,55,60,66,70,30,53,55,65,83,83,30,53,55,65,83,83,30,53,65,77,83,83,"
                    + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5",
    };

    /** Fields the optional guard block adds to the encoded line. */
    public static final int GUARD_FIELDS = 5;

    /** Number of knee points. Fixed so the D-pad editor has a fixed shape. */
    public static final int POINTS = 6;

    /** Knee temperatures, degrees C, ascending. Editable. */
    public final int[] tempC = new int[POINTS];

    /** Duty at each knee, per profile. Editable. */
    public final int[][] duty = new int[PROFILES][POINTS];

    /** Deadband, degrees C. The curve input rises with the measured temperature at once, but follows it down only after it has fallen this far. */
    public double hysteresisC;

    /** Maximum rise, duty points per second. */
    public double slewUpPerSec;

    /** Maximum fall, duty points per second. */
    public double slewDownPerSec;

    /** Duty commanded while the light engine is off (led_status == 0). Stock uses 10. */
    public int idleDuty;

    /** Hard floor applied after the curve, never below {@link FanIo#MIN_DUTY}. */
    public int minDuty;

    /** Hard ceiling applied after the curve, never above {@link FanIo#MAX_DUTY}. */
    public int maxDuty;

    /** Is the SoC guard armed? On by default; it is a backstop, not a second curve. */
    public boolean socGuardEnabled;

    /** SoC die temperature at which the guard starts adding fan, degrees C, read from thermal_zone0. */
    public int socGuardStartC;

    /** Extra duty points per degree above {@link #socGuardStartC}. Additive to the curve's own output, not an absolute floor. */
    public double socGuardGainPerC;

    /** Ceiling on the guarded duty. Not the hardware maximum: above about 62 the fan buys very little die temperature for the noise. */
    public int socGuardMaxDuty;

    /** Deadband for the guard's input, degrees C. Wider than {@link #hysteresisC} because the die sensor is noisier. */
    public double socGuardHystC;

    public CurveConfig() {
        setDefaults();
    }

    /** The measured curve, as shipped: a fresh install is correct without configuring anything. */
    public void setDefaults() {
        int[] t = {47, 51, 55, 60, 66, 70};
        System.arraycopy(t, 0, tempC, 0, POINTS);

        int[] all = {30, 38, 40, 50, 68, 83};
        System.arraycopy(all, 0, duty[PROFILE_LOW], 0, POINTS);
        System.arraycopy(all, 0, duty[PROFILE_NORMAL], 0, POINTS);
        System.arraycopy(all, 0, duty[PROFILE_HIGH], 0, POINTS);

        // 0.8 C, measured: at 0.5 the duty dithered by a point at 24 C ambient, and at 0.8 a six-minute hardware test saw zero duty changes.
        hysteresisC = 0.8;
        // Slow on purpose: one duty point every 4 s rising, every 8 s falling, is inaudible as a change.
        slewUpPerSec = 0.25;
        slewDownPerSec = 0.12;
        idleDuty = 10;
        // 30 is inaudible even up close, and was held 12 minutes under the hottest load the machine can produce with no fan-stall shutdown.
        minDuty = 30;
        maxDuty = 83;

        // The SoC guard. The fan is driven by the LED thermistor and cannot see the die at all, so
        // thermal_zone0 (pll, throttled at 75 C) gets a backstop: knee 70 C, 2.0 duty points per
        // degree, capped at 62 where the fan's remaining authority over the die is about 3 C.
        socGuardEnabled = true;
        socGuardStartC = 70;
        socGuardGainPerC = 2.0;
        socGuardMaxDuty = 62;
        socGuardHystC = 1.5;
    }

    /** Map an rgblevel reading onto a profile index, mirroring the stock packed-switch. */
    public static int profileForLevel(int rgblevel) {
        switch (rgblevel) {
            case 1:
            case 4:
                return PROFILE_LOW;
            case 2:
                return PROFILE_NORMAL;
            case 3:
                return PROFILE_HIGH;
            default:
                // Unknown or unreadable brightness tier: fail safe is high, so use the hottest profile.
                return PROFILE_HIGH;
        }
    }

    /** The curve for a preset. An index from nowhere falls back to the quietest. */
    public static CurveConfig preset(int i) {
        return decode(PRESETS[(i < 0 || i >= PRESETS.length) ? 0 : i]);
    }

    /** Which preset a stored curve is, by matching the encoded line. A real edit reads as {@link #PRESET_CUSTOM}, correctly. */
    public static int presetOf(String encoded) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].equals(encoded)) {
                return i;
            }
        }
        return PRESET_CUSTOM;
    }

    /** The name to show for a preset index, or "Custom" for anything that is not one. */
    public static String presetName(int i) {
        return (i < 0 || i >= PRESET_NAMES.length) ? "Custom" : PRESET_NAMES[i];
    }

    /** Is this one of the four presets drawn for the LED drive override? False for {@link #PRESET_CUSTOM}. */
    public static boolean isBrightPreset(int preset) {
        return preset >= RUNGS && preset < PRESET_NAMES.length;
    }

    /** Which rung of its family a preset is - 0 quietest, {@link #RUNGS} - 1 coldest - or -1 for a curve in neither family. */
    public static int rungOf(int preset) {
        return (preset < 0 || preset >= PRESET_NAMES.length) ? -1 : preset % RUNGS;
    }

    /** The presets a given drive state allows, quietest rung first. */
    public static int[] presetsFor(boolean driveOn) {
        int[] r = new int[RUNGS];
        for (int rung = 0; rung < RUNGS; rung++) {
            r[rung] = driveOn ? RUNGS + rung : rung;
        }
        return r;
    }

    /** The four for the stock LED drive. */
    public static int[] standardPresets() {
        return presetsFor(false);
    }

    /** The four for the LED drive override. */
    public static int[] brightPresets() {
        return presetsFor(true);
    }

    /** The same rung in the family {@code driveOn} selects. A preset already in that family, or one with no rung at all, is returned unchanged. */
    public static int counterpartOf(int preset, boolean driveOn) {
        int rung = rungOf(preset);
        return rung < 0 ? preset : (driveOn ? RUNGS + rung : rung);
    }

    /**
     * The curve to store for a given drive state: the same rung in the family that state allows.
     *
     * This is the gate. Both the screen and the broadcast path move the override through
     * {@code Prefs}, and {@code Prefs} moves the curve through here, so a standard rung cannot be
     * left running on the raised drive. A hand-edited curve has no counterpart and is returned
     * untouched; the caller says so out loud instead.
     */
    public static String curveForDrive(String encoded, boolean driveOn) {
        int preset = presetOf(encoded);
        return preset == PRESET_CUSTOM ? encoded : PRESETS[counterpartOf(preset, driveOn)];
    }

    /** Why a preset may not be applied in the current drive state, or null if it may. A refusal rather than a silent substitution. */
    public static String wrongFamilyRefusal(int preset, boolean driveOn) {
        if (rungOf(preset) < 0 || isBrightPreset(preset) == driveOn) {
            return null;
        }
        return PRESET_NAMES[preset] + " is a " + (driveOn ? "Curve" : "Bright Curve")
                + " preset and the LED drive is " + (driveOn ? "on" : "off")
                + "; use " + PRESET_NAMES[counterpartOf(preset, driveOn)]
                + " or turn the drive " + (driveOn ? "off" : "on");
    }

    /** A preset by the name it is shown under, or {@link #PRESET_CUSTOM}. Case, spaces and punctuation are ignored. */
    public static int presetNamed(String name) {
        String want = squash(name);
        if (want.length() == 0) {
            return PRESET_CUSTOM;
        }
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            if (squash(PRESET_NAMES[i]).equals(want)) {
                return i;
            }
        }
        return PRESET_CUSTOM;
    }

    /** Letters and digits only, lower case: the form two spellings of a name agree on. */
    private static String squash(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                sb.append((char) (ch - 'A' + 'a'));
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** The names a drive state accepts, as a sentence: "quiet, balanced, cool or cold". Built from the list rather than typed out. */
    public static String familyWords(boolean driveOn) {
        int[] family = presetsFor(driveOn);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < family.length; i++) {
            if (i > 0) {
                sb.append(i == family.length - 1 ? " or " : ", ");
            }
            sb.append(PRESET_NAMES[family[i]].toLowerCase());
        }
        return sb.toString();
    }

    /** Repair anything a user or a corrupt preferences file could have put in here. Called on every load and after every edit, so the control loop can assume sanity. */
    public void sanitise() {
        for (int i = 0; i < POINTS; i++) {
            if (tempC[i] < 0) {
                tempC[i] = 0;
            }
            if (tempC[i] > 100) {
                tempC[i] = 100;
            }
        }
        // keep the knees strictly ascending, so interpolation can never divide by zero
        for (int i = 1; i < POINTS; i++) {
            if (tempC[i] <= tempC[i - 1]) {
                tempC[i] = tempC[i - 1] + 1;
            }
        }
        if (tempC[POINTS - 1] > 100) {
            for (int i = POINTS - 1; i >= 0; i--) {
                if (tempC[i] > 100 - (POINTS - 1 - i)) {
                    tempC[i] = 100 - (POINTS - 1 - i);
                }
            }
        }
        if (minDuty < FanIo.MIN_DUTY) {
            minDuty = FanIo.MIN_DUTY;
        }
        if (maxDuty > FanIo.MAX_DUTY) {
            maxDuty = FanIo.MAX_DUTY;
        }
        if (maxDuty < minDuty) {
            maxDuty = minDuty;
        }
        if (idleDuty < FanIo.MIN_DUTY) {
            idleDuty = FanIo.MIN_DUTY;
        }
        if (idleDuty > FanIo.MAX_DUTY) {
            idleDuty = FanIo.MAX_DUTY;
        }
        for (int p = 0; p < PROFILES; p++) {
            for (int i = 0; i < POINTS; i++) {
                if (duty[p][i] < FanIo.MIN_DUTY) {
                    duty[p][i] = FanIo.MIN_DUTY;
                }
                if (duty[p][i] > FanIo.MAX_DUTY) {
                    duty[p][i] = FanIo.MAX_DUTY;
                }
            }
        }
        if (!(hysteresisC >= 0.0) || hysteresisC > 20.0) {
            hysteresisC = 1.5;
        }
        if (!(slewUpPerSec > 0.0) || slewUpPerSec > 100.0) {
            slewUpPerSec = 5.0;
        }
        if (!(slewDownPerSec > 0.0) || slewDownPerSec > 100.0) {
            slewDownPerSec = 1.0;
        }
        sanitiseGuard();
    }

    /** Repair the SoC guard: ascending knees, legal duties, and duties that never decrease as the die gets hotter. */
    private void sanitiseGuard() {
        if (socGuardStartC < 0) {
            socGuardStartC = 0;
        }
        if (socGuardStartC > 120) {
            socGuardStartC = 120;
        }
        // A negative or absent gain is a corrupt value, not "off" - socGuardEnabled is what disarms the guard.
        if (!(socGuardGainPerC > 0.0) || socGuardGainPerC > 20.0) {
            socGuardGainPerC = 2.0;
        }
        if (socGuardMaxDuty < FanIo.MIN_DUTY) {
            socGuardMaxDuty = FanIo.MIN_DUTY;
        }
        if (socGuardMaxDuty > maxDuty) {
            socGuardMaxDuty = maxDuty;
        }
        if (!(socGuardHystC >= 0.0) || socGuardHystC > 20.0) {
            socGuardHystC = 1.5;
        }
    }

    /** Extra duty points the guard asks for at a given SoC die temperature, on top of what the curve wants. Zero below the knee; an unreadable sensor returns 0, and the caller applies {@link #socGuardMaxDuty}. */
    public int socGuardBoost(double socCelsius) {
        if (!socGuardEnabled || Double.isNaN(socCelsius) || Double.isInfinite(socCelsius)) {
            return 0;
        }
        double over = socCelsius - socGuardStartC;
        if (over <= 0.0) {
            return 0;
        }
        int boost = (int) Math.round(over * socGuardGainPerC);
        return boost < 0 ? 0 : boost;
    }

    /** The curve itself: piecewise-linear interpolation, flat outside the end knees. Pure function of the config. */
    public int dutyAt(int profile, double celsius) {
        if (profile < 0 || profile >= PROFILES) {
            profile = PROFILE_HIGH;
        }
        int[] col = duty[profile];
        double v;
        if (celsius <= tempC[0]) {
            v = col[0];
        } else if (celsius >= tempC[POINTS - 1]) {
            v = col[POINTS - 1];
        } else {
            v = col[POINTS - 1];
            for (int i = 1; i < POINTS; i++) {
                if (celsius <= tempC[i]) {
                    double span = tempC[i] - tempC[i - 1];
                    double f = span <= 0 ? 1.0 : (celsius - tempC[i - 1]) / span;
                    v = col[i - 1] + f * (col[i] - col[i - 1]);
                    break;
                }
            }
        }
        int d = (int) Math.round(v);
        if (d < minDuty) {
            d = minDuty;
        }
        if (d > maxDuty) {
            d = maxDuty;
        }
        return FanIo.clampForUi(d);
    }

    /** Serialise to a single line, for SharedPreferences. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append("v1");
        for (int i = 0; i < POINTS; i++) {
            sb.append(',').append(tempC[i]);
        }
        for (int p = 0; p < PROFILES; p++) {
            for (int i = 0; i < POINTS; i++) {
                sb.append(',').append(duty[p][i]);
            }
        }
        sb.append(',').append(hysteresisC);
        sb.append(',').append(slewUpPerSec);
        sb.append(',').append(slewDownPerSec);
        sb.append(',').append(idleDuty);
        sb.append(',').append(minDuty);
        sb.append(',').append(maxDuty);
        // Appended after the v1 fields on purpose: decode() accepts a longer line, so a curve saved before the guard existed still loads with the default guard.
        sb.append(',').append(socGuardEnabled ? 1 : 0);
        sb.append(',').append(socGuardStartC);
        sb.append(',').append(socGuardGainPerC);
        sb.append(',').append(socGuardMaxDuty);
        sb.append(',').append(socGuardHystC);
        return sb.toString();
    }

    /** Parse a line produced by {@link #encode()}. Anything unparseable falls back to the defaults; a broken config must never leave the fan uncontrolled. */
    public static CurveConfig decode(String s) {
        CurveConfig c = new CurveConfig();
        if (s == null) {
            return c;
        }
        try {
            String[] f = s.split(",");
            int need = 1 + POINTS + PROFILES * POINTS + 6;
            if (f.length < need || !"v1".equals(f[0])) {
                return c;
            }
            int k = 1;
            for (int i = 0; i < POINTS; i++) {
                c.tempC[i] = Integer.parseInt(f[k++].trim());
            }
            for (int p = 0; p < PROFILES; p++) {
                for (int i = 0; i < POINTS; i++) {
                    c.duty[p][i] = Integer.parseInt(f[k++].trim());
                }
            }
            c.hysteresisC = Double.parseDouble(f[k++].trim());
            c.slewUpPerSec = Double.parseDouble(f[k++].trim());
            c.slewDownPerSec = Double.parseDouble(f[k++].trim());
            c.idleDuty = Integer.parseInt(f[k++].trim());
            c.minDuty = Integer.parseInt(f[k++].trim());
            c.maxDuty = Integer.parseInt(f[k++].trim());
            // The guard block is optional: a short line is a curve from before it existed, not a corrupt one.
            if (f.length >= k + GUARD_FIELDS) {
                c.socGuardEnabled = Integer.parseInt(f[k++].trim()) != 0;
                c.socGuardStartC = Integer.parseInt(f[k++].trim());
                c.socGuardGainPerC = Double.parseDouble(f[k++].trim());
                c.socGuardMaxDuty = Integer.parseInt(f[k++].trim());
                c.socGuardHystC = Double.parseDouble(f[k].trim());
            }
        } catch (RuntimeException e) {
            c.setDefaults();
        }
        c.sanitise();
        return c;
    }
}
