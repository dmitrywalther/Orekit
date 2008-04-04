package fr.cs.orekit.time;

import java.io.Serializable;
import java.text.DecimalFormat;

import fr.cs.orekit.errors.OrekitException;

/** Class representing a date as year, month and day chunks.
 * <p>This class uses the astronomical convention for calendars,
 * which is also the convention used by <code>java.util.Date</code>:
 * a year zero is present between years -1 and +1, and 10 days are
 * missing in 1582. The calendar used around these special dates are:</p>
 * <ul>
 *   <li>up to 0000-12-31 : proleptic julian calendar</li>
 *   <li>from 0001-01-01 to 1582-10-04: julian calendar</li>
 *   <li>from 1582-10-15: gregorian calendar</li>
 * </ul>
 * <p>Instances of this class are guaranteed to be immutable.</p>
 * @author Luc Maisonobe
 */
public class ChunkedDate implements Serializable, Comparable {

    /** Serializable UID. */
    private static final long serialVersionUID = 7127660151923617766L;

    /** Factory for proleptic julian calendar (up to 0000-12-31). */
    private static final YearFactory prolepticJulianFactory = new ProlepticJulianFactory();

    /** Factory for julian calendar (from 0001-01-01 to 1582-10-04). */
    private static final YearFactory julianFactory          = new JulianFactory();

    /** Factory for gregorian calendar (from 1582-10-15). */
    private static final YearFactory gregorianFactory       = new GregorianFactory();

    /** Factory for leap years. */
    private static final MonthDayFactory leapYearFactory    = new LeapYearFactory();

    /** Factory for non-leap years. */
    private static final MonthDayFactory commonYearFactory  = new CommonYearFactory();

    /** Format for years. */
    private static DecimalFormat fourDigits = new DecimalFormat("0000");

    /** Format for months and days. */
    private static DecimalFormat twoDigits  = new DecimalFormat("00");

    /** Year number. */
    public final int year;

    /** Month number. */
    public final int month;

    /** Day number. */
    public final int day;

    /** Build a date from its calendar elements.
     * @param year year number (may be 0 or negative for BC years)
     * @param month month number from 1 to 12
     * @param day day number from 1 to 31
     * @exception IllegalArgumentException if inconsistent arguments
     * are given (parameters out of range, february 29 for non-leap years,
     * dates during the gregorian leap in 1582 ...)
     */
    public ChunkedDate(int year, int month, int day) throws IllegalArgumentException {

        // very rough range check
        // (just to avoid ArrayOutOfboundException in MonthDayFactory later)
        if ((month < 1) || (month > 12)) {
            OrekitException.throwIllegalArgumentException("non-existent month {0}",
                                                          new Object[] {
                                                              new Integer(month)
                                                          });
        }

        // start by trusting the parameters
        this.year  = year;
        this.month = month;
        this.day   = day;

        // build a check date from the J2000 day
        final ChunkedDate check = new ChunkedDate(getJ2000Day());

        // check the parameters for mismatch
        if ((year != check.year) || (month != check.month) || (day != check.day)) {
            OrekitException.throwIllegalArgumentException("non-existent date {0}-{1}-{2}",
                                                          new Object[] {
                                                              new Integer(year),
                                                              new Integer(month),
                                                              new Integer(day)
                                                          });
        }

    }

    /** Build a date from a year and day number.
     * @param year year number (may be 0 or negative for BC years)
     * @param dayNumber day number in the year from 1 to 366
     * @exception IllegalArgumentException if dayNumber is out of range
     * with respect to year
     */
    public ChunkedDate(int year, int dayNumber) throws IllegalArgumentException {
        this(new ChunkedDate(year - 1, 12, 31).getJ2000Day() + dayNumber);
        if (dayNumber != getDayOfYear()) {
            OrekitException.throwIllegalArgumentException("no day number {0} in year {1}",
                                                          new Object[] {
                                                            new Integer(dayNumber),
                                                            new Integer(year)
                                                          });
        }
    }

    /** Build a date from its day number with respect to J2000 epoch.
     * @param j2000Day day number with respect to J2000 epoch
     */
    public ChunkedDate(int j2000Day) {

        // we follow the astronomical convention for calendars:
        // we consider a year zero and 10 days are missing in 1582
        // from 1582-10-15: gregorian calendar
        // from 0001-01-01 to 1582-10-04: julian calendar
        // up to 0000-12-31 : proleptic julian calendar
        YearFactory yFactory = gregorianFactory;
        if (j2000Day < -152384) {
            if (j2000Day > -730122) {
                yFactory = julianFactory;
            } else {
                yFactory = prolepticJulianFactory;
            }
        }
        year = yFactory.getYear(j2000Day);
        final int dayInYear = j2000Day - yFactory.getLastJ2000DayOfYear(year - 1);

        // handle month/day according to the year being a common or leap year
        final MonthDayFactory mdFactory =
            yFactory.isLeap(year) ? leapYearFactory : commonYearFactory;
        month = mdFactory.getMonth(dayInYear);
        day   = mdFactory.getDay(dayInYear, month);

    }

    /** Get the day number with respect to J2000 epoch.
     * @return day number with respect to J2000 epoch
     */
    public int getJ2000Day() {
        YearFactory yFactory = gregorianFactory;
        if (year < 1583) {
            if (year < 1) {
                yFactory = prolepticJulianFactory;
            } else if ((year < 1582) || (month < 10) || ((month < 11) && (day < 5))) {
                yFactory = julianFactory;
            }
        }
        final MonthDayFactory mdFactory =
            yFactory.isLeap(year) ? leapYearFactory : commonYearFactory;
        return yFactory.getLastJ2000DayOfYear(year - 1) +
               mdFactory.getDayInYear(month, day);
    }

    /** Get the day of week.
     * <p>Day of week is a number between 1 (Monday) and 7 (Sunday).</p>
     * @return day of week
     */
    public int getDayOfWeek() {
        final int dow = (getJ2000Day() + 6) % 7; // result is between -6 and +6
        return (dow < 1) ? (dow + 7) : dow;
    }

    /** Get the day number in year.
     * <p>Day number in year is between 1 (January 1st) and either 365 or
     * 366 inclusive depending on year.</p>
     * @return day number in year
     */
    public int getDayOfYear() {
        return getJ2000Day() - new ChunkedDate(year - 1, 12, 31).getJ2000Day();
    }

    /** Get a string representation (ISO-8601) of the date.
     * @return string representation of the date.
     */
    public String toString() {
        return new StringBuffer().
               append(fourDigits.format(year)).append('-').
               append(twoDigits.format(month)).append('-').
               append(twoDigits.format(day)).
               toString();
    }
    
    /** {@inheritDoc} */
    public int compareTo(Object other) {
        int j2000Day = getJ2000Day();
        int otherJ2000Day = ((ChunkedDate) other).getJ2000Day();
        if (j2000Day < otherJ2000Day) {
            return -1;
        } else if (j2000Day > otherJ2000Day) {
            return 1;
        }
        return 0;
    }

    /** {@inheritDoc} */
    public boolean equals(Object other) {
        try {
            ChunkedDate otherDate = (ChunkedDate) other;
            return (otherDate != null) && (year == otherDate.year) &&
                   (month == otherDate.month) && (day == otherDate.day);
        } catch (ClassCastException cce) {
            return false;
        }
    }

    /** {@inheritDoc} */
    public int hashCode() {
        return (year << 16) | (month << 8) | day;
    }

    /** Interface for dealing with years sequences according to some calendar. */
    private static interface YearFactory {

        /** Get the year number for a given day number with respect to J2000 epoch.
         * @param j2000Day day number with respect to J2000 epoch
         * @return year number
         */
        public int getYear(int j2000Day);

        /** Get the day number with respect to J2000 epoch for new year's Eve.
         * @param year year number
         * @return day number with respect to J2000 epoch for new year's Eve
         */
        public int getLastJ2000DayOfYear(int year);

        /** Check if a year is a leap or common year.
         * @param year year number
         * @return true if year is a leap year
         */
        public boolean isLeap(int year);

    }

    /** Class providing a years sequence compliant with the proleptic julian calendar. */
    private static class ProlepticJulianFactory implements YearFactory {

        /** {@inheritDoc} */
        public int getYear(int j2000Day) {
            return  -((-4 * j2000Day - 2920488) / 1461);
        }

        /** {@inheritDoc} */
        public int getLastJ2000DayOfYear(int year) {
            return (1461 * year + 1) / 4 - 730123;
        }

        /** {@inheritDoc} */
        public boolean isLeap(int year) {
            return (year % 4) == 0;
        }

    }

    /** Class providing a years sequence compliant with the julian calendar. */
    private static class JulianFactory implements YearFactory {

        /** {@inheritDoc} */
        public int getYear(int j2000Day) {
            return  (4 * j2000Day + 2921948) / 1461;
        }

        /** {@inheritDoc} */
        public int getLastJ2000DayOfYear(int year) {
            return (1461 * year) / 4 - 730122;
        }

        /** {@inheritDoc} */
        public boolean isLeap(int year) {
            return (year % 4) == 0;
        }

    }

    /** Class providing a years sequence compliant with the gregorian calendar. */
    private static class GregorianFactory implements YearFactory {

        /** {@inheritDoc} */
        public int getYear(int j2000Day) {

            // year estimate
            int year = (400 * j2000Day + 292194288) / 146097;

            // the previous estimate is one unit too high in some rare cases
            // (240 days in the 400 years gregorian cycle, about 0.16%)
            if (j2000Day <= getLastJ2000DayOfYear(year - 1)) {
                --year;
            }

            // exact year
            return year;

        }

        /** {@inheritDoc} */
        public int getLastJ2000DayOfYear(int year) {
            return (1461 * year) / 4 - year / 100 + year / 400 - 730120;
        }

        /** {@inheritDoc} */
        public boolean isLeap(int year) {
            return ((year % 4) == 0) && (((year % 400) == 0) || ((year % 100) != 0));
        }

    }

    /** Interface for dealing with months sequences according to leap/common years. */
    private static interface MonthDayFactory {

        /** Get the month number for a given day number within year.
         * @param dayInYear day number within year
         * @return month number
         */
        public int getMonth(int dayInYear);

        /** Get the day number for given month and day number within year.
         * @param dayInYear day number within year
         * @param month month number
         * @return day number
         */
        public int getDay(int dayInYear, int month);

        /** Get the day number within year for given month and day numbers.
         * @param month month number
         * @param day day number
         * @return day number within year
         */
        public int getDayInYear(int month, int day);

    }

    /** Class providing the months sequence for leap years. */
    private static class LeapYearFactory implements MonthDayFactory {

        /** Months succession definition. */
        private static final int[] previousMonthEndDay = {
            0, 0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335
        };

        /** {@inheritDoc} */
        public int getMonth(int dayInYear) {
            return (dayInYear < 32) ? 1 : (10 * dayInYear + 313) / 306;
        }

        /** {@inheritDoc} */
        public int getDay(int dayInYear, int month) {
            return dayInYear - previousMonthEndDay[month];
        }

        /** {@inheritDoc} */
        public int getDayInYear(int month, int day) {
            return day + previousMonthEndDay[month];
        }

    }

    /** Class providing the months sequence for common years. */
    private static class CommonYearFactory implements MonthDayFactory {

        /** Months succession definition. */
        private static final int[] previousMonthEndDay = {
            0, 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334
        };

        /** {@inheritDoc} */
        public int getMonth(int dayInYear) {
            return (dayInYear < 32) ? 1 : (10 * dayInYear + 323) / 306;
        }

        /** {@inheritDoc} */
        public int getDay(int dayInYear, int month) {
            return dayInYear - previousMonthEndDay[month];
        }

        /** {@inheritDoc} */
        public int getDayInYear(int month, int day) {
            return day + previousMonthEndDay[month];
        }

    }

}
