package com.sg.shougongji.ui;

import com.sg.shougongji.model.DayReview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EffortDuration {
    private static final String DECIMAL = "(?:[0-9]{1,4}(?:\\.[0-9]{1,6})?|\\.[0-9]{1,6})";
    private static final Pattern HOURS = Pattern.compile(DECIMAL);
    private static final Pattern UNITS = Pattern.compile(
            "(?:(" + DECIMAL + ")\\s*(?:小时|h)\\s*)?(?:(" + DECIMAL + ")\\s*(?:分钟|分|m))?");
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private EffortDuration() {}

    static BigDecimal parseHours(String value) {
        String input = value.strip().toLowerCase(Locale.ROOT);
        BigDecimal rounded;
        if (HOURS.matcher(input).matches()) {
            rounded = new BigDecimal(input).setScale(2, RoundingMode.HALF_UP);
        } else {
            Matcher units = UNITS.matcher(input);
            if (input.isBlank() || !units.matches()) {
                throw invalidDuration();
            }
            BigDecimal minutes = number(units.group(1)).multiply(MINUTES_PER_HOUR).add(number(units.group(2)));
            rounded = minutes.divide(MINUTES_PER_HOUR, 2, RoundingMode.HALF_UP);
        }
        if (rounded.signum() <= 0 || rounded.compareTo(BigDecimal.valueOf(10000)) >= 0) {
            throw invalidDuration();
        }
        return rounded;
    }

    static String display(BigDecimal hours) {
        BigDecimal minutes = hours.multiply(MINUTES_PER_HOUR).setScale(0, RoundingMode.HALF_UP);
        if (minutes.signum() == 0 && hours.signum() > 0) {
            return hours.stripTrailingZeros().toPlainString() + " 小时";
        }
        return DayReview.duration(minutes.longValueExact() * 60);
    }

    private static BigDecimal number(String value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static IllegalArgumentException invalidDuration() {
        return new IllegalArgumentException("请输入有效工时，例如 1.5、45m 或 1小时20分。");
    }
}
