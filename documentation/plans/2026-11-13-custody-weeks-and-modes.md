# Custody weeks, and what Perso and Off show

**Status:** built in 12.16.0 (2026-11-13), from the owner's feedback (not a roadmap item) and an
interview the same day. The seven decisions and the as-built notes are recorded at the end.

The owner shares custody of a child, every other week from Friday to Friday. This repository is
public: the child's name is typed in Modes settings and kept in the vault, never here. Below,
`<name>` stands for it.

## Today

- **Habits:** the "2 wk" schedule shows two rows, "Week 1" and "Week 2", counted every other week
  from 2024-01-01 (`HabitScheduler.EPOCH_MONDAY`), with no sign of which week is the current one.
  The owner's one 2-week habit happens to match the custody weeks. Holidays can't be expressed.
- **Modes:** Work during work hours, and one fixed mode outside them (the owner's: Perso). A leave
  is Off all day. **Perso and Off behave the same:** both only hide the work folders
  (`Visibility.workContextHidden`). Nothing else in the code tells them apart.
- **Projects** show the same in Perso and Off: every project outside the work folders.

## The custody calendar (Modes settings, a "Shared custody" card after Schedule)

- **One child, one name** typed in the card. Labels read "With `<name>`" and "Without `<name>`".
  It lives in the vault's `10_Journal/system/context_modes.json`, like the rest of Modes.
- **Rhythm:** every other week, strictly. A 53-week year doesn't break it (decision 1): 2026 has a
  week 53, so from January 2027 the weeks with the child on weekdays fall on odd week numbers. This
  matches how the 2-week habit grid already counts.
- **Phase:** "This week, `<name>` is with you: Mon–Fri / the weekend", stored as a date.
- **Handover:** Friday by default (the day is editable), during the workday (decision 2). The
  evening after work follows the new week.
- **A day with the child** is a day the child wakes up at the owner's: the departure day counts,
  the arrival day doesn't. With a Friday handover that means Mon–Fri of the weekday week and
  Sat–Sun of the weekend week, the same days as the owner's 2-week habit.
- **Exceptions** (decision 3), for school holidays and swapped weekends: date ranges (both ends
  included) marked "with" or "without", with an optional label, added like a leave. A day inside
  one follows it, for habits and modes alike. If two overlap, the one that starts later wins.
- **Read-only device:** reads the calendar and edits nothing, like the rest of Modes.

## Modes

- **During work hours on work days:** Work, with or without the child.
- **Outside work hours:** Off with the child, Perso without. When the calendar is on, two rows
  replace the "Outside work hours" choice: "With `<name>`" (default Off) and "Without `<name>`"
  (stored as the existing `outsideHoursMode`, so a 12.15 device keeps reading it).
- **Leave** (decision 4) only removes Work: Off with the child, Perso without. With the calendar
  off, a leave is still Off all day, as today.
- **On a handover day** the mode changes when the workday ends. That moment is a schedule change,
  so a manual choice ends there too, even on a day off.
- **The mode sheet:** Perso reads "Work hidden; only your Perso projects", Off reads "No work, no
  projects". The line under the title adds "with `<name>`" or "without `<name>`".
- **Shutdown:** Close the day offers the evening's real mode: Off on an evening with the child.

## What Perso and Off show (decisions 6, 7)

- **Modes settings → "Perso projects"** (a card after Work folders): a checklist of the personal
  projects, meaning every project root outside the work folders, sub-projects included. With
  nothing ticked, Perso shows every personal project, as today. The list is stored in
  `context_modes.json`.
- **Work:** unchanged, every project.
- **Perso:** only the ticked projects, on the project screens.
- **Off:** no project, on the project screens.
- **The project screens:**
  - The Projects tab keeps its place in the bar. In Off it shows one line, "No projects in Off".
    In Perso it shows the ticked projects, with pins and hides as today.
  - Daily's Projects block takes no room in Off. In Perso it picks among the ticked projects.
  - The Projects widget shows the same line in Off. It reads the mode from disk, like the workers:
    today it reads the in-memory one, which a cold widget process can't trust.
  - Due today drops the `@moi` actions of a project that isn't shown. A path belongs to the
    deepest project root that holds it (`ProjectFormat.projectFor`).
- **Unchanged:** notes stay in the file list, search and AI answers. The toolkit's tools keep
  today's rule: hand-off composer, evidence report, objectives, explain an error, meeting prep.

## Habits (decision 5)

- **Each habit gets "Days":** Any / With `<name>` / Without `<name>`, applied on top of its schedule
  (Weekly, 2 wk or Interval), exceptions included. The row shows only when the calendar is on.
  With the calendar off the choice is ignored, and the habit follows its schedule.
- **Stored** as `custody` in the vault's habit config (`10_Journal/data/habits/config.json`):
  absent means any day, otherwise `WITH` or `WITHOUT`. A 12.15 device ignores it.
- **`HabitScheduler.isScheduledOn` takes the calendar,** so every surface follows it: the Habits
  tab, Daily, stats and streaks, the widgets, reminders, the health automations and the weekly
  review. Workers and widgets read the calendar from disk (settled decision 8).
- **The 2-week grid stays,** and marks the current week ("this week").
- **The owner's 2-week habit** can move to "every day + With `<name>`" in the editor after the
  update. The app never rewrites a habit by itself.
- **Room:** `habit_configs` gains a column, so the database goes to v32. It goes through a
  migration that adds the column, not the destructive fallback, because the fallback would drop
  two things:
  - the chat history, the Room-only content the conventions name as the exception;
  - every vector, which means a full re-index on the phone.

  Schemas aren't exported, so the migration is checked by installing over 12.15 on the tablet or
  the emulator.

## Tests first

- **The calendar** (pure): the owner's fortnight (W45–W46 2026); New Year 2026/27, where W53 and W1
  alternate; the arrival and departure days; exceptions winning over the rhythm, and over each
  other; the moment of change on a handover day (before and after the workday ends).
- **`ModeResolver`:** evenings and weekends with and without the child; a leave with and without
  the child; a leave with the calendar off (Off); a manual choice ending at the handover.
- **`HabitScheduler`:** the condition on Weekly, 2 wk and Interval, with exceptions; the calendar
  off means the condition is ignored.
- **Projects:** Off shows none, Perso shows the ticked ones (or all), Work shows all; Due today's
  filter by project root, with sub-projects.
- **Round trips:** a habit with `custody`; a `context_modes.json` written before this change (no
  calendar, no Perso list) still loads.
- **On a device:** the migration (chat history and vectors kept), the mode sheet, the Projects tab
  in Off, and the habit editor.

## Decisions (owner, 2026-11-13, interview)

1. **Rhythm:** every other week, whatever the week number. A 53-week year doesn't break it.
2. **Handover:** during the workday, so the evening follows the new week. Nothing to set.
3. **Holidays and swaps:** exceptions in Modes settings, as date ranges marked with or without the
   child. Habits and modes both follow them.
4. **Leave:** only removes Work. The calendar decides Off or Perso.
5. **Habits:** a "Days" choice on each habit (Any / With / Without). The 2-week grid stays.
6. **Perso projects:** a checklist in Modes settings, beside Work folders. Nothing ticked means all
   of them.
7. **Off:** no project on the project screens (the tab's one line, Daily's block, the widget, Due
   today). Notes stay in the file list and search.

## Defaults taken without asking

- Work mode and work hours are the same every week.
- The modes for "with" and "without" are editable; the defaults are Off and Perso.
- The handover day is editable; the default is Friday.
- With the calendar off, nothing changes: the habit condition is ignored and a leave is Off.
- The Room column goes through a migration, not the destructive fallback (see Habits).

## As built (12.16.0)

Where the build differs from the text above, or settles what it left open:

- **Setting the rhythm:** "This week, `<name>` arrives on Friday / leaves on Friday" stores the
  arrival date. The handover-day chips move it within the same weeks. Until one of the two is
  chosen the calendar changes nothing.
- **An exception** is added like a leave: a date range, then "Who is home those days?" with an
  optional label and two buttons, With and Without.
- **The mode sheet** ends its line with "· with `<name>`" or "· without `<name>`". Perso reads "Work
  hidden; your personal projects (pick them in Modes settings)", Off reads "Work and projects
  hidden".
- **The Perso card** takes the mode's own name ("Perso projects"). It lists every project outside
  the work folders, sub-projects included. A ticked root that later moves into a work folder stays
  in the file but no longer shows.
- **`HabitScheduler.isScheduledOn` takes the calendar as a required parameter,** so no caller can
  forget it. The habit card reads, for example, "SAT, SUN · without `<name>`".
- **Room:** `MIGRATION_31_32` adds the column in place (`ALTER TABLE … DEFAULT ''`, with
  `@ColumnInfo(defaultValue = "")` so Room's schema check matches). It is registered before the
  destructive fallback.
- **Checked on the tablet** (read-only, the real vault, 2026-11-13):
  - Installed over 12.15.0, the database went to v32. A marker message planted in the chat table
    was still there (removed afterwards); a rebuild would have dropped it.
  - Modes settings shows the two new cards, locked.
  - Off showed "No projects in Off." and Daily without its Projects block. Perso showed the
    personal projects only.
  - The tablet went back to its schedule afterwards.

  The calendar's effect on modes and habits can't be shown there: the tablet is read-only, and the
  vault's Modes have no calendar yet. The unit tests cover it, then the owner checks on the phone.

## Not in this batch

- Off hiding anything beyond projects: the Tech watch, the toolkit chip, focus sessions.
- Notifications that differ between Perso and Off.
- A handover at a set time, other rhythms (2-2-5-5, every other weekend), more than one child.
