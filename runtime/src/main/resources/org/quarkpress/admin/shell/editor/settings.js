// SPDX-License-Identifier: GPL-2.0-or-later
// What WordPress prints into its editor page for the date and locale packages, built in the browser from the editor
// route's date settings (the site's formats, start of the week and time zone, for every user who may edit the post),
// and the preset variables the canvas needs, generated from the editor settings' theme features.

import { COMBINED_FORMATS, DATE_L10N, MOMENT_LOCALE } from "../l10n/en-us.js";

/** An offset in hours as WordPress writes it for wp.date: `0`, `5:30`, `-3:30`, `5:45`; no sign added. */
export function offsetFormatted(offset) {
  return String(offset).replace(".25", ":15").replace(".75", ":45").replace(".5", ":30");
}

/**
 * Sets the text direction, moment's English locale and wp.date's settings from the editor route's `dateSettings`
 * (`{formats: {date, time}, startOfWeek, timezone: {offset, string, abbr}}`).
 */
export function applyLocale(dateSettings) {
  const wp = window.wp;
  const startOfWeek = dateSettings.startOfWeek;
  wp.i18n.setLocaleData({ "text direction\u0004ltr": ["ltr"] }, "default");
  window.moment.updateLocale("en_US", {
    ...MOMENT_LOCALE,
    months: [...MOMENT_LOCALE.months],
    monthsShort: [...MOMENT_LOCALE.monthsShort],
    weekdays: [...MOMENT_LOCALE.weekdays],
    weekdaysShort: [...MOMENT_LOCALE.weekdaysShort],
    week: { ...MOMENT_LOCALE.week, dow: startOfWeek },
    // the site's time and date formats, as WordPress sets them; the combined one stays the English constant
    longDateFormat: {
      ...MOMENT_LOCALE.longDateFormat,
      LT: dateSettings.formats.time,
      LL: dateSettings.formats.date,
    },
  });
  wp.date.setSettings({
    l10n: {
      ...DATE_L10N,
      months: [...DATE_L10N.months],
      monthsShort: [...DATE_L10N.monthsShort],
      weekdays: [...DATE_L10N.weekdays],
      weekdaysShort: [...DATE_L10N.weekdaysShort],
      meridiem: { ...DATE_L10N.meridiem },
      relative: { ...DATE_L10N.relative },
      startOfWeek,
    },
    formats: {
      time: dateSettings.formats.time,
      date: dateSettings.formats.date,
      datetime: COMBINED_FORMATS.datetime,
      datetimeAbbreviated: COMBINED_FORMATS.datetimeAbbreviated,
    },
    timezone: {
      offset: dateSettings.timezone.offset,
      offsetFormatted: offsetFormatted(dateSettings.timezone.offset),
      string: dateSettings.timezone.string,
      abbr: dateSettings.timezone.abbr,
    },
  });
}

/** Each preset kind: where its list is in the theme features, its variable's infix, and the field holding its value. */
const PRESETS = [
  ["color", "palette", "color", "color"],
  ["color", "gradients", "gradient", "gradient"],
  ["typography", "fontSizes", "font-size", "size"],
  ["spacing", "spacingSizes", "spacing", "size"],
  ["shadow", "presets", "shadow", "shadow"],
  ["dimensions", "aspectRatios", "aspect-ratio", "ratio"],
];

/**
 * The preset variables as one `:root` rule, from `__experimentalFeatures`: the default colours, gradients, font sizes,
 * spacing sizes, shadows and aspect ratios, in that order, each list in its own order, slugs as given.
 */
export function presetVariables(features) {
  const declarations = [];
  for (const [section, list, infix, field] of PRESETS) {
    const presets = features && features[section] && features[section][list] && features[section][list].default;
    if (!Array.isArray(presets)) {
      continue;
    }
    for (const preset of presets) {
      if (preset && preset.slug !== undefined && preset[field] !== undefined) {
        declarations.push(`--wp--preset--${infix}--${preset.slug}:${preset[field]};`);
      }
    }
  }
  // printed inside a style element: no "<" may end it
  return `:root{${declarations.join("")}}`.replaceAll("<", "\\3c ");
}
