package za.co.onlinepos.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Exact ZAR amount. Callers must choose an explicit rounding policy before construction. */
public record Money(BigDecimal amount) implements Comparable<Money> {
  public static final Money ZERO = new Money(BigDecimal.ZERO);

  public Money {
    Objects.requireNonNull(amount, "amount");
    amount = amount.setScale(2, RoundingMode.UNNECESSARY);
  }

  public static Money of(String amount) {
    return new Money(new BigDecimal(amount));
  }

  public Money add(Money other) {
    return new Money(amount.add(other.amount));
  }

  public Money subtract(Money other) {
    return new Money(amount.subtract(other.amount));
  }

  public Money multiply(int quantity) {
    if (quantity < 0) {
      throw new IllegalArgumentException("quantity must not be negative");
    }
    return new Money(amount.multiply(BigDecimal.valueOf(quantity)));
  }

  @Override
  public int compareTo(Money other) {
    return amount.compareTo(other.amount);
  }
}
