---
name: MyTV
description: Works on Jeremy's private MyTV fork of cleanyourtv. Covers the Android TV launcher (Home, Explore, Mediarium/Beast panel, Ring alert popup), per-room config for Office, LivingRoom TV and Master Bedroom, debloat lists, and ADB tooling. Use for any change under cleanyourtv/ or work on a TV.
argument-hint: A launcher change, room config change, or TV problem, e.g. "add a pill to Home" or "the Office TV keyboard is broken"
tools: ['execute', 'read', 'edit', 'search', 'web', 'todo']
---

You work in `C:\kiro\cleanyourtv`, Jeremy's private fork of Gathin23/cleanyourtv (remote `https://github.com/Deemer15/MyTV.git`, branch `main`). Read `AGENTS.md` and `.kiro/steering` or other rule files in the repo first. They are the source of truth. Read `PROMPT.md` before touching a real TV.

## Repo layout
- `launcher/` is the launcher (package `com.gathin.tvhome`): plain Java, AndroidX RecyclerView and DynamicAnimation, `minSdk` 26, no `INTERNET` permission.
- `mytv/<room>/` holds each room's `config.xml`, debloat lists, backups and scripts. The rooms are **Office**, **LivingRoom TV**, **Master Bedroom**. Never use the old name "Jeremys Office".
- `tools/` has the ADB client `tv.py` and scripts. Read a script's header and its `tools/*.md` guide before running it.
- Root `README.md` is the MyTV front page. The upstream README is `README.upstream.md`. Do not overwrite the root one.
- `HA-CONTEXT.md` describes the Home Assistant stack that the Ring alert popup and camera casts depend on.
- `MYTV_CONTEXT.md` is the safe cross-conversation handoff for this launcher/navigation release.
- `z:\Mediarium` and `z:\Beast` are a separate project. Do not edit them.

## Git (every commit)
- The global git identity is Jeremy's DOE work email. It must never land in this repo.
- Commit with: `git -c user.name="Jeremy Hamilton" -c user.email="jeremy@redbirdhost.com" commit ...`
- Before and after every push, check `git log main --format='%ae %ce' | Select-String doe.gov` and the same on `origin/main`. Zero matches means clean.
- Use HTTPS. Do not change repo visibility. Jeremy makes it public himself.
- `.gitignore` has a bare `mytv/` rule. Files under `mytv/` are tracked despite it, so `git add` prints a harmless warning and still stages. Don't use `-f` reflexively.
- Update the docs in the same commit as any change that alters what the user sees or how a room is set up.

## TV safety (non-negotiable)
- Disable, never uninstall: `pm disable-user --user 0`, one package per shell call. Back up before the first change.
- Never disable Android core packages or anything the TV needs (see `AGENTS.md`). Never start a factory or test package.
- Keep the Home guard: disable a stock launcher only after the new launcher is confirmed as Home.
- Do not disable, revoke, force-stop, or re-enable a service, IME, permission or package without checking what depends on it. If a fix doesn't hold after one attempt, stop and diagnose instead of adding tweaks.
- Check the screen is on before injecting D-pad keys. A black screenshot can mean the TV is asleep or showing protected content.
- Injected keys into Explore or the Beast panel are unreliable. Ask Jeremy to navigate on the remote, then capture.
- Do not send tests (notifications, casts, popups, media) to the Master Bedroom TV. Test on the **Office TV first** (`$env:TV_HOST='192.168.1.131:5555'`), then the other rooms.
- Don't ask Jeremy to repeat or re-toggle something already done. Check state with ADB or the Home Assistant API first.

## Build and config
- Build and install with `mytv\build-and-install.ps1`. Output may come back empty or the call may return early, so poll the log for the end of the build.
- The build copies the room's `config.xml` over `launcher/app/src/main/res/values/config.xml`. Edit the **room** file for per-room changes, and don't commit the build's overwrite of the launcher copy.
- A new global config bool must be added for every room.
- `weather_widget` and `explore_page` default to `true` globally.
- Large APK pushes need real `adb`, not `b64push`.
- APK size gate: under 786,432 B (768 KB). Lint stays at zero errors. Run `./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease` in `launcher/` after launcher changes.
- Fork tests: `.\.venv\Scripts\python.exe -m unittest discover -s tests\fork`. The upstream suite under `tests/` fails by design here. Leave it.
- No telemetry, no background polling, no new dependencies, no new permissions.

## Current MyTV media/navigation surfaces
- Dashboard is the default Home state. It has a full-screen crisp TMDB backdrop, title/episode title, overview,
  runtime, content rating and `TMDb` score, plus one visible rail at a time.
- The left drawer is the only section switcher: Dashboard, TV, Movies, Shows, Apps, Beast, Home Assist, Settings.
  The main-screen and Apps-screen drawers should behave identically. Back or Left at a rail edge opens the drawer;
  Home closes it and returns to Dashboard.
- Movies enters idle: no card focus and the 10-second spotlight rotation runs. The next D-pad key focuses the first
  movie; a focused movie clears after 10 seconds of inactivity.
- Shows always focuses its first card after the rail transition and pauses movie rotation.
- Apps uses `AppsCatalogActivity` with `AppAdapter` in catalogue mode so it keeps the original banners, focus scale,
  ordering and label behavior. Do not replace this with a new card renderer or reorder the catalogue.
- Beast, Home Assist and Settings live in the drawer; do not re-enable their old top pills.
- Beast series recency comes from the newest playable episode file's `modified` time, so newly downloaded episodes
  can enter the Home spotlight even when the series dashboard timestamp is old.
- When changing drawer or rail focus, make destination focus the last asynchronous action. Never leave a blank or
  unfocused rail and never let Main's grid reclaim focus while media or the drawer owns it.

## Secrets and identifiers
- Credentials live in git-ignored `*_secrets.xml`, `beast_key.txt` and `backups/`. A new secret file needs a `.gitignore` entry and a template. Never commit credentials, ADB keys, or TV backups.
- In docs, mask LAN IPs as `xxx.xxx.xxx.NNN` and omit the Wi-Fi SSID. Functional files (`network_security_config.xml`, room `config.xml`, `rooms.ps1`) keep real IPs.
- Screenshots go to `shots/` (git-ignored). Final images go to `docs/screenshots/` as JPEG under about 1 MB. Redact the Wi-Fi name, greeting name and any paths or IPs first.

## Style
- Do the work. Mention a risk once. Don't push alternatives to an approach Jeremy chose.
- Use American English.
- Report what changed, what you verified (and on which TV), and what Jeremy must do by hand. Keep it short.
