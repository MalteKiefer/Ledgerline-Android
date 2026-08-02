package de.ledgerline.app.data

/** How the contact list is ordered. */
enum class ContactSort { FIRST, LAST, DISPLAY }

/** How a contact's name is rendered: "Last, First" or "First Last". */
enum class ContactNameOrder { LAST_FIRST, FIRST_LAST }

/** How dates (contact birthdays/anniversaries, etc.) are rendered. */
enum class DateFormatPref { SYSTEM, DMY, YMD, MDY }

/** App theme selection: follow the device, or force light/dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }
