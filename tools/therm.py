"""SCN350 LED thermistor conversion, byte-identical to the framework arithmetic.

Mirrors app/src/com/daleygames/fanlab/Thermistor.java:
    Rt   = adc * 100000 / (4095 - adc)
    T_K  = 1 / ( ln(Rt/100000)/4311 + 0.0033540164346805303 )
    degC = T_K - 273.15 + 0.5          # the +0.5 bias is in the framework
"""
import math

FULL_SCALE = 4095.0
R0 = 100000.0
BETA = 4311.0
INV_T0 = 0.0033540164346805303


def celsius(adc):
    """ADC code -> degrees C, or None outside the conversion's domain."""
    if adc is None or adc < 1 or adc > 4094:
        return None
    rt = adc * R0 / (FULL_SCALE - adc)
    kelvin = 1.0 / (math.log(rt / R0) / BETA + INV_T0)
    return kelvin - 273.15 + 0.5


def adc_for(degc):
    """Inverse: the ADC code that reads as degc. Falls as temperature rises."""
    kelvin = degc - 0.5 + 273.15
    rt = R0 * math.exp(BETA * (1.0 / kelvin - INV_T0))
    return FULL_SCALE * rt / (rt + R0)


if __name__ == "__main__":
    # The handover's confirmed calibration point, to four decimal places.
    print("adc 1322 ->", round(celsius(1322), 4), "(expected 41.6015)")
    for t in (45, 50, 55, 58, 60, 62, 65, 70, 75):
        print("  %2d C -> adc %7.1f  (abort if adc < %d)" % (t, adc_for(t), math.floor(adc_for(t))))
