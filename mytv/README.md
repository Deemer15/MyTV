# My TVs

Three TVs, each set up from this PC with the Clean Your TV launcher (`com.gathin.tvhome`, built from `launcher\`).
Each room has its own folder with its launcher settings, debloat lists and backups.

| Room | Device | Address | Folder |
|------|--------|---------|--------|
| Office | Hisense SmartTV 4K (lushan), Google TV 12 | `xxx.xxx.xxx.131` | [`Office\`](Office/) |
| LivingRoom TV | onn. Streaming Device 4K pro (SNA), Google TV 14 | `xxx.xxx.xxx.233` | [`LivingRoom TV\`](LivingRoom%20TV/) |
| Master Bedroom | onn 4K Pro Streaming Device (jarvis2), Google TV 14 | `xxx.xxx.xxx.214` | [`Master Bedroom\`](Master%20Bedroom/) |

Addresses are shown with the first three octets masked. The room names and real addresses live in
[`rooms.ps1`](rooms.ps1). If a TV gets a new address, change it there; better, give each TV a DHCP reservation on
the router so it doesn't move.

Every command below needs **USB debugging and Network debugging switched on** on that TV
(Settings > System > Developer options), because it talks to the TV over the network. Accept the
"Allow USB debugging?" prompt on the TV the first time, then switch debugging off again when you're done.

## Common tasks

| To | Run |
|----|-----|
| Rebuild and install the launcher after editing a room's `config.xml` | `pwsh mytv\build-and-install.ps1 -Room 'Master Bedroom'` |
| Undo a room's debloat and give Home back to Google TV | `pwsh mytv\restore.ps1 -Room 'Office'` |
| Re-enable one app | `pwsh mytv\restore.ps1 -Room 'Office' -Package com.vt.hishow` |
| Snapshot a TV's packages and settings (read-only) | `pwsh mytv\backup.ps1 -Room 'Master Bedroom'` |
| Push a theme | `pwsh tools\theme.ps1 -TvHost <address> themes\christmas` |
| Remove the theme (plain black) | `pwsh tools\theme.ps1 -TvHost <address> -Reset` |
| See what is disabled | `$env:TV_HOST='<address>'; tools\venv\Scripts\python.exe tools\tv.py "pm list packages -d"` |

To remove the launcher too, after a restore: `tools\venv\Scripts\python.exe tools\tv.py "pm uninstall com.gathin.tvhome"`.
On the TV itself, a disabled app comes back from Settings > Apps > See all apps > Show system apps > the app > Enable.

## Change the dock, the hidden apps or the number of slots

Edit **`mytv\<room>\config.xml`**, not `launcher\app\src\main\res\values\config.xml`: the room's file is
copied over the launcher's on every build. Then run `build-and-install.ps1` for that room.

- `dock_apps`: package names in dock order.
- `hidden_apps`: apps never shown as tiles (they stay installed and open from Settings > Apps).
- `columns`: dock slots and grid columns, 4 to 7. Tiles shrink from 5 up.
- `show_inputs_pill`: the Inputs button in the top bar.
- `show_network_pill`: the Wi-Fi button in the top bar.
- **House rule: the top bar is Beast and Settings only, on every TV, current and future.** `show_network_pill` and
  `show_inputs_pill` are `false` in all three rooms. A new room copies the office `config.xml`, which already has
  both off; keep them off. The remote's Input button still opens the Inputs list on a TV that has inputs.
- `weather_widget`: replaces the date next to the clock with a weather widget: current temperature (Fahrenheit),
  a condition icon and today's high and low. The data comes from Open-Meteo over HTTPS (no account or key); the
  place is the TV's own last known location, rounded to about 1 km, else `latitude` and `longitude` from the same
  file. Refreshes every 30 minutes while Home is showing. If it can't get a forecast, the date stays. The room
  config must define it (`true`/`false`) or the build fails, because `build-and-install.ps1` copies that file
  over the launcher's.
- `explore_page`: turns the rail's tab row into Home / Explore. Explore opens a full-screen page with a huge
  TMDB backdrop, the focused title's logo, rating, year and overview, and a Movies / Shows tab row over one
  poster rail. Movies are TMDB trending-this-week that the IPTV provider or download library carries, topped up
  with the newest Local/IPTV movies; Shows are the downloaded series. Focusing a show opens its episodes in
  order as wide stills, each with its own TMDB name and overview. On in all three rooms. Needs `media_rail` on.
  Episode stills and backdrops are pulled at TMDB `original` size for a crisp 1080p banner (posters stay `w500`).
  A just-aired episode whose still TMDB has not yet promoted into the season payload is filled from the per-episode
  images endpoint; an episode with no still at all falls back to the show's wide TMDB backdrop, never the stretched
  vertical poster. Note a limit: when TMDB's only uploaded still for an episode is itself low-res (some are 400x225),
  that is the source and it looks soft blown up — no launcher setting fixes a low-res source.
- `calendar_widget`: an "Events" line under the date showing upcoming Google Calendar events. The private ICS
  feed URL lives in `calendar_secrets.xml` (below); `CalendarClient` fetches it over HTTPS and expands recurring
  events, covering the start of today through seven days out, so today's and all-day events stay on. Several
  events rotate every 10 seconds with a crossfade; a long title scrolls right to left once. With no feed set, or
  nothing coming up, it reads "No upcoming events". Refreshes every 30 minutes while Home is showing. On in all
  three rooms; the room config must define it (`true`/`false`) or the build fails, as with `weather_widget`.
  Like the others, the room config must define it or that room's build fails.
- `beast_page`: the Beast button in the top bar (the Mediarium server panel). On in all three rooms; see below.
- `beast_url`: the address Beast opens. Empty hides the button even when `beast_page` is true.

Package names: TiviMate `ar.tvplayer.tv`, Netflix `com.netflix.ninja`, YouTube `com.google.android.youtube.tv`,
Prime Video `com.amazon.amazonvideo.livingroom`, Disney+ `com.disney.disneyplus`, Hulu `com.hulu.livingroomplus`,
Apple TV `com.apple.atve.androidtv.appletv`, Plex `com.plexapp.android`, Kick `com.kick.mobile`,
Just Player `com.brouken.player`, SmartTube beta `org.smarttube.beta`.

Tiles can also be reordered on the TV itself: hold OK on the Settings pill and pick Reorder.

## Office

Hisense SmartTV 4K (lushan), Google TV 12, firmware STT2.230929.001.091. Set up on 2026-11-02.

- **8 packages disabled** (never uninstalled), listed with reasons in
  [`Office\debloat.txt`](Office/debloat.txt): Hisense viewing-data tracking (ACR), Google partner
  tracking, the feedback uploader, calendar sync, Family Moment, store demo mode, first-time setup hooks and the
  Hisense "cleaner". Applied as `batch1.txt` and `batch2.txt`, which the restore replays.
- **Home**: the Clean Your TV launcher. Google TV home (`com.google.android.apps.tv.launcherx`) is disabled so it
  can't take Home back.
- **Dock (5)**: TiviMate, Kick, Netflix, SmartTube (beta), Apple TV. SmartTube (`org.smarttube.beta`, 32.68) is in
  YouTube's old slot; stock YouTube is still installed, just not in the dock. It was installed by copying the APK
  from the bedroom TV, see the Master Bedroom section.
- **Inputs menu**: HDMI 1-3 and Live TV (`com.vt.tv.tvcenter`, hidden from the grid).
- **Theme**: `themes\christmas` (Santa's sleigh wallpaper, glass dock). Screensaver: the stock Google TV
  backdrop.
- **Beast button**: on, opening the Mediarium server panel at `http://xxx.xxx.xxx.15:8264/`. See the Beast page
  section below; it is on in all three rooms.
- **Weather widget**: on, in place of the date next to the clock (current temperature, condition icon, today's
  high/low). The Wi-Fi and Inputs pills are off (house rule above); Beast and Settings stay. Verified on screen
  2026-11-04 against Open-Meteo's own answer for the same place (51°F shown vs. 50.5°F/cloud cover 98% from the
  API at that moment); a second source (wttr.in) said 55°F at the same time, so don't expect sources to agree
  exactly. See `weather_widget` above.
- **Kept on by choice**: every streaming app, Google Assistant, Alexa, AirPlay, DLNA, Miracast, firmware updates
  and the screensaver. No ad-blocking DNS.
- **Backups**: `Office\backups\` (before any change, after the old launcher was removed, and a later one).

**Remote buttons.** Netflix, Input and the mic work. The YouTube, Prime Video, Disney+ and gear buttons did
nothing once Google TV home was disabled. They can come back with
`pwsh mytv\build-and-install.ps1 -Room 'Office' -HomeKeyService` plus these `app_keys` and
`settings_key_keycodes` in its `config.xml` (measured on another lushan set): `scan:469:com.google.android.youtube.tv`,
`scan:468:com.amazon.amazonvideo.livingroom`, `scan:418:com.disney.disneyplus`, and keycode `83` for the gear.
The trade: while that helper runs, holding Volume up/down moves one step per press instead of ramping.
Decided on 2026-11-02: no helper.

## Master Bedroom

onn 4K Pro Streaming Device (jarvis2), Google TV 14, build URO3.260203.035.A1.15640252. Set up on 2026-11-02.

- **Snapshots**: `Master Bedroom\backups\onn-jarvis2-before\` (before any change) and `onn-jarvis2-after\`.
- **13 packages disabled** (never uninstalled), listed with reasons in
  [`Master Bedroom\debloat.txt`](Master%20Bedroom/debloat.txt), based on the repo's `examples\onn-4k-pro-gen2\`
  list for this exact build. Applied as `batch1.txt` to `batch3.txt`, which the restore replays.
- **Wi-Fi**: the saved network was first owned by the setup wizard, so it was forgotten and joined again before
  the wizard was disabled (it now belongs to the system). Re-enable the wizard before a factory reset:
  `pwsh mytv\restore.ps1 -Room 'Master Bedroom' -Package com.google.android.tungsten.setupwraith`.
- **Casting**: if casting from a phone stops working, re-enable the setup wizard (same command); on some Google TV
  devices the Cast credentials live in it.
- **Home**: the Clean Your TV launcher; Google TV home is disabled.
- **Just Player** 0.218: copied from the office TV and registered as a Play Store install, so it keeps updating.
- **Dock (6)**: TiviMate, Plex, SmartTube (beta), Disney+, Prime Video, Apple TV. SmartTube
  (`org.smarttube.beta`, 32.68) was installed on the TV and swapped into YouTube's slot; the stock YouTube app is
  still installed, just not in the dock. The build running here is the universal one (35 MB, arm64 and 32-bit
  libraries), which is what the office got. `Master Bedroom\SmartTube_beta_32.68_arm64-v8a.apk` (git-ignored) is
  the arm64-only build: it will not install on these 32-bit boxes. To copy the working build to another TV, pull it
  from this one: `adb pull <path from "pm path org.smarttube.beta">`, then install it with `pm install-create` /
  `install-write` / `install-commit`.
- **Hidden from the grid**: Play Games (a stub that opens the Play Store), YouTube Music and Just Player
  (the Movies & Shows rail opens it for IPTV titles).
- **Beast button and Movies & Shows rail**: on, as in the other two rooms. Playing a "Local" download needs mpv
  (`is.xyz.mpv`), which is **not installed on this box yet**; Just Player (IPTV titles) is.
- **No Inputs button**: a streaming box has no HDMI inputs or tuner, so `show_inputs_pill` is off and
  `live_tv_package` is empty.
- **Theme**: `themes\christmas`, same as the office.
- **Weather widget**: on, in place of the date next to the clock, same as the office. Dock screenshot-verified
  2026-11-04. The Wi-Fi pill is off here too (house rule above).
- **Closing ADB on this box**: USB debugging alone opens port 5555 here, so both USB debugging and network
  debugging have to go off.

## LivingRoom TV

onn. Streaming Device 4K pro (SNA), Google TV 14, build URO4.260304.011.B1.15051976, `xxx.xxx.xxx.233`. Set up on
2026-11-03 to match the office, minus the Hisense-only parts (no HDMI inputs or tuner on a streaming box).

- **Dock (5)**: TiviMate, Kick, Netflix, YouTube, Apple TV — the office's dock, except the office now has
  SmartTube where this TV still has YouTube. No theme;
  Beast button and the merged Movies & Shows rail (`media_rail` on, `iptv_rail` off) are on, 5 columns. Its
  `config.xml` differs from the office's only in `live_tv_package` (empty), `hidden_apps` (empty) and
  `show_inputs_pill` (false) — the parts that depend on this being a streaming box with no HDMI inputs or tuner.
- **13 packages disabled** (never uninstalled), listed in [`LivingRoom TV\debloat.txt`](LivingRoom%20TV/debloat.txt),
  from the repo's `examples\onn-4k-pro-gen2\` list; applied as `batch1.txt` to `batch3.txt`. Checked identical across
  a reboot. Everything else stays on, as in the office.
- **Players**: mpv (downloads) and Just Player 0.218 (IPTV, Play Store installer) were copied from the office TV;
  both boxes are armeabi-v7a.
- **Wi-Fi**: the saved network belongs to the system (`android.uid.system:1000`), so disabling the setup wizard
  is safe here. Re-enable it before a factory reset:
  `pwsh mytv\restore.ps1 -Room 'LivingRoom TV' -Package com.google.android.tungsten.setupwraith`.
- **Backups**: `LivingRoom TV\backups\before\` (taken before any change).
- **Weather widget**: on, in place of the date next to the clock, same as the office.
- **Network ADB**: left open on purpose, same as the other two rooms; not a pending task.

## Secrets files (git-ignored, recreate on a new PC)

Three files in `launcher\app\src\main\res\values\` hold the logins and addresses. They are ignored by git, so a
fresh clone has none of them. Each one is built into the launcher, so after changing a value rebuild every room
that uses it. Every file has the same shape; the values below are placeholders.

`iptv_secrets.xml`: the IPTV provider's Xtream Codes login. Used by the plain IPTV row and by the merged rail.

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="iptv_server" translatable="false">https://host</string>
    <string name="iptv_username" translatable="false">USERNAME</string>
    <string name="iptv_password" translatable="false">PASSWORD</string>
</resources>
```

`beast_secrets.xml`: the Beast (Mediarium) download library, for rooms with `media_rail` on. All four values are
needed; if one is empty the Beast source is skipped and only IPTV shows.

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="beast_api_base" translatable="false">http://SERVER:PORT</string>
    <string name="beast_api_key" translatable="false">API_KEY</string>
    <string name="beast_library_root" translatable="false">/absolute/library/path</string>
    <string name="beast_media_base" translatable="false">http://SERVER:FILE_PORT</string>
</resources>
```

- `beast_api_base`: the Mediarium server, no trailing slash. It is only read for the dashboard and file paths.
- `beast_api_key`: the Mediarium API key (admin). It is sent as an `X-API-Key` header only, never in a playback URL.
- `beast_library_root`: the absolute library path the server reports as the start of each file path, for example
  `/mnt/e/Media/Torrents`. It must match what the server reports exactly, or titles get no playback URL.
- `beast_media_base`: the static HTTP file server rooted at that same folder, no trailing slash. Playback swaps
  the library root for this address.
- Both hosts must be allowed as cleartext in `launcher\app\src\main\res\xml\network_security_config.xml`, or the
  launcher cannot reach them over plain HTTP.

`tmdb_secrets.xml`: a TMDB v3 API key. It fetches the wide backdrops and the "Show more" Streaming section.
Without it the rail still works, with posters instead of backdrops and an empty Streaming section.

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="tmdb_api_key" translatable="false">TMDB_V3_API_KEY</string>
</resources>
```

The rail turns on only when at least one of Beast or IPTV is fully set. Check what is missing with
`git status --ignored launcher\app\src\main\res\values`.

`calendar_secrets.xml`: the private "Secret address in iCal format" from Google Calendar, for the
`calendar_widget` Events line. Anyone with this URL can read the whole calendar, so it is git-ignored and never
committed; if it leaks, reset it in Google Calendar and drop the new one in here (no code change). Without the
file the Events line just reads "No upcoming events".

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="calendar_ics_url" translatable="false">GOOGLE_CALENDAR_SECRET_ICS_URL</string>
</resources>
```

## Latest movies row (plain IPTV row, not used now)

All three rooms now use the merged Movies & Shows rail instead (`media_rail` on, `iptv_rail` off). This is the
older IPTV-only row; its settings are kept in case you want it back in a room.

A row of posters between the clock and the dock: the 20 newest movies on the IPTV provider TiviMate uses, read
from its Xtream Codes API over HTTPS. Up from the dock goes to the row, Down goes back; the top line names the
focused movie and when it was added. OK plays it in Just Player (`com.brouken.player`); TiviMate can't be told
to play a movie from outside. The list is asked for when Home comes up, at most every 10 minutes, and cached so
the row shows at once. The account allows 5 streams at once.

- Settings: `launcher\app\src\main\res\values\iptv_rail.xml` (on/off, count, categories to skip, player app).
  Shared by all rooms.
- Login: `launcher\app\src\main\res\values\iptv_secrets.xml`, ignored by git and built into the launcher on each
  TV. If the password changes, edit it there and rebuild every room. Format:
  `<string name="iptv_server">https://host</string>`, plus `iptv_username` and `iptv_password`.
- Turn it off: set `iptv_rail` to `false` (or delete `iptv_secrets.xml`) and rebuild.
- VLC was tried first and removed: given a second movie while the first was still open, even in the background,
  it showed a black screen or closed.

## Beast page (all rooms)

A **Beast** button in the top bar, next to Inputs, opens the [Mediarium](https://mediarium.app/) media-server
panel full-screen at `http://xxx.xxx.xxx.15:8264/`. It is the real web panel in a locked-down WebView, so
everything the panel can do (search, add, manage downloads) works from the couch.

- **Sign in once.** The first time, sign in with the on-screen keyboard. The session cookie is kept, so later
  visits open already signed in. If it ever logs you out, just sign in again.
- **Pointer, not focus.** The panel is a mouse-and-touch website, so the remote drives an on-screen arrow
  cursor instead of jumping between fields:
  - Arrows move the cursor; hold a direction and it speeds up.
  - OK clicks where it points (hold OK for a long-press).
  - Push the cursor against a screen edge to scroll what is under it.
  - Back goes back inside the panel; Back again leaves to Home.
- **Stays on the panel.** Only `beast_url`'s own address loads. A link to anywhere else is refused, so the page
  can't wander onto the open internet.

Turn it on or off per room in `mytv\<room>\config.xml`, then rebuild that room:

- `beast_page` (`true`/`false`): shows the Beast button. `true` in all three rooms.
- `beast_url`: the address it opens. Empty hides the button even when `beast_page` is true.

Because the panel is served over plain HTTP, the host `xxx.xxx.xxx.15` is allowed as a cleartext exception in
`launcher\app\src\main\res\xml\network_security_config.xml`. If the Beast server ever moves, change both
`beast_url` and that host, then rebuild. Nothing else on the launcher is allowed to use plain HTTP.

The server's API key file `Office\beast_key.txt` (git-ignored) is **not** used by this page — the
WebView signs in with the web panel instead. It can be kept for other tooling or deleted.

## Tried and removed: Plex row

A "recently added on Plex" row was built and removed. Plex shows its Remote Watch Pass paywall when another app
asks it to play something (`plex://watch/video?uri=…`), even at home; pressing play inside Plex works.

## Optional: ad-blocking DNS (not set on either TV)

Skipped on purpose to avoid breaking TiviMate playlists or app sign-ins. To try it on one TV:
`tools\tv.py "settings put global private_dns_specifier dns.adguard-dns.com"`, then
`tools\tv.py "settings put global private_dns_mode hostname"`. It does not block YouTube ads.
Undo: `tools\tv.py "settings put global private_dns_mode opportunistic"`.

## Files

| File | What it does |
|------|--------------|
| `rooms.ps1` | Room names, addresses and stock launchers |
| `build-and-install.ps1` | Build the launcher with a room's `config.xml` and install it |
| `restore.ps1` | Undo a room's debloat, or re-enable one package |
| `backup.ps1` | Read-only snapshot of a TV's packages and settings |
| `toolchain.ps1` | Portable JDK 17 + Android SDK in `%LOCALAPPDATA%\cleanyourtv-toolchain` (delete that folder to remove) |
| `<room>\config.xml` | That TV's launcher settings |
| `<room>\debloat.txt`, `<room>\batch*.txt` | What was disabled, with reasons, and the batches as applied |
| `<room>\backups\` | Snapshots taken with `backup.ps1` (kept out of git) |
