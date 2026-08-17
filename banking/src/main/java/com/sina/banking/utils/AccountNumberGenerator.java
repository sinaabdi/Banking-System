package com.sina.banking.utils;

public class AccountNumberGenerator {
    public static Long withCheckDigit(Long seed) {
        String seedString = String.valueOf(seed);
        int sum = 0;

        for (int i = seedString.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(seedString.charAt(i));
            int distanceFromRight = seedString.length() - 1 - i;

            if (distanceFromRight % 2 == 0) {
                digit = doubleAndSumDigits(digit);
            }

            sum += digit;
        }

        int checkDigit = (10 - (sum % 10)) % 10;

        return seed * 10 + checkDigit;
    }

    private static int doubleAndSumDigits(int digit) {
        int ret = digit * 2;

        if (ret > 9) {
            ret -= 9;
        }

        return ret;
    }
}
