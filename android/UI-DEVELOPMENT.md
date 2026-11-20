# Android UI development, design system and visual QA

This is the canonical engineering guide for Android user-interface work. It covers
where styling lives, how to inspect the rendered app, how the automated visual
regression suite works, and the self-review every UI change must pass before delivery.

Design authority is unchanged: the approved
[Android V4.6 reference](https://github.com/suraj-singh12/gnss-android-tracking/blob/8d95eafe6fdcd1423027194ea128ae7a8b91236d/docs/design/approved/android-v4.6-interactive.html),
the Android sections and the 15 operator rules in [design-system.md](../docs/design-system.md),
the [preservation matrix](../docs/ui-preservation-matrix.md) and the decisions and
evidence in [ui-acceptance.md](../docs/ui-acceptance.md). This guide says how to
implement and verify that design. It does not authorize a redesign; new or changed
visual behaviour still needs operator approval (AGENTS.md §6–7).

## 1. UI architecture

The app has one Activity, `MainActivity`, built from native framework Views in Kotlin
(no XML layouts, no Compose, no Material Components library). Its presentation is
layered for historical reasons:

1. `onCreate` builds the original operational controls and binds them to their owners
   (Room, `Sender`, `SosHoldGesture`, `CommandControl`, preflight and so on).
2. `installReferenceScreens` and then `installV46Screens` re-parent those same controls
   into the V4.6 destinations. **The V4.6 layer is what users see.** It never creates a
   second state owner; it moves and restyles existing views.

When you change how something looks, change it in the V4.6 layer or in the shared
components below. Do not restyle a control in `onCreate` and expect the result to
survive: later layers move and restyle it. Behaviour (listeners, IDs, validation,
transactions, SOS hold) stays with the original owner.

Window insets (system bars, display cutout, IME) are applied once at the shared root
(`root.setOnApplyWindowInsetsListener`). The weighted main `ScrollView`, the fixed SOS
dock and the navigation bar are siblings in that root. Do not add per-screen footer
padding.

## 2. Design system: where styling lives

| Layer | File | Owns |
|---|---|---|
| Colour roles | `app/src/main/res/values/colors.xml`, `values-night/colors.xml` | Semantic Day/Night palette: `background`, `surface`, `ink`, `muted`, `line`, `control_line`, `accent`, `action`, `secondary`, `secondary_surface`, `positive_surface`, `warning`/`warning_surface`, `danger`, `emergency_action`/`emergency_surface`, `disabled_ink`, `disabled_surface`, `disabled_line`, approved `stop_surface`/`stop_line`/`stop_ink` (identical in both themes), `ripple`. |
| Dimension tokens | `app/src/main/res/values/dimens.xml` | Spacing steps, page/card/list insets, section rhythm, radii, stroke, control heights, button/input/pill padding, icon sizes and the type scale (sp). |
| Typography roles | `NativeUi.kt` → `TextRole` | Each role = one size token + weight + ink: `Display`, `DialogTitle`, `AppBarTitle`, `AppBarSubtitle`, `Hero`, `Heading`, `Body`, `Button`, `RowTitle`, `Value`, `Label`, `Caption`, `FieldLabel`, `Meta`, `Pill`, `Section`, `Micro`, `Metric`, `Mono`. |
| Components | `NativeUi.kt` → `NativeUi` | Shared View vocabulary (table below). |
| Theme | `res/values/styles.xml` | Framework `Theme.Material` Day/Night, system bar colours, sans font family. |

To restyle the whole app, change a token or a `NativeUi` component. Examples:

- Card corner radius: `radius_card` in `dimens.xml`.
- Secondary-text size everywhere: `text_caption` / `text_meta`.
- All button heights: `control_height`; button label type: `TextRole.Button`.
- Night surface colour: `surface` in `values-night/colors.xml`.

### Shared components (`NativeUi`)

| Component | Use for | Notes |
|---|---|---|
| `label(text, TextRole, tone?)`, `text(view, role)` | Every TextView | `label(text, size: Float, …)` is a legacy overload that snaps to the nearest role; new code must pass a `TextRole`. |
| `shape(tone, radiusToken, stroke?)` | Any rounded surface | `stroke = null` for borderless. `surface(tone, radiusDp)` remains for legacy call sites. |
| `card(parent, gap, tone)` | Grouped content | 16dp radius, 1dp `line` border, 14dp padding. |
| `section(parent, title)` | Uppercase group heading | 18dp above, 8dp below; accessibility heading. |
| `menu(parent, title, subtitle, action)` | Navigation rows | Title + muted subtitle + right chevron; 60dp minimum. Grouped rows share one bordered list with dividers only between peers. |
| `fact(parent, label, value)` | Read-only label/value | Selectable value, end-aligned. |
| `pill(view, positive)` | Status | Borderless full pill; teal positive, amber degraded/unknown. |
| `style(button, primary, danger)` | Actions | Secondary (wash + border), primary (filled `action`), danger (caution wash). Disabled uses `disabled_*` with readable `disabled_ink`. |
| `stopStyle(button)` | Stop Tracking (hero and its confirmation) | Approved pale-red treatment in both themes. Do not replace. |
| `quiet(button)` | Cancel/Close/inline links | Text action with ripple. |
| `iconButton(button, icon, description)` | App bar actions | Borderless 48dp target, centred 24dp vector. |
| `equalHeights(row)` | Peer actions in one row | Keeps row actions the same height when large text wraps a label. |
| `input(editText)` | Text fields | Outlined 10dp, 48dp, Body type, 2dp accent focus stroke, disabled state, sans even for password types. |
| `selector(spinner)` + `selectorAdapter(items)` / `selectorItem(view)` | Exclusive choices | Outlined field, single down-chevron at a fixed 12dp end inset, Body type in field and popup. |
| `switchRow(switch)` | Binary settings | Label start, Switch end, 48dp. Use a Switch, never a CheckBox, for a binary setting. |
| `metric(parent, value, caption)` | Large counts | 26sp autosizing value with caption. |
| `feedback(view)`, `collapsingLabel(role)` | Status/error text | A collapsing label measures 0 height while empty, so an idle slot leaves no gap. |
| `dialog(alertDialog)`, `dialogContent`, `dialogListAdapter` | App-owned dialogs | 18dp radius, 18sp title, 14sp body, quiet negative, primary or caution positive (`CAUTION_ACTIONS`), 12dp bottom inset, 64dp minimum action width. |
| `evidence(source)` | Structured read-only evidence | Presentation adapter over an existing bound TextView. |
| `icon(resource, size, tint)` | Vector artwork | Tinted with a semantic colour. Navigation chevrons point right; selectors use the down chevron. |

The 24dp half-shaded appearance vector, the 1.2-second SOS hold dock and Stop Tracking
are protected approved treatments. Do not reintroduce sun/moon artwork or text glyphs.

### Rules for new UI code

- Use a `TextRole`, a dimension token and a semantic colour. A literal size, radius or
  hex colour in a screen is a review defect unless it is recorded as an exception.
- Reuse a `NativeUi` component before creating a new pattern. If a pattern is missing,
  add it to `NativeUi`, document it in the table above and use it everywhere it applies.
- Controls match intent: Switch for binary, selector for exclusive choice, button for
  action, chevron row for navigation.
- Touch targets are at least 48dp (`control_height`). Text is scalable sp.
- Separators go only between peer rows.

## 3. Live UI inspection

Robolectric renders (section 4) run anywhere. Live inspection needs a real Android
runtime: an emulator with hardware acceleration (KVM on Linux, Hypervisor on macOS,
WHPX on Windows) or a USB device with developer options.

### Android Studio (interactive)

1. Open `android/` in Android Studio. Create a Pixel-class emulator, API 34 or 35.
2. Run the `app` configuration (debug build). The debug APK is debuggable.
3. **Layout Inspector** (`View → Tool Windows → Layout Inspector`) shows the live View
   tree with each view's bounds, padding, margins, text size and drawable. Enable
   *Show layout bounds* to see padding/margins over the running app.
4. Developer options on the emulator/device: *Show layout bounds*, *Font size* and
   *Display size*, *Dark theme* (system; the app also has its own control).

### Command line (`android/tools/ui-inspect.sh`)

Requires `adb` on `PATH` and `python3`. Output goes to `android/build/ui-inspect/`
(override with `OUT=`); use `SERIAL=` to choose a device.

```sh
cd android
./gradlew assembleDebug --console=plain
tools/ui-inspect.sh install           # adb install -r -g the debug APK
tools/ui-inspect.sh launch
tools/ui-inspect.sh tap Settings      # tap by visible text or content description
tools/ui-inspect.sh tap "Party & Command"
tools/ui-inspect.sh shot party        # party.png + party.xml (UI Automator hierarchy)
tools/ui-inspect.sh bounds party      # print on-screen bounds of every labelled node
tools/ui-inspect.sh font 1.5          # system font scale
tools/ui-inspect.sh size 720x1280 320 # smaller display; `size reset` restores
tools/ui-inspect.sh night             # the app's own Day/Night toggle
tools/ui-inspect.sh tour day-1.0-     # capture every destination and nested Settings/Diagnostics page
```

`*.xml` files are UI Automator dumps with the real on-screen bounds of every node; use
them to check alignment, sizes and overlap on the running app.

### CI emulator capture

The `android-emulator-ui` job in `.github/workflows/issue-6-matched-assets.yml` boots an
API 34 x86_64 emulator, installs the exact-SHA debug APK and runs
`tools/ci-emulator-tour.sh` (Day/Night × font 1.0/1.5). The artifact
`Android-emulator-ui-<sha>` contains the PNG/XML captures, `device.txt` and logcat.
It is emulator evidence, not physical-device acceptance.

## 4. Automated visual regression

`app/src/test/java/org/gnss/tracking/VisualRegressionTest.kt` renders the real
`MainActivity` with Robolectric native graphics (Android View/Skia), deterministic Room
fixtures at a fixed instant (`FIXTURE_TIME`, UTC) and xhdpi density, for six profiles:

| Profile | Purpose |
|---|---|
| `day-360x800-1.0` | Reference phone, dialogs, keyboard |
| `night-360x800-1.0` | Night palette, dialogs |
| `day-360x800-1.5` | Large text, dialogs, keyboard |
| `night-360x640-1.3` | Short screen, increased text, keyboard |
| `day-480x960-1.0` | Large phone |
| `night-800x360-1.0` | Landscape |

Each profile visits four Tracking states, SOS pending, an urgent alert (top and
scrolled), every Settings and Diagnostics destination (top and scrolled end),
instruction details, the physical-button listening state, the keyboard state (300dp IME
inset) and, where marked, ten app-owned dialogs. Synthetic 24dp status and navigation
bar insets are applied throughout.

For every capture it does three things:

1. **Layout audit** (`VisualQa.kt`, `VisualAudit`). Rules: `text-clipped`,
   `text-ellipsized` (unless the view is explicitly a bounded single line),
   `offscreen`, `escapes-parent`, `overlap` (visible leaves, clipped to scroll
   viewports), `touch-target` (< 48dp), `type-token` (text size not in the type scale),
   `radius-token` (corner radius not a shape token), `empty-gap` (empty text taking
   space), `duplicate-header` (in-page "‹" back link or title repeating the app bar),
   `row-mismatch` (peer actions of different heights) and `insets` (SOS outside the
   safe area). `VisualAuditSelfTest` proves each rule fires on a deliberately broken
   layout.
2. **Geometry comparison** against the approved baseline in
   `app/src/test/resources/visual-baseline/<profile>.json`: the dp bounds of every
   visible button, input, selector, switch, icon and rounded surface per screen.
   Movement or resizing beyond 2dp fails the test with a per-component diff.
3. **Capture output**: `app/build/visual-qa/<profile>/*.png`, `index.html` (contact
   sheet with findings) and `report.json`. With `GNSS_SCREENSHOT_DIR` set they go to
   `$GNSS_SCREENSHOT_DIR/visual-qa/` (CI uploads them in the acceptance evidence).

```sh
cd android
./gradlew testDebugUnitTest --tests '*VisualRegressionTest' --tests '*VisualAuditSelfTest' --console=plain
# open app/build/visual-qa/<profile>/index.html
```

### Changing the approved baseline

A geometry failure means the layout moved. Do **not** re-record just to make it pass.

1. Open the failing profile's `index.html` and compare against the V4.6 reference.
2. If the movement is a defect, fix the code.
3. If it is an intended, approved change, re-record and justify it:
   `GNSS_VISUAL_BASELINE=record ./gradlew testDebugUnitTest --tests '*VisualRegressionTest'`.
   Commit the JSON diff with the reason (issue, approval, screens affected) and add a
   row to the findings ledger in `docs/ui-acceptance.md`.

To accept a layout-audit finding as intentional, add a `(Regex, reason)` entry to
`allowed` in `VisualRegressionTest`. The list is empty today; every entry needs a reason.

## 5. Mandatory self-review before delivering UI changes

Compilation and green functional tests are not evidence of good UI. Automated checks
and visual inspection complement each other; neither replaces the other.

1. Build the debug APK and run the visual suite (section 4).
2. Open the contact sheets for at least `day-360x800-1.0`, `night-360x800-1.0`,
   `day-360x800-1.5` and `night-800x360-1.0`. Inspect every changed screen and its
   neighbours yourself.
3. For each changed screen check: alignment to the 17dp page margin and 14dp card
   inset; consistent type roles; spacing rhythm; borders only where the pattern has
   them; corner radii; icon size/weight/alignment; chevron placement; action hierarchy
   (one primary per group, caution for destructive); disabled controls look disabled;
   no clipping, truncation, overlap or blank gaps; SOS dock and navigation intact.
4. Compare the same component across screens (rows, pills, inputs, buttons, dialogs).
5. Where an emulator is available, run `tools/ui-inspect.sh tour` or the CI emulator
   job and inspect the real rendering and UI Automator bounds.
6. Fix what you find and re-run the suite. Record new findings and fixes in the ledger.
7. Run the full gate: `./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain`.
   Lint must not gain warnings from your change.

### Release criteria for UI work

- Visual suite and self-test pass; baseline changes are justified in the ledger.
- Full Android suite, lint and debug APK pass at the exact delivered SHA.
- Rendered self-review completed and recorded, including Night, large text, short and
  landscape layouts and dialogs.
- Physical-device items in [DEVICE-ACCEPTANCE.md](DEVICE-ACCEPTANCE.md) listed as open
  until the operator verifies them.

## 6. Coverage matrix

`●` covered by audit + geometry + capture in that profile. `K` keyboard state. `D` dialogs.

| Surface | day 360 1.0 | night 360 1.0 | day 360 1.5 | night 360×640 1.3 | day 480 | night landscape |
|---|---|---|---|---|---|---|
| Tracking: stopped / active / offline / error | ● | ● | ● | ● | ● | ● |
| Tracking: SOS pending, urgent alert (+ scrolled) | ● | ● | ● | ● | ● | ● |
| Settings and its 8 nested pages | ● | ● | ● | ● | ● | ● |
| Diagnostics and its 13 nested pages, instruction details | ● | ● | ● | ● | ● | ● |
| Physical button listening | ● | ● | ● | ● | ● | ● |
| Party & Command with keyboard | K | | K | K | | |
| Dialogs: stop tracking, enrollment steps, disable, re-enroll, field readiness, instruction filter, retry, phone alert, invalid interval, unsaved edits | D | D | D | | | |
| Notifications (OS-owned content) | `NotificationVisualTest` | | | | | |

Not covered by automation: open Spinner popups, real IME windows, OS permission and
document-picker screens, toasts, TalkBack/Switch Access, OEM rendering.

## 7. Known limitations

- Robolectric native rendering uses real framework Views and Skia but not a real
  device window manager, IME, GPU or OEM fonts. Emulator capture (CI) and physical
  devices are separate evidence levels.
- The local agent sandbox has no KVM, so emulator inspection runs in CI, not locally.
- Landscape on short phones: the SOS dock and navigation keep a fixed footer, so the
  scrolling content area is small (about 100dp at 800×360 with 24dp system bars).
  A side navigation rail or hiding navigation in landscape would need operator design
  approval.
- With the keyboard open, the SOS dock and navigation stay above it by design, which
  leaves a small form viewport on short screens. Real-device IME behaviour must be
  checked.
- App-bar titles and the SOS dock caption are bounded single lines; at large text they
  ellipsize. The full text stays available (screen content, content descriptions,
  SOS history).
