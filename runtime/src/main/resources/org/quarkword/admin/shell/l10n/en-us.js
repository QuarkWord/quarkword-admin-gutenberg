// SPDX-License-Identifier: GPL-2.0-or-later
// The English (United States) date texts WordPress prints into its editor page for wp.date and moment, as a browser
// receives them: data only. The site's own formats, start of the week and time zone come with the editor's settings.

/** The `l10n` part of `wp.date.setSettings` (its `startOfWeek` is the site's, set when applied). */
export const DATE_L10N = Object.freeze({
  locale: "en_US",
  months: [
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
  ],
  monthsShort: ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"],
  weekdays: ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"],
  weekdaysShort: ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"],
  meridiem: { am: "am", pm: "pm", AM: "AM", PM: "PM" },
  relative: {
    future: "%s from now",
    past: "%s ago",
    s: "a second",
    ss: "%d seconds",
    m: "a minute",
    mm: "%d minutes",
    h: "an hour",
    hh: "%d hours",
    d: "a day",
    dd: "%d days",
    M: "a month",
    MM: "%d months",
    y: "a year",
    yy: "%d years",
  },
  startOfWeek: 1,
});

/** The combined formats WordPress translates for English, whatever the site's own formats. */
export const COMBINED_FORMATS = Object.freeze({
  datetime: "F j, Y g:i a",
  datetimeAbbreviated: "M j, Y g:i a",
});

/**
 * The object WordPress passes to `moment.updateLocale("en_US", …)` on a site with the default settings (its
 * `week.dow`, `LT` and `LL` are the site's start of the week, time format and date format).
 */
export const MOMENT_LOCALE = Object.freeze({
  months: DATE_L10N.months,
  monthsShort: DATE_L10N.monthsShort,
  weekdays: DATE_L10N.weekdays,
  weekdaysShort: DATE_L10N.weekdaysShort,
  week: { dow: 1 },
  longDateFormat: {
    LT: "g:i a",
    LTS: null,
    L: null,
    LL: "F j, Y",
    LLL: "F j, Y g:i a",
    LLLL: null,
  },
});
