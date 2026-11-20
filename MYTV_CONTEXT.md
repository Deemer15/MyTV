# MyTV release context

Handoff for continuing work on Jeremy's private MyTV fork. Read `AGENTS.md`, `.github/agents/MyTV.agent.md`,
`HA-CONTEXT.md` (local-only) and the latest `README.md` before making changes.

## Current release

The Office TV has the current release. Living Room and Master Bedroom have earlier builds of the launcher and
should receive the final release only after the Office behavior is accepted.

This release replaces the old Home / Explore tab-driven media UI with:

- A full-screen Dashboard backdrop using crisp TMDB artwork.
- A Recently Added movie rail and a Shows rail.
- A 10-second movie showcase rotation that runs only while Movies is idle.
- A 10-second inactivity timeout that clears a focused movie and resumes showcase rotation.
- A Shows state that always focuses the first show and pauses movie rotation.
- A dedicated Apps catalogue using the original `AppAdapter` TV banners, ordering and focus animation.
- One side drawer with Dashboard, TV, Movies, Shows, Apps, Beast, Home Assist and Settings.
- Beast and Home Assist in the drawer instead of the old top pills. The Settings top pill is hidden too.
- A correct post-resume destination handoff: drawer actions from Apps are applied after Main is laid out.

## Focus model

- Physical Home always returns to Dashboard / Movies.
- Movies enters idle with no card focus. The next D-pad/OK action focuses the first movie.
- Any navigation while a movie is focused resets the 10-second idle timeout.
- Shows focuses the first card after its rail transition and stays focused.
- A first Left at the leftmost card is consumed; a second Left opens the drawer.
- The drawer opens highlighted on the active destination.
- Back/Apps-side drawer returns to the original Main drawer model, preselected on Apps.

If behavior regresses, inspect these first:

- `MediaRail.focusMovies()`, `focusShows()`, `focusFirst()`, `switchRail()` and `onCardKey()`.
- `MainActivity.onNewIntent()`, `onResume()`, `openSideNav()`, `activateSideNav()` and `activeNavIndex`.
- `AppsCatalogActivity.returnToMain()` and its drawer trigger.

## Relevant code

- `MediaRail.java`: dashboard artwork, rails, series handling, rotation and 10-second focus timeout.
- `MainActivity.java`: drawer, destination state, Home behavior and guarded WebView launches.
- `AppsCatalogActivity.java`: standalone Apps catalogue using `AppAdapter` in catalogue mode.
- `AppAdapter.java`: original TV banner rendering and focus animation, reused by the Apps page.
- `BeastClient.java`: library recency and episode file parsing.
- `TmdbClient.java`: metadata, backdrop, runtime, age rating and score.
- `RoundedBadgeSpan.java`: compact yellow `TMDb` and white age-rating badges.
- `BeastClientTest.java`: focused tests for series recency and spotlight episode labels.

## Build and validation

Run from `launcher/`:

```powershell
.\gradlew.bat --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

Release requirements:

- JDK 17 and Android platform/build-tools 34.
- Lint: zero errors.
- APK: below 786,432 B.
- Validate visual focus behavior on the Office TV first.
- Do not send injected navigation into the Master Bedroom TV.

Install a room with:

```powershell
pwsh mytv\build-and-install.ps1 -Room 'Office'
```

## Secrets and local-only state

Do not commit:

- `launcher/app/src/main/res/values/*_secrets.xml`
- `mytv/*/beast_key.txt`
- `mytv/*/backups/`
- `tools/adbkey/`
- `shots/`
- `HA-CONTEXT.md`

`shots/` are local evidence and may contain Wi-Fi/account information. Public screenshots belong under
`docs/screenshots/` only after redaction.

## Release notes / screenshots

The current code is text-documented in `README.md` and `mytv/README.md`. New screenshots are not required to build
or install the release. For a visual GitHub release, useful captures would be:

1. Dashboard with the side drawer closed.
2. Shows with the first show focused.
3. Apps catalogue with a dock app focused.
4. Left drawer with all eight destinations visible.

Crop/redact the Wi-Fi SSID, greeting name, addresses and any account data before publishing.
