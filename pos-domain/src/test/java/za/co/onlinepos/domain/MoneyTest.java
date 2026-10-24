package za.co.onlinepos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {
  @Test
  void decimalAdditionDoesNotIntroduceBinaryFloatingPointError() {
    assertEquals(Money.of("0.30"), Money.of("0.10").add(Money.of("0.20")));
    assertEquals(Money.of("285.00"), Money.of("95.00").multiply(3));
  }

  @Test
  void extraFractionalCentsMustNotBeSilentlyRounded() {
    assertThrows(ArithmeticException.class, () -> Money.of("1.001"));
    assertThrows(NumberFormatException.class, () -> Money.of("NaN"));
    assertThrows(NumberFormatException.class, () -> Money.of("Infinity"));
  }

  @Test
  void canonicalScaleMakesEquivalentAmountsEqual() {
    assertEquals(Money.of("12"), Money.of("12.00"));
    assertEquals(new BigDecimal("0.00"), Money.ZERO.amount());
    assertEquals(Money.of("12").hashCode(), Money.of("12.00").hashCode());
  }

  @Test
  void subtractionSupportsAccountingDifferencesWithoutDoubleRounding() {
    assertEquals(Money.of("-0.01"), Money.of("1.99").subtract(Money.of("2.00")));
    assertThrows(IllegalArgumentException.class, () -> Money.of("1").multiply(-1));
  }
}
