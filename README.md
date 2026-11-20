# MyTV

My three home TVs, running a customised fork of [Clean Your TV](https://github.com/Gathin23/cleanyourtv): the
stock Google TV home screen is replaced by a clean, Apple TV style launcher (`com.gathin.tvhome`), and the
tracking and unused system services are disabled (never uninstalled). This is a private, personal backup;
nothing here is meant for upstream. The original project's README is kept for reference at
[`README.upstream.md`](README.upstream.md).

![The MyTV home screen](docs/screenshots/home.jpg)

## What I added on top of upstream

- **Cinematic Dashboard**: a full-screen TMDB backdrop with title, overview, year, runtime, US age rating and a
  yellow `TMDb` score badge. The Recently Added movie rail sits below the showcase.
- **Shows**: a separate rail and show-detail screen with the show's artwork, overview, seasons and episode cards.
  Selecting Shows focuses its first card and pauses the movie spotlight.
- **Auto-rotating movie spotlight**: it cross-fades every 10 seconds while Movies is idle. Entering Movies clears
  focus so rotation runs; the next navigation key focuses the first movie. A focused movie also clears after 10
  seconds of no D-pad activity.
- **Drawer navigation**: Back or the rail's left edge opens Dashboard, TV, Movies, Shows, Apps, Beast, Home
  Assist and Settings. The drawer highlights the current section and physical Home always returns to Dashboard.
- **Apps page**: a standalone catalogue using the launcher's original TV banner tiles, app order, focus scale and
  animation. It keeps the same left drawer and never changes the launcher app order.
- **Beast and Home Assist**: both open from the drawer through the existing guarded WebView. The old Beast,
  Home Assistant and Settings top pills are hidden.
- **Series freshness**: a show moves up using its newest downloaded episode file's modification time, rather
  than the older date at which the series was first added.
- **Weather widget** in place of the date next to the clock: current temperature, a condition icon and today's
  high and low, from the TV's own location.
- **Per-room setups** under [`mytv/`](mytv/README.md): one config, debloat list and set of scripts per TV.

## What is Mediarium (Beast)?

"Beast" is my nickname for **[Mediarium](https://mediarium.app/)**, the self-hosted media server running on my
home network. It is the source behind the Dashboard rails and the Beast drawer destination, and nothing in it
leaves the LAN.

![Mediarium Dashboard: server CPU, memory and storage, and download status](docs/screenshots/beast-dashboard.jpg)

What it does:

- **Keeps a download library.** Mediarium manages a library of downloaded movies and shows and serves the files
  over my home network. The launcher reads that library for the "Local" titles in the rail and plays them in mpv.
- **Has its own web panel.** Mediarium runs a web dashboard (search, add, and manage downloads) on a port on the
  server. The launcher's **Beast drawer item** opens that real panel full-screen in a locked-down WebView, so I can
  drive it from the couch with the remote. The WebView only ever loads that one address; links to anywhere else
  are refused, so it can't wander onto the open internet.
- **Feeds the rail alongside IPTV.** The Movies & Shows rail merges Mediarium's downloads with the newest movies
  from my IPTV provider (read over its Xtream Codes API). When the same title exists in both, the download wins.
  TMDB fills in the wide backdrops, logos, ratings and overviews.

How the pieces fit:

| Piece | Role |
|-------|------|
| Mediarium server ("Beast") | Self-hosted on the LAN: the download library, the file server, and the web panel |
| Beast drawer item | Opens the Mediarium web panel full-screen in a guarded WebView |
| Dashboard rails | Mediarium downloads (mpv) + IPTV newest movies (Just Player), deduped, art from TMDB |
| Apps page | Original CleanYourTV banner tiles in a standalone catalogue |

A tour of the panel:

![Mediarium Discover: filter bar and a Plex watchlist rail with Downloaded badges](docs/screenshots/beast-discover.jpg)

*Discover* — browse and filter movies, shows and books (by genre, year, popularity), with "hide owned" and a
Plex watchlist rail.

![Mediarium Library: Movies/TV/Ebooks tabs with rich filters and download-status chips](docs/screenshots/beast-library.jpg)

*Library* — everything already in the collection, filtered by codec, HDR, resolution, audio, subtitles and more,
with per-status counts.

![Mediarium Upcoming: a release calendar for the month](docs/screenshots/beast-upcoming.jpg)

*Upcoming* — a release calendar (month, posters, backdrops or agenda) for titles on the way.

![Mediarium Activity: download status tiles and a "waiting for a release" card](docs/screenshots/beast-activity.jpg)

*Activity* — what is downloading, waiting, or finished, and why a title is still pending.

The server addresses, the IPTV login and the TMDB key are **not** in this repo: they live in git-ignored
`*_secrets.xml` files that a fresh clone recreates locally. See
[`mytv/README.md`](mytv/README.md#secrets-files-git-ignored-recreate-on-a-new-pc) for the exact files and the
[Beast page notes](mytv/README.md#beast-page-all-rooms) for how the panel behaves on the remote.

### Dashboard, Shows and the Apps page

![Explore, Movies tab: a full-screen backdrop with the title logo, rating and overview over a poster rail](docs/screenshots/explore.jpg)

The side drawer replaces the old Home / Explore tabs:

- **Dashboard / Movies** uses the newest Local and IPTV movies. It enters unfocused so the large backdrop rotates
  every 10 seconds; the first navigation key focuses the first card. A movie focus clears after 10 seconds idle.
- **Shows** uses downloaded series and focuses the first card after the rail switch. It pauses the movie rotation.
  OK opens the show's season/episode page; Back returns to the previous screen.
- **Apps** opens a separate catalogue rendered with the original launcher `AppAdapter` tiles and banner artwork.
  Its ordering is the launcher's configured ordering, not a new app sort.
- The full-screen backdrop, episode title, show title, year, runtime, age rating, `TMDb` score and overview follow
  the focused item.
- **Age ratings** come from TMDB's US listing and appear next to the year, both here and on the Home showcase.
  Movies show their certificate (PG-13, R and so on) and series show their TV rating (TV-14, TV-MA and so on). A title with no US release on TMDB shows no age rating, so an unreleased film stays
  blank until TMDB lists one.

![Explore, Shows tab: The Amazing Race backdrop with its logo over a rail of series](docs/screenshots/explore-shows.jpg)

Focusing a show opens its episodes in order as wide stills, each with its own TMDB name and overview:

![Explore episodes view: The Amazing Race S39 with a rail of episode stills and titles](docs/screenshots/episodes.jpg)

## My TVs

| Room | Device | Features |
|------|--------|------|
| Office | Hisense SmartTV 4K (lushan), Google TV 12 | Dashboard, Shows detail, Apps catalogue, drawer, Weather widget |
| LivingRoom TV | onn. Streaming Device 4K pro (SNA), Google TV 14 | Same launcher features, Weather widget |
| Master Bedroom | onn 4K Pro (jarvis2), Google TV 14 | Same launcher features, Weather widget |

Addresses, what was disabled and why, remote-button notes and the restore commands are in
[`mytv/README.md`](mytv/README.md). Each room's folder has its own `config.xml`, `debloat.txt` and the batches
as applied.

## Common tasks

Run from the repo root in PowerShell. The TV needs USB debugging and Network debugging switched on.

| To | Run |
|----|-----|
| Build the launcher for a room and install it | `pwsh mytv\build-and-install.ps1 -Room 'LivingRoom TV'` |
| Snapshot a TV before changing it | `pwsh mytv\backup.ps1 -Room 'LivingRoom TV'` |
| Undo a room's debloat and give Home back | `pwsh mytv\restore.ps1 -Room 'LivingRoom TV'` |
| Disable a batch of packages | `pwsh tools\disable.ps1 -TvHost <ip>:5555 'mytv\<room>\batch1.txt'` |
| Push the Christmas theme | `pwsh tools\theme.ps1 -TvHost <ip>:5555 themes\christmas` |
| Take a screenshot | `$env:TV_HOST='<ip>:5555'; .\.venv\Scripts\python.exe tools\tv.py --screenshot shots\x.png` |

Edit `mytv\<room>\config.xml`, not `launcher\app\src\main\res\values\config.xml`: the room's file is copied over
the launcher's on every build, so the launcher's copy always shows the last room built.

## Setting up a new TV like the office

1. Add the room to `mytv\rooms.ps1`, and to the `ValidateSet` in `build-and-install.ps1`, `backup.ps1` and
   `restore.ps1`.
2. Back it up first with `backup.ps1`. Check `dumpsys wifi | grep -o 'cname=[^ ]*'` before disabling the setup
   wizard: if it owns the saved Wi-Fi network, disabling it drops the TV off Wi-Fi.
3. Copy the office `config.xml` into the new room's folder and change what the hardware needs (a streaming box
   has no Live TV app or HDMI inputs). The main navigation is now in the side drawer, so the old Home Assist,
   Beast and Settings top pills stay hidden. Keep `weather_widget`, `explore_page` and `media_rail` on.
4. Disable the debloat batches one at a time with a screenshot between them, setup wizard last.
5. Install mpv and Just Player if the room uses the rail, then run `build-and-install.ps1`.
6. Push the theme, reboot once, and compare `pm list packages -d` before and after.

## Build

JDK 17 and the Android SDK are set up by `mytv\toolchain.ps1` into `%LOCALAPPDATA%\cleanyourtv-toolchain`. The
release APK must stay under 786,432 bytes (the repo's size gate); the current build is about 644 KB.

## Not in this repo

These stay on my PC on purpose, so a fresh clone will not build the rail until they are recreated:

- `launcher\app\src\main\res\values\iptv_secrets.xml`, `beast_secrets.xml`, `tmdb_secrets.xml`, `ha_secrets.xml`: the IPTV
  login, the Beast server addresses and API key, the TMDB API key, and the Home Assistant token. The exact files and what each value means
  are in [`mytv/README.md`](mytv/README.md#secrets-files-git-ignored-recreate-on-a-new-pc).
- `mytv\*\backups\`: package and settings snapshots of each TV, taken before any change.
- `mytv\Office\beast_key.txt`: the Beast server API key.
- `tools\adbkey`: the ADB key that the TVs authorised.
- `shots\`: screenshots, which show the Wi-Fi name.

## Credit and licence

Built on Clean Your TV by Gathin23, released under the MIT licence; see [`LICENSE`](LICENSE) and the original
page at [`README.upstream.md`](README.upstream.md). The room setups, the Movies & Shows rail, the Explore page
and the other changes above are my own additions to the fork.
