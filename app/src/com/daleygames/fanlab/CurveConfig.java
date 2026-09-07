package com.daleygames.fanlab;

/**
 * The fan curve: a piecewise-linear map from LED temperature to duty, one duty column
 * per brightness tier, plus the hysteresis and slew-rate parameters that make the result
 * inaudible.
 *
 * <h3>Why these defaults</h3>
 * The stock controller is a bare if/else ladder with no hysteresis, sampled every 15 s.
 * Its tier-3 (Presentation) rungs are, using the true boundaries:
 * <pre>
 *   t &lt; 45.5  -&gt; high.speed.min      45.5..48.5 -&gt; 70
 *   48.5..50.5 -&gt; 75                  50.5..52.5 -&gt; 80      t &ge; 54.5 -&gt; 83
 * </pre>
 * The equilibrium parks at about 45.5 C, right on the first boundary, so the fan
 * alternates between the floor and 70 forever. With the floor at 69 the step is one
 * point and inaudible ("steady"); with the floor at 55 the step is fifteen points and
 * obvious ("cycling"). That is the entire bug: not the floor value, the missing
 * hysteresis at the boundary the machine happens to sit on.
 *
 * The shipped table is the one measured on hardware and written up in CURVE.md, and its
 * shape follows from that diagnosis:
 * <ul>
 *   <li><b>a floor at duty 30 up to 47 C</b>, which is where Normal, Eco and Super Eco
 *       all live -- they are inaudible and they never move. 47 rather than 46 is measured,
 *       not rounded: Normal's settled thermistor reading has a median of 46.5 C and a 95th
 *       percentile of 47.1 C, so a floor ending at 46 would lift Normal off duty 30 for
 *       96 % of its running time while 47 leaves it there for 98 %;</li>
 *   <li><b>a shelf from 51 to 55 C spanning two duty points, 38 to 40</b>, because the
 *       fix for a boundary the machine parks on is not a better boundary but no boundary.
 *       This is the operating point in Presentation and it covers the room this unit
 *       actually lives in, so the fan barely moves as the room drifts -- <b>0.5 duty points
 *       per degree, against 2.14 on the curve this replaced</b>. It is a shelf with a
 *       deliberate tilt rather than a flat one: the owner asked for slightly more fan when
 *       the light engine is hotter, capped at 40 because that is where he stops calling it
 *       silent. Two duty points across four degrees buys 0.8 C at the top of the band,
 *       which carries the 55 C ceiling out to a 28.0 C room instead of 26.8 and the 54 C
 *       line out to 26.7 instead of 25.8;</li>
 *   <li><b>a ramp above it</b>, 2 duty points per degree, so that when the machine does
 *       leave the shelf it leaves it continuously but with real authority. That slope is
 *       the owner's, and its purpose is transient rather than steady-state: it exists to
 *       arrest a runaway. In the blocked-vent fault run of 2026-09-07 it is worth +2 duty
 *       points at 58 C, +3 at 60 and +4 at 61 against the 1.4 duty/C it replaced, while
 *       changing <b>nothing at all below 55 C</b> and moving the settled point by only
 *       0.15 C at a 30 C room. Free where the machine actually lives, and useful where it
 *       does not;</li>
 *   <li><b>a backstop</b> reaching 83 by 70 C, at temperatures the plant cannot reach at
 *       any plausible ambient;</li>
 *   <li><b>identical columns for all three profiles</b>, so a brightness change is not a
 *       tier change in duty terms and FanCurve's immediate-jump exception never fires.</li>
 * </ul>
 *
 * <h3>The shelf moved, because the field log said it was in the wrong place</h3>
 * An earlier version of this table put the shelf at duty 30 below 48 C and this comment
 * claimed the operating point sat mid-shelf with the fan never moving. Thirty-six hours of
 * field log refuted it: the settled `degC` median was 51.85, three to five degrees up the
 * 48-55 C ramp, and <b>every one of the thirty settled duty changes in that log happened
 * on the ramp while the shelf produced none</b>. The shelf was real and the machine simply
 * was not on it. It is now placed where the machine actually sits, which is what the claim
 * had always assumed.
 * The response is monotone and continuous, so it is self-correcting: if the temperature
 * rises, duty rises with it immediately, instead of waiting for a whole-degree tick.
 *
 * Pure Java.
 */
public final class CurveConfig {

    /** rgblevel 1 (Eco) and 4 (Super Eco) share this column, exactly as the stock ladder does. */
    public static final int PROFILE_LOW = 0;
    /** rgblevel 2 (Normal). */
    public static final int PROFILE_NORMAL = 1;
    /** rgblevel 3 (Presentation). */
    public static final int PROFILE_HIGH = 2;
    public static final int PROFILES = 3;

    public static final String[] PROFILE_NAMES = {"Eco / Super Eco", "Normal", "Presentation"};

    /** The four curves on offer, quietest first. */
    public static final String[] PRESET_NAMES = {"Quiet", "Balanced", "Cool", "Cold"};

    /**
     * How much each preset adds to Quiet's knee duties <b>above the floor</b>.
     *
     * The floor -- knee 0, duty 30 below 47 C -- is the same in all four presets and is not
     * offset. That is the owner's instruction and the arithmetic backs it: below 47 C the
     * light engine is cool enough that extra fan buys almost nothing. Measured, Cold's +15
     * bought 3.4 C in Super Eco on a thermistor already sitting at 35 C, and 3.0 C in
     * Normal at 44 C -- full noise for no useful cooling, in the three brightness modes
     * that spend their whole lives on the floor. Above the floor the offset is worth
     * paying: in Presentation the same +15 buys 3.6 C on a thermistor at 52 C, where the
     * ceiling actually matters.
     *
     * Public because it <i>is</i> the design rather than an implementation detail, and the
     * host test asserts {@link #PRESETS} against it instead of against the resulting
     * numbers -- the transform is what carries the stability argument, so the transform is
     * what wants pinning.
     */
    public static final int[] PRESET_OFFSETS = {0, 5, 10, 15};

    /** {@link #presetOf} for a curve that is none of the four. Not a destination. */
    public static final int PRESET_CUSTOM = -1;

    /**
     * The presets, encoded. All four are the shipped curve with a constant added to every
     * knee duty above the floor, clipped at the 83 ceiling. Nothing else about them differs
     * -- same hysteresis, same slew, same guard, same knee temperatures -- with one
     * exception: Cold's floor edge is 43 C where the others' is 47, for the reason given
     * against its line below.
     *
     * <h3>Why an offset rather than four drawn curves</h3>
     * Adding a constant to the knees above the floor leaves the <i>differences</i> between
     * those knees untouched. The shelf and every segment above it therefore keep Quiet's
     * width and Quiet's duty/C slope, so their geometry and their stability margin are
     * unchanged. Monotonicity and identical columns across the three profiles carry across
     * by construction.
     *
     * <h3>The one segment the offsets do change, and what that cost</h3>
     * Pinning the floor means the rise from it to the shelf has to climb further in the
     * same 4 C: 2.0 duty/C on Quiet, then 3.25, 4.5 and <b>5.75 on Cold</b>. That is the
     * one place the "inherit Quiet's stability" argument does not apply, and it is exactly
     * the kind of steepening that was measured hunting elsewhere in this curve's history --
     * so it was not assumed safe. All four presets were driven through
     * {@code tools/CurveSim.java} against the two-pole plant across 15-35 C ambient at four
     * slow poles: <b>334 of 336 runs are steady at one duty point per tick</b>.
     *
     * <b>The one that is not, stated plainly rather than rounded away.</b> Quiet at 16 C
     * hunts by two duty points, which predates the pinned floor and predates the shelf --
     * it is a rounding knife-edge where the operating point lands almost exactly between
     * two integers on the rise, and 14, 15, 17 and 18 C are all steady. Two points at duty
     * 32 is inside what the owner calls inaudible, six degrees below the coldest room this
     * unit has seen, and moving a flat region off a measured operating point to remove it
     * would cost more than it saves. It is the accepted state, and the host test that
     * drives every preset against the plant is bounded at two for exactly that reason:
     * three is a regression.
     *
     * Cold at 17 C used to hunt by four, from the 5.75 duty/C rise the pinned floor forced
     * on it. That was fixed by moving Cold's floor edge to 43 C -- see its line -- and the
     * fix was chosen over leaving it because the host test could then be bounded at the
     * accepted two rather than carrying an exception for a known four.
     *
     * Away from those two the steep rise is harmless because nothing rests on it: each
     * preset's own operating point sits on its shelf or above, and the rise is only ever
     * traversed on the way past.
     *
     * The alternative -- offsetting the floor too, so every segment is congruent -- was
     * built first and rejected, because it made the three brightness modes that live on the
     * floor louder for no cooling worth having. See {@link #PRESET_OFFSETS}.
     *
     * <h3>Where the tail flattens, and why that is still safe</h3>
     * The offsets clip at 83, so the top of the curve is the one place the four shapes are
     * not congruent: Quiet, Balanced and Cool all reach full speed at the last knee, 70 C,
     * and only Cold gets there earlier, at 66 C. That still satisfies "reach the ceiling by
     * a temperature the plant cannot achieve" -- the hottest degC ever recorded on this
     * unit is 52.85 C, so every one of these curves reaches its ceiling more than thirteen
     * degrees above anything the hardware has produced, and rule 6 holds for all four.
     *
     * <h3>Where they settle</h3>
     * Solved against the measured plant by {@code tools/equilibria.py}, Presentation:
     * <pre>
     *              22 C ambient      24 C              26 C
     *   Quiet      37.2 % / 50.6     38.4 % / 51.9     39.2 % / 53.4
     *   Balanced   39.5 % / 49.2     41.6 % / 50.3     43.3 % / 51.6
     *   Cool       42.1 % / 48.1     44.3 % / 49.2     46.9 % / 50.5
     *   Cold       45.0 % / 46.9     47.6 % / 48.3     50.3 % / 49.6
     * </pre>
     * So at 24 C the cooling against Quiet is 1.6, 2.7 and 3.6 C. The offsets bite harder
     * here than they did on the previous curve, where the same +5 bought only 1.3 C. That
     * curve's operating point sat on a ramp, so the extra duty cooled the light engine, the
     * cooler thermistor read further down the ramp, and the loop gave most of the offset
     * back. Quiet's operating point is a flat shelf, so there is no ramp to walk down and
     * the offset is spent where it was asked for. It still does not transfer 1:1, because
     * the offset curves land back on the rise below the shelf, which is where their own
     * feedback reappears.
     *
     * <h3>Quiet is the default, not a copy of it</h3>
     * The first entry is byte-identical to what {@link #setDefaults()} encodes, so choosing
     * Quiet and resetting to defaults are the same act. A test asserts that, because
     * otherwise the two would drift apart silently the first time a default moved.
     */
    public static final String[] PRESETS = {
            // Quiet: floor 30 to 47 C, up to the shelf by 51, then 38 rising to 40 across
            // the 51-55 C band the machine occupies, and a backstop to 83 by 70 C.
            "v1,47,51,55,60,66,70,30,38,40,50,68,83,30,38,40,50,68,83,30,38,40,50,68,83,"
                    + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5",
            // Balanced: Quiet + 5 above the floor. The top knee's 88 clips to 83.
            "v1,47,51,55,60,66,70,30,43,45,55,73,83,30,43,45,55,73,83,30,43,45,55,73,83,"
                    + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5",
            // Cool: Quiet + 10 above the floor. The top knee's 93 clips to 83.
            "v1,47,51,55,60,66,70,30,48,50,60,78,83,30,48,50,60,78,83,30,48,50,60,78,83,"
                    + "0.8,0.25,0.12,10,30,83,1,70,2.0,62,1.5",
            // Cold: Quiet + 15 above the floor. Its fourth knee lands exactly on 83 and the
            // top one's 98 clips to it, so this is the one preset whose ceiling arrives
            // early, at 66 C.
            //
            // Its floor edge is 43 C, not the 47 C the other three share. With the floor
            // pinned at 30 and the shelf at 53, a rise over 47-51 is 5.75 duty/C, and at
            // 17 C ambient the controller hunted by four duty points there. Starting the
            // rise at 43 drops the slope to 2.87 duty/C and the hunt is gone at every
            // ambient tried.
            //
            // Note this is NOT a slope threshold, tempting as it looks: Cool's 47-51 rise is
            // 4.5 duty/C and is steady, while a 2.67 duty/C rise tried during the shelf work
            // hunted. Whether a curve hunts turns on where its equilibrium lands relative to
            // the integer duty boundaries, which no static rule has yet predicted -- see
            // testCurvePresetsDoNotHunt, which is why that test exists. The
            // cost is Normal-on-Cold: its settled reading of 46.5 C now sits on the rise,
            // so above about a 23 C room Normal runs 32-36 % on this preset instead of 30.
            // Eco and Super Eco are unaffected. Nobody choosing the coldest preset is
            // asking for the quietest fan, so the trade was taken.
            "v1,43,51,55,60,66,70,30,53,55,65,83,83,30,53,55,65,83,83,30,53,55,65,83,83,"
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

    /**
     * Deadband, degrees C. The curve input rises with the measured temperature
     * immediately, but only follows it down after it has fallen this far. Asymmetric on
     * purpose: rising is a safety event, falling is a comfort event.
     */
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

    /**
     * Is the SoC guard armed? On by default, because with the shipped numbers it cannot
     * fire in any condition yet measured -- it is a backstop, not a second curve.
     */
    public boolean socGuardEnabled;

    /**
     * SoC die temperature at which the guard starts adding fan, degrees C, read from
     * thermal_zone0.
     */
    public int socGuardStartC;

    /**
     * Extra duty points per degree above {@link #socGuardStartC}.
     *
     * The guard is <b>additive</b>, not an absolute floor, and that is the whole design.
     * An absolute floor has to climb from the minimum duty back up to whatever the curve
     * already wanted before it can achieve anything -- a third of its useful range spent
     * catching up -- and it arrives at the knee as a step, which is exactly the
     * discontinuity the stock controller parks on. Adding to the curve's own output
     * instead means the guard contributes nothing at the knee, rises continuously from
     * there, and spends every point it asks for.
     */
    public double socGuardGainPerC;

    /**
     * Ceiling on the guarded duty. Not the hardware maximum: from duty 40 the fan has
     * about 7.5 C of total authority over the die and 5.4 C of that is bought by duty 60,
     * so the last twenty-odd points buy 2 C. Past here it is paying a lot of noise for
     * very little heat, and the SoC's own throttling is the better tool.
     */
    public int socGuardMaxDuty;

    /**
     * Deadband for the guard's input, degrees C. Wider than {@link #hysteresisC} because
     * a die sensor is noisier and faster-moving than a thermistor bolted to the chassis;
     * the same asymmetry applies -- up at once, down only past the band.
     */
    public double socGuardHystC;

    public CurveConfig() {
        setDefaults();
    }

    /**
     * The measured curve, as shipped. A fresh install is correct without configuring
     * anything -- install, press the button, done.
     *
     * <h3>Why a preset is safe on a projector nobody has measured</h3>
     * This is a feedback controller closed on temperature, not a lookup table. It does not
     * need to know a unit's thermal plant: one that runs hotter simply gets more fan. The
     * measurements behind these numbers were needed to *predict* where a given unit lands,
     * not for the controller to work. A unit whose plant runs N degrees hotter behaves like
     * this one in a room N degrees warmer, so Presentation at 24 C ambient settles at:
     * <pre>
     *   unit +0 C -> duty 38, 51.9 C  (inside 55)   unit +6 C  -> duty 42, 56.1 C
     *   unit +2 C -> duty 39, 53.4 C  (inside 55)   unit +8 C  -> duty 44, 57.2 C
     *   unit +4 C -> duty 40, 54.9 C  (inside 55)   unit +15 C -> duty 54, 61.5 C
     * </pre>
     * The old curve absorbed about 6 C of unit-to-unit variation while holding the 55 C
     * ceiling, and this one absorbs 4 C. The shelf is what spent the difference -- an
     * operating point this flat is placed on <i>this</i> unit's measured plant, so a hotter
     * unit slides off the top of it sooner. Half of what a flat shelf would have cost is
     * bought back by the tilt, whose extra authority is available to a hotter unit as well
     * as to a hotter room. The failure mode is unchanged and is the safe one:
     * it gets louder and warmer rather than running away, and even the +15 C case sits
     * nearly 13 C below the 75 C shutdown. On a unit nobody has measured, Balanced is the
     * conservative starting preset.
     *
     * <h3>Why one column for all three profiles</h3>
     * The optics care about temperature, not about which brightness mode produced it, and
     * the loop already finds whatever duty holds a given temperature. Identical columns
     * also mean a brightness change is not a tier change in duty terms, so
     * {@link FanCurve}'s deliberate "jump immediately on an upward tier change" never
     * fires: the largest single-tick change on an Eco -> Presentation switch drops from
     * 10 duty points to 1.
     *
     * <h3>The numbers</h3>
     * Floor at 30 (inaudible even up close) to 47 C; 2 duty points per C up to the shelf
     * by 51 C; <b>38 rising to 40 from 51 to 55 C</b> -- half a duty point per degree,
     * which reads as 38 % at 51, 39 % from 52 and 40 % from 54; 2 duty points per C from
     * there to 60 C; then a backstop reaching 83 by 70 C.
     *
     * <h3>Why the shelf is where it is, and why it is flat</h3>
     * The shelf is the whole design, and it is placed on measurement rather than taste.
     * <ul>
     *   <li><b>Duty 38</b> is the quietest flat duty that keeps the light engine under
     *       55 C in the warmest room this machine has been measured in. Duty 37 would
     *       breach it at 26.3 C ambient and 36 at 25.7; the room has been 26.2.</li>
     *   <li><b>51 to 55 C</b> is the band duty 38-39 produces across most of that room.
     *       The rise above ambient at duty 38 is 28.1 C, so the shelf holds the duty inside
     *       a single point for every ambient from 22.9 to 27.9 C, against a measured room
     *       of 21.9 to 26.2 C. Over the field log it gives <b>0.22 duty changes an hour
     *       against the old curve's 1.00</b>, with the duty confined to 37-39 rather than
     *       wandering 36-40, and every change is one point.</li>
     *   <li><b>Why the shelf tilts instead of being flat, and why by two points.</b> A
     *       flat 38 was built first and is quieter -- 0.14 duty changes an hour against
     *       0.79, and only two duty values in play. It was changed on the owner's
     *       instruction and the instruction was a good one: he does not want the light
     *       engine above 54-55 C for lamp life, and a flat shelf serves its quietest duty
     *       at the <i>top</i> of the band as well as the bottom, which is exactly where
     *       that matters. He set the ceiling on the tilt himself -- "happy with the fan up
     *       until 40" -- so the shelf spans 38 to 40 and no further.
     *
     *       <p>The cost is real and measured: over the field log the duty changes 0.79
     *       times an hour rather than 0.14, still one point at a time and still inside the
     *       old curve's 1.00. What it buys is 0.8 C at the top of the band, the 54 C line
     *       moving from a 25.8 C room to 26.7, and -- as a side effect worth having -- the
     *       unit-to-unit tolerance doubling from +2 C to +4 C, because the extra authority
     *       is available to a hotter unit as well as to a hotter room.</p>
     *
     *       <p>The tilt is 0.5 duty/C, a quarter of the slope of the ramp this curve was
     *       built to get off, and a twentieth of the stock ladder's step.</p></li>
     *   <li><b>Why 92 % and not 100 %</b>, since a shelf starting at 50 C would have
     *       covered all of it: the three constraints do not quite fit, and it is worth
     *       recording the arithmetic rather than rediscovering it. Normal's settled reading
     *       reaches 47.1 C, so the floor cannot end below 47. Presentation's settled reading
     *       starts at 50.8 C, so a shelf covering all of it cannot start above 50.8. That
     *       leaves 3.7 C for the rise between them, and <b>a rising segment needs 4 C</b> --
     *       at 3 C the slope is 2.7 duty/C and it hunts. That was not reasoned but
     *       measured: the 3 C version was built, and {@code tools/CurveSim.java} found it
     *       hunting by 2 points at 18 and 21 C ambient where the 4 C version is steady at
     *       every pole. (Do not read 4 C as a threshold -- Cool's rise is steeper and
     *       steady. Nothing static predicts this; the dynamic test is the check.) So the
     *       4 C rise
     *       is kept and the shelf starts at 51, which spends the coldest 1 C of the room's
     *       range to buy stability everywhere. The alternative -- floor at 46, shelf at
     *       50 -- pins 100 % of Presentation but lifts Normal off duty 30 for 96 % of its
     *       running time, and Normal is inaudible today.</li>
     *   <li><b>Flat</b> because the previous curve's operating point sat three to five
     *       degrees up a 2.14 duty/C ramp, and all thirty of its settled duty changes in
     *       thirty-six hours of field log happened on that ramp. The shelf below it
     *       produced none. Replaying the same room closed-loop, this curve holds duty 38
     *       for 100 % of that time with zero changes.</li>
     *   <li><b>The floor stops at 47 C</b>, and that edge is measured on the machine
     *       rather than taken from the plant table. The table predicts Normal at 44.1 C,
     *       but 289 settled Normal rows in the field log read a median of <b>46.5 C</b>
     *       with a 95th percentile of 47.1 C -- the Normal column of the plant table
     *       under-states the rise by about 2.5 C, which is exactly the kind of error the
     *       provenance notes in {@code tools/solve_curve.py} warn about for that column.
     *       Placed on the table's figure the floor would have ended at 46 and lifted Normal
     *       off duty 30 for 96 % of its running time. Placed on the measurement it ends at
     *       47 and leaves it there for 98 %, which is what V1 achieved and worth keeping.
     *       Eco (38.5 C) and Super Eco (34.4 C) are far below either edge.</li>
     * </ul>
     * The cost is stated in {@link #PRESETS} and above: a room below 22 C gets a duty a
     * point or two above what the old ramp would have asked for, and a room above 27 C
     * runs the light engine warmer in exchange for staying quiet.
     */
    public void setDefaults() {
        int[] t = {47, 51, 55, 60, 66, 70};
        System.arraycopy(t, 0, tempC, 0, POINTS);

        // 47   51   55   60   66   70      <- degrees C
        //       |____|     the shelf: two duty points across the whole band the machine
        //                  occupies, so it barely moves but still cools when it is hotter
        int[] all = {30, 38, 40, 50, 68, 83};
        System.arraycopy(all, 0, duty[PROFILE_LOW], 0, POINTS);
        System.arraycopy(all, 0, duty[PROFILE_NORMAL], 0, POINTS);
        System.arraycopy(all, 0, duty[PROFILE_HIGH], 0, POINTS);

        // 0.8. Measured, not guessed: at 0.5 the duty dithered by a point at 24 C
        // ambient; at 0.8 the six-minute acceptance test on hardware saw the LED range
        // 51.42..51.91 C (+/-0.25 C) with ZERO duty changes.
        //
        // Briefly raised to 1.2 on the strength of a failing test, and put back. That
        // test starts the controller at duty 62 and counts transitions while it slews to
        // the curve's target; under the old curve 45.5 C WAS duty 62 so nothing moved,
        // and under this one it ramps 62 -> 30 and logs ~32 changes. It was measuring
        // settling, not dither sensitivity. Widening the band would not have helped, and
        // because the band is asymmetric it would have cost about a duty point of upward
        // bias on every machine, forever, to fix nothing.
        hysteresisC = 0.8;
        // Slow on purpose. One point every 4 s rising, every 8 s falling, is inaudible as
        // a change; the stock controller's 10-15 point step is not.
        slewUpPerSec = 0.25;
        slewDownPerSec = 0.12;
        idleDuty = 10;
        // 30 is "inaudible even up close" and is proven: held 12 minutes under the hottest
        // load the machine can produce, with no fan-stall shutdown.
        minDuty = 30;
        maxDuty = 83;

        // --- the SoC guard ---
        //
        // The fan is driven by the LED thermistor and cannot see the SoC at all. Switching
        // UHD processing on moves the SoC die about 11 C while moving the LED thermistor
        // half a degree, so a load that heats the video pipeline and not the light engine
        // is invisible to the curve. thermal_zone0 (pll) is the only zone with cooling
        // devices bound to it, at 75 C; that is where CPU and GPU frequency get throttled.
        //
        // Where these numbers come from, measured rather than argued. A 48-minute sweep
        // with UHD on -- six holds, duty 40 held first and last so load drift would show
        // as a failure to close, and it closed to within 0.5 C -- gives the die's plant
        // directly:
        //
        //     duty   30     40     50     62     83
        //     pll    69.0   64.2   61.6   57.8   54.4   C at 24 C ambient
        //
        // So from duty 40 the fan can take 6.4 C off the die by duty 62 and 9.8 C by full
        // speed. The last 21 points buy 3.4 C of that, which is why the ceiling is 62 and
        // not 83: 62 is already "way too loud" and the authority has largely run out.
        //
        // 2.0 duty points per degree. Against the measured authority of 0.26-0.32 C per
        // duty point through this range that is a loop gain of 0.52-0.64 -- under one, and
        // confirmed steady in simulation against the measured plant at 24 C and 30 C
        // ambient across every SoC load, inert to saturated, with zero duty changes.
        //
        // 70 C as the knee. The die sits at 64.2 C at the operating point, 10.8 C under
        // the 75 C trip, and does not reach 70 until roughly a 30-33 C room -- where it
        // then adds about one duty point. It must stay a backstop; a guard that fires in
        // ordinary use is just a second curve, and a noisy one.
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
                // Unknown or unreadable brightness tier. Use the hottest profile: fail safe
                // is high, and tier 3 is the one whose duties are highest at every knee.
                return PROFILE_HIGH;
        }
    }

    /** The curve for a preset. An index from nowhere falls back to the quietest. */
    public static CurveConfig preset(int i) {
        return decode(PRESETS[(i < 0 || i >= PRESETS.length) ? 0 : i]);
    }

    /**
     * Which preset a stored curve is, decided by matching the encoded line. There is
     * deliberately no preference of its own: with nothing else stored, the label and the
     * curve cannot disagree, and the screen has no way to claim Balanced while the loop
     * runs something else.
     *
     * The accepted cost is that an edit which really does change the curve reads as
     * {@link #PRESET_CUSTOM}. Tuning the guard with {@code --ef socgain} re-encodes the
     * whole line, so the label flips to Custom -- correctly, because what is on the unit
     * is then no longer one of the four.
     */
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

    /**
     * Repair anything a user or a corrupt preferences file could have put in here.
     * Called on every load and after every edit, so the control loop can assume sanity.
     */
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

    /**
     * Repair the SoC guard. Held to the same rule as the curve -- ascending knees, legal
     * duties -- plus one the curve does not need: the guard's duties must be
     * non-decreasing. A floor that sagged as the die got hotter would be worse than no
     * floor at all, because it would look like protection while removing it.
     */
    private void sanitiseGuard() {
        if (socGuardStartC < 0) {
            socGuardStartC = 0;
        }
        if (socGuardStartC > 120) {
            socGuardStartC = 120;
        }
        // A negative or absent gain is not "off" -- socGuardEnabled is off. Treat it as a
        // corrupt value and restore the default rather than silently disarming.
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

    /**
     * Extra duty points the guard asks for at a given SoC die temperature, on top of
     * whatever the curve already wants. Zero below the knee, rising linearly above it.
     * The caller applies {@link #socGuardMaxDuty}, because the cap is on the resulting
     * duty and not on the contribution.
     *
     * An unreadable sensor returns 0, not a high value. The guard is advisory: it exists
     * to shave a peak the LED thermistor cannot see, and the machine is no worse off
     * without it than it was before it existed. Failing a fan to 62 % because a monitoring
     * zone stopped responding would spend a lot of silence on nothing. The LED-driven
     * curve, which is the actual safety case, is unaffected either way.
     */
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

    /**
     * The curve itself: piecewise-linear interpolation, flat outside the end knees.
     * Pure function of the config; no state, no I/O.
     */
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
        // Appended after the v1 fields on purpose: decode() accepts a line that is longer
        // than it needs, so a curve saved before the guard existed still loads, and picks
        // up the default guard rather than none.
        sb.append(',').append(socGuardEnabled ? 1 : 0);
        sb.append(',').append(socGuardStartC);
        sb.append(',').append(socGuardGainPerC);
        sb.append(',').append(socGuardMaxDuty);
        sb.append(',').append(socGuardHystC);
        return sb.toString();
    }

    /**
     * Parse a line produced by {@link #encode()}. Anything unparseable falls back to the
     * defaults; a missing or broken config must never leave the fan uncontrolled.
     */
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
            // The guard block is optional. A short line is a curve from before it existed,
            // not a corrupt one, and it keeps the defaults set in the constructor.
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
