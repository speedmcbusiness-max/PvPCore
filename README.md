# VPerPlayerKit 2.1

Per-player kits plus a ranked RTP queue, tier system and totem practice for Paper **1.21.8+** (Java 21).
The queue menus use Minecraft's native dialogs, which need Paper 1.21.7 or newer.

## Build
    mvn package          # jar ends up in target/VPerPlayerKit-2.1.0.jar
Or push this folder to GitHub, the included Action builds the jar (Actions tab > Build > Artifacts).
If your server runs a different Paper version, change `paper-api` in pom.xml to match.

## Upgrading
Delete the old `config.yml` and `menus.yml` (new keys were added), keep `kits.db` / your MySQL data.

## Setup checklist
1. Set `rtpq.world` in config.yml to the name of the world players are teleported to (default `world`). The RTP queue, FFA, Gladiator,
   `/rtp` and `/qrtp` all find plains, desert, badlands, snow and mushroom spots **inside that world** (biome groups: `rtp.biomes`).
   Optional: give a biome its own world with `rtpq.worlds.<biome>: <world name>`. "Auto" picks any spot.
2. Stand where players should return after matches and run `/rtpq setspawn`.
3. Totem practice (optional):
   - `/admintotemprac setting spawn` where players arrive,
   - `/admintotemprac create`, then with the gold axe left click = corner 1, right click = corner 2,
   - `/admintotemprac button`, then right click the button block with the blaze rod.

## /rtp and /qrtp
`/rtp` opens the **RTP** dialog (Plains, Desert, Badlands, Snow, Mushrooms). `/rtp <plains|desert|badlands|snow|mushroom>` teleports straight there.
Anything else (nether, end, ...) is rejected, you have to pick one of the biomes. The biomes are searched in `rtpq.world` (or the biome's own world from `rtpq.worlds`). Mushroom islands are rare, raise `rtp.biome-search-radius` if they are not found.
`/qrtp` (quick rtp) moves you to a different random place in the world you are already in, only in those worlds. Both avoid duel
arenas, use a cooldown (`rtp.cooldown-seconds`) and do not work during matches or while queued.

## Moderation tools
| Command | Permission | What it does |
|---|---|---|
| `/report <player>` | perplayerkit.report | opens the Report dialog (reason: Cheating, Macro, AutoTotem, Hacks, or type your own) |
| `/report <player> <any reason>` | perplayerkit.report | sends the report straight away with your own reason |
| `/offend <player> <Cheating\|Macro\|AutoTotem\|Hacks>`, `/unoffend <player>` | perplayerkit.offend | bans / unbans: the player can play but cannot see others and shows BANNED above the nametag (see below) |
| `/v:rank ...` | perplayerkit.rank.admin | ranks and the web rank editor (see below) |
| `/sus` | perplayerkit.sus | large chest: head, name and reasons of every reported player. Left click = teleport, right click = clear reports, shift click = `/ss` |
| `/sfmode` | perplayerkit.sfmode | puts you in spectator and blocks creative / survival / adventure until you run it again |
| `/damage <player> <amount>` | perplayerkit.autocheck | hurts the player (amount in health points) |
| `/autocheck <player> <totems>` | perplayerkit.autocheck | pops that many totems and measures how fast they put a new one in their hand, flags AutoTotem |
| `/freeze <player>`, `/unfreeze <player>` | perplayerkit.freeze | the player cannot move, interact or use commands (chat stays on). Stays frozen after a relog |
| `/ss <player>` | perplayerkit.ss | freezes the player and teleports them (and you) to the ss spawn. Run it again to send them back to the exact place they were |
| `/ssspawn set` (also `/vcrystal:ssspawn set`) | perplayerkit.ss | sets the ss spawn where you stand |

`/autocheck` never kills: it stops when the player has no totem in their hands. A human needs about 6+ ticks to put a totem back, an AutoTotem
client 1-3 (`autocheck.suspicious-ticks`). Reports, bans, texts and the `/sus` look are customizable: `report`, `offend`, `freeze`, `ss`, `autocheck`
in config.yml, `report` in dialogs.yml, `sus` in menus.yml.

## Banned players (`/offend`, `/unoffend`)
`/offend <player> <reason>` no longer kicks: the player stays online and can play, but **cannot see any other player**, their chat only reaches
other banned players and staff, they cannot hurt players, and a red underlined **BANNED** floats above their nametag for everyone else
(`banned.tag`, `&` colors and `&#RRGGBB` hex work). They are kept out of queues, matches, events and tpa. `/unoffend <player>` lifts it. It survives relogs.
To also run a real ban of your punishment plugin, set `offend.ban-command`. Everything is under `banned` in config.yml.

## Nametags and ranks
Every player gets the nametag from `nametag.format`, default `{tier} &7| &f{player} &7| {flag}  {rank}` (shown above the head and in the tab list):
`{tier}` is the queue tier, `{player}` the name, `{flag}` whatever PlaceholderAPI placeholder you put in `nametag.flag-placeholder` (use the one from your
flags plugin, this plugin does nothing else with flags) and `{rank}` the player's top rank. It is built with scoreboard teams, so another plugin that
also controls teams (TAB and similar) can conflict, turn it off with `nametag.enabled: false` in that case.

Ranks (default youtuber, staff, admin, owner, helper, manager, tester; `perplayerkit.rank.admin`):
* `/v:rank <player> <set|add|remove> <rank> [time]` - `set` replaces all ranks, `add` adds one, `remove` takes it away. Time like `30m`, `12h`, `7d` or `perm`. Several ranks can be active, the one with the highest priority is shown.
* `/v:rank editor` - gives a link to a web page where you edit the ranks (tag text with a live preview, priority, add/delete ranks and optional console commands that run when a rank is added or removed, e.g. to give a LuckPerms group). The link works for `web.token-minutes`.
* `/v:rank list`, `/v:rank reload`.
The editor is served by the plugin on `web.port` (default 8123, open it in your firewall) over plain http. Set `web.public-url` to your address, or put https in front with a reverse proxy.
Placeholders: `%vperplayerkit_rank%`, `%vperplayerkit_rank_tag%`, `%vperplayerkit_banned%`.

## Teleport requests and /back
`/tpa <player>` (go to them), `/tpahere <player>` (they come to you), `/tpaaccept [player]`, `/tpadeny [player]`, `/tpacancel`,
`/tpaauto` (toggle: accept requests automatically), `/tpaignore <time|off>` (e.g. `10m`, `2h`, `1d`: ignore every request for that long).
Requests expire (`tpa.expire-seconds`), the teleport waits `tpa.delay-seconds` and is cancelled if you move. None of it works during matches,
queues, events, spectating or while frozen. `/back` returns to where you were before a tpa teleport or to where you died (run it again to come back).

## Totem practice: how to use the admin items
1. `/admintotemprac setting spawn`, stand where players should arrive.
2. `/admintotemprac create` gives a **gold axe**: left click a block = corner 1, right click a block = corner 2. The box between is where players take damage.
3. `/admintotemprac button` gives a **blaze rod**: right click the button players will press.
4. Players run `/totemprac`, press the button to start/stop, `/totemprac spawn start` for the aim target (works anywhere until the practice world is set up).
`/totemprac help` and `/admintotemprac help` print these steps in game (editable in `totemprac.help`).

## Customizing dialogs
Every dialog title, button label, tooltip, body line and input label lives in `dialogs.yml`. Edit it freely (MiniMessage,
keep the `{placeholders}`), then restart. Missing keys fall back to the built-in defaults.

## RTP queue
`/rtpq` opens the **RTP-Queue** dialog:
* **Select-Kit** opens the kit menu, left click a kit to select it (it turns into paper with "Selected" in the lore).
  The same happens in `/k` after you load a kit.
* **Join Queue** dialog: Biome (Auto, Plains, Desert, Mushroom, Snow, Badlands) and Kit (Selected Kit or any saved kit).
* **State** shows kills/victory/defeat/match played; clicking one opens that leaderboard.
* **Leaderboard**, **Match-History** (hover a match for when it was played) and **Tier-Info**.

How a match works
* Two queued players are matched (same biome or Auto). Both are teleported to a random safe spot in that biome's world,
  never close to another duel's arena (`rtpq.min-arena-distance`).
* The arena is a 250x250 danger zone: every player gets their own border, and red beacon beams mark the corners
  and edge middles. Leaving it shows a warning, then deals 5 hearts per second.
* First to 2 kills wins (first to 3 for tier tests). Lethal hits end the round, totems still pop normally.
* Afterwards blocks, crystals and drops in the arena are restored and both players are sent to the lobby with a cleared inventory.
* Disconnecting counts as a forfeit.
* The winner is put straight back in the queue (`rtpq.winner-auto-requeue`): kit restored, full health, a new arena, while the old one regenerates for others.

## Tiers
| Tier | Points | Tier | Points |
|---|---|---|---|
| HT1 | 60 | HT4 | 4 |
| LT1 | 45 | LT4 | 3 |
| HT2 | 30 | HT5 | 2.5 |
| LT2 | 20 | LT5 | 2 |
| HT3 | 10 | HT6 | 1.5 |
| LT3 | 6 | LT6 | 1 |
| | | Unranked | 0 |

The overall leaderboard is sorted by tier points. Queue wins add progress % (depends on both tiers, totems popped,
match time and kills, all tunable under `tier.progress`). At 100% your next queue match is a **tier test**: first to 3
against a player of your tier. Win = next tier. Lose = you have 2 chances, after both are used your progress drops to a
random 25-80%.

## Commands
| Command | Permission | Notes |
|---|---|---|
| `/kit` `/k`, `/kit1-14` `/k1-14`, `/lsk`, `/kit share-code <code>`, `/kitroom`, `/pk` | perplayerkit.use | kits |
| `/kitroom admin <category>`, `/pk create\|edit\|delete <kit>` | perplayerkit.kitroom.admin | |
| `/heal`, `/repair [all]`, `/regear` | perplayerkit.heal / repair / regear | |
| `/rtp [biome]`, `/qrtp` | perplayerkit.rtp / qrtp | random teleport |
| `/rtpq` (`/queue`), `/rtpq leave` | perplayerkit.use | queue dialog |
| `/rtpq setspawn`, `/setspawn` | perplayerkit.rtpq.admin | sets the lobby spawn |
| `/spawn` | perplayerkit.spawn | go to the lobby spawn (not during a duel) |
| `/leaderboard [overall\|kills\|victory\|defeat\|matchplayed]` (`/lb`) | perplayerkit.use | |
| `/tier [player]` | perplayerkit.use | |
| `/verify`, `/unverify [account]` | perplayerkit.verify (`unverify.admin` for others) | Discord linking |
| `/progress` | everyone | your tier test progress |
| `/result <player> <tier> [fight \| fight]` | perplayerkit.tier.admin | set a tier and post the Discord result |
| `/region [region]`, `/region <player> <region>` | perplayerkit.region (`.admin`) | your region for Discord results |
| `/tier [name]` or `/tier <playername\|dc> <ign\|discord id>` | perplayerkit.use | tier lookup |
| `/spec <player>` (`/spectator`), `/spec leave` | perplayerkit.spectate | spectate a match |
| `/event`, `/event leave` | perplayerkit.event | join/leave the event lobby |
| `/event open\|start\|close\|setspawn` | perplayerkit.event.admin | host an event |
| `/leave` | everyone | leave a duel (forfeit), Gladiator match or the queue |
| `/state` (`/stats`) | perplayerkit.use | open the State dialog |
| `/totemprac`, `/totemprac spawn start\|stop` | perplayerkit.use | go to / leave the practice world, aim target |
| `/ffartpq` (`/rtpffa`, `/rtpqffa`), `/ffartpq leave` | perplayerkit.ffa | FFA queue |
| `/ffartp wand\|create <name>\|del <name>\|list` | perplayerkit.ffa.admin | FFA arenas |
| `/admintotemprac create\|delete\|button\|setting` | perplayerkit.totemprac.admin | |

`/admintotemprac setting`: `damage`, `hits` (default 2), `interval` (ticks, default 50 = 2.5s), `gap` (ticks between the
hits, default 10), `prepare` (seconds, default 3), `spawn`, `lobby`. With no value it prints the current settings.

## Danger zone (duels and Gladiator)
Every match gets a danger zone that is randomly a **square** or a **circle** (`rtpq.zone.shapes`). It starts at `rtpq.arena-size`,
waits `shrink-start-seconds`, then repeatedly shrinks (`shrink-factor`) and **moves** to a new center that always lies inside the old zone,
until `min-radius`. Players outside take 5 hearts per second after the warning. The edge is marked by red beam columns
(`rtpq.zone.beacons`, glowing block displays that glide with the zone, so they can move unlike real beacons). A square also shows a
per-player world border (re-sent only when it moves), both shapes get red particles on the edge near the player, and beams that
vanish with an unloaded chunk are re-created when a player gets close again (`rtpq.zone.beam-range`).

## Totems
A totem only works while it is in a hand, exactly like vanilla: a player who holds no totem when a lethal hit lands loses the round, nothing is
used automatically. (An optional `rtpq.auto-equip-totem: true` moves one from the inventory into the offhand, it is off by default. The old
`rtpq.auto-totem` key does nothing any more.)

## Score bar, rebuild and biome change (duels)
* **Score bar:** a boss bar at the top of the screen shows `[head] yourScore - opponentScore [head]` (each player sees their own score first,
  spectators see player 1 vs player 2). Format, color, style and progress are under `scorebar` in config.yml. Player heads in text need Paper 1.21.9+,
  older servers show the names automatically. Remove the `<head:...>` parts from the format if you only want the numbers.
* **Rebuild:** before every new round the arena is rebuilt: placed, broken, exploded, burnt, flowing (water/lava), piston-moved and falling blocks are
  put back and crystals/drops are removed (`rtpq.rebuild.between-rounds`). After the match the whole arena is restored as before.
* **Change biome while queued:** the RTP-Queue dialog has a **Change Biome** button while you wait (also `/rtpq biome [name]`). A random biome
  (**Auto**) is locked: once you queued with Auto, or switch to it, it cannot be changed, leave the queue and join again to pick a biome.
* **Match leftovers:** a player who was in a match when the server crashed gets their match items cleared and is sent to spawn when they join again.

## Announcements
* **Win bar:** after a duel, FFA or Gladiator win everyone in the match (and its spectators) sees `<player> Won` above the hotbar (`win-actionbar`).
* **Win broadcast:** duel and Gladiator wins are also broadcast in chat (`duel-win-broadcast`, `gladiator-win-broadcast`).
* **High tier joins:** when an LT2, HT2, LT1 or HT1 player joins a duel, FFA or Gladiator it is announced, e.g.
  `[HT1] Steve V/S Alex [Spect]` (duel), `[LT2] Steve V/S Alex, Bob, Carl [Spect]` (FFA) or `[HT2] Steve V/S 5 players` with
  `[Spect]` on the next line (Gladiator). `[Spect]` runs `/spec <player>`. Settings: `announce.*`, texts: `highlight-duel`,
  `highlight-ffa`, `highlight-gladiator`, `spect-button` (config.yml, messages).

## Spectating (`/spec`, `/spectator`)
`/spec <player>` (or `/spectator <player>`) watches a running duel, FFA or Gladiator in spectator mode, `/spec leave` or `/leave` goes back
to where you were. You return automatically when the match ends or you disconnect. Spectators can only use `/spec` and `/leave`, are pulled back
if they fly more than `spectator.max-distance` blocks away, and cannot spectate while queued, in a match or in an event.
Everything is under `spectator:` in config.yml. Placeholder: `%vperplayerkit_vspec_count%` = how many people watch your match.

## Events (`/event`)
An admin hosts an event: `/event setspawn` once, `/event open` (broadcasts a [Join] link), players use `/event` to join the lobby and
`/event leave` to leave (they return to where they were). `/event start` (needs `event.min-players`) starts a FFA with everybody in the lobby,
the winner is broadcast, `/event close` cancels an event that has not started. Nobody can be hurt in the lobby. Permissions:
`perplayerkit.event` (players) and `perplayerkit.event.admin`.

## FFA RTP queue
`/ffartpq` (aliases `/rtpffa`, `/rtpqffa`) joins the FFA queue with your selected kit, `/ffartpq leave` or `/leave` leaves.
When `ffa.min-players` are queued a countdown starts (`ffa.start-delay-seconds`), then everybody is spawned on the spawn blocks of
a free arena and fights, last player standing wins (knocked-out players spectate). Arenas are protected while a FFA runs
(`ffa.allow-building`). The same match command lock as duels applies.

**Arena:** with `ffa.mode: rtp` (default) the FFA is played like a duel: a random spot in the RTP worlds, a danger zone with a **500 block radius**
(`ffa.zone.radius`) that is randomly a square or a circle and shrinks and moves with red beams on the edge, players are spread on a ring
(`ffa.spawn-radius`) and the blocks are restored afterwards. `ffa.mode: arenas` uses the spawn-block arenas from `/ffartp wand` instead.
All zone numbers are under `ffa.zone`.

**Timer:** `ffa.schedule` starts an FFA every `interval-seconds` (default 120). Everybody queued and everybody standing within
`ffa.lobby-radius` blocks of the lobby spot set with `/ffartp lobby` is teleported in automatically when the timer ends (needs a selected kit
and `ffa.min-players`). Reminders are sent at 60, 30, 10 and 5..1 seconds. `%vperplayerkit_ffa_next%` shows the seconds left.

Admin (`perplayerkit.ffa.admin`): `/ffartp wand` gives the **FFA Spawn Wand**.
* Right click a block = add it as a spawn block (right click again to remove), left click = remove. Selected blocks glow with particles.
* **Shift + right click** when done: the plugin asks for the arena name in chat (type `cancel` to abort).
* `/ffartp create <name>` creates the arena from the current selection without the chat prompt, `/ffartp del <name>` deletes, `/ffartp list` lists, `/ffartp lobby` sets the waiting area.

## Totem practice aim target
Inside the practice world `/totemprac spawn start` spawns a block (`totemprac.target.block`, default DIAMOND_BLOCK) in front of you that only
you can see. It grows a little (`grow-multiplier`) while your crosshair is on it and returns to normal size when you look away.
`/totemprac spawn stop` removes it. The namespaced form `/vperplayerkit:totemprac spawn start` works too.

## Discord linking
Set `discord.enabled: true`, `discord.token` (bot token), optionally `discord.guild-id` and `discord.announce-channel-id` in config.yml.
The bot needs no privileged intents, invite it with the `applications.commands` and `bot` scopes.
* In game `/verify` gives a code. In Discord run `/verify <code>`, the bot asks "Is this really you?" with Yes/No buttons, on Yes the accounts are linked.
* `/unverify` unlinks yourself, `/unverify <account>` (Minecraft name or Discord ID) unlinks someone else (`perplayerkit.unverify.admin`, Discord: Manage Server).
* Discord slash commands: `/verify <code>`, `/unverify [account]`, `/stats`, `/leaderboard <overall|kills|victory|playedmatch|defeat>`,
  `/tier <playername|dc> <ign|@user>`, `/progress` (only you see it).
* In game: `/tier <playername|dc> <ign|discord id>` (or `/tier <name>`), `/progress`, `/stats`, `/leaderboard <overall|kills|victory|playedmatch|defeat>`.
* When a player reaches a new tier (tier test win or `/result`) they get a Congratulations title in game and the tier result is posted to Discord (see below).
The jar bundles the Discord library (JDA), so it is larger than before. With `discord.enabled: false` nothing of it is used.

## Tier results in Discord
Every `/result` (and, optionally, every queue tier test promotion or final failure) posts a result to Discord, in one of two formats
depending on the tier (`results.high-min-tier`, default HT3: a result is **high** when the old or the new tier is HT3 or above):
* **Normal result** (Unranked / LT6 up to LT3): a message pinging the player, with a red embed titled `<player>'s Test Results 🏆` and the fields
  Tester, Region, Username, Previous Rank, Rank Earned (for example `High Tier 4` -> `Low Tier 4`). The player's skin is the thumbnail.
* **High result** (HT3 up to HT1): a text post, for example
  ```
  @Player - Ivri_ - Failed Low Tier 2
  HT3 Fights:
  > Lost 1-4 vs. ilmcl
  > Lost 0-4 vs. joshyyidk (DQ at 0-0)
  Demoted To Low Tier 3
  ```
  (header bold, "HT3 Fights:" underlined, fights quoted, last line italic. Promotions say "Passed ..." and "Promoted To ...".)
* The bot adds the reactions from `results.reactions` (default 👑 🥳 😱 😭 😂 💀) to both.
* `/result <player> <tier> [fight | fight ...]` (`perplayerkit.tier.admin`): the Tester is you (your linked Discord account if you have one).
  Fights are free text separated by `|`, e.g. `/result Ivri_ LT3 Lost 1-4 vs. ilmcl | Lost 0-4 vs. joshyyidk (DQ at 0-0)`. They only show in high results.
* Queue tier tests post automatically with Tester = `results.auto-tester` and the real scores of the match (`post-auto-promotions`, `post-auto-failures`).
* Region: players set theirs with `/region <NA|EU|AS|AU|SA|AF|ME>` (admins: `/region <player> <region>`), the default is `results.default-region`.
* Set `results.normal-channel-id` and `results.high-channel-id` (blank = `discord.announce-channel-id`). The bot needs Send Messages, Embed Links and
  Add Reactions in those channels. Every label, template, color, the skin URL and the emoji are in config.yml under `results`.

## Placeholders (PlaceholderAPI)
Install PlaceholderAPI and the plugin registers `%vperplayerkit_<name>%` automatically. Values are cached and refreshed in the background
(`placeholders.refresh-seconds`), so they are safe in scoreboards and tab lists.

| Placeholder | Value | Related commands |
|---|---|---|
| `kit_selected` | selected kit number or `None` | `/kit`, `/lsk` |
| `region` | your region | `/region` |
| `top1_name`, `top2_name`, `top3_name` | the top 3 of the overall leaderboard | `/leaderboard` |
| `top1_skin` ... `top3_skin` | URL of their full body skin picture (`placeholders.skin-url`) | `/leaderboard` |
| `top1_head` ... `top3_head` | URL of their head picture (`placeholders.head-url`) | `/leaderboard` |
| `top1_uuid`, `top1_tier`, `top1_value` | uuid, tier, tier points (works up to `top10`) | `/leaderboard` |
| `kits_saved` | how many kits you saved | `/kit`, `/kit1-14` |
| `max_kits` | configured number of kit slots | `/kit` |
| `tier` | tier name (HT1, LT3, Unranked ...) | `/tier`, `/result` |
| `tier_points` | tier leaderboard points | `/tier` |
| `tier_next` / `tier_next_points` | next tier and its points | tier info |
| `tier_progress` | progress % towards the tier test | tier info |
| `tier_progress_bar` | 10 character bar of the progress | tier info |
| `tier_test_ready` | yes/no | tier info |
| `tier_chances` | tier test chances left | tier info |
| `kills`, `wins` (`victory`), `losses` (`defeat`), `played` (`matchplayed`) | your totals | `/state`, `/stats` |
| `winrate`, `kd` | win percentage, kills per loss | `/state` |
| `rank_overall`, `rank_kills`, `rank_victory`, `rank_defeat`, `rank_matchplayed` | your leaderboard position or `-` | `/leaderboard`, `/lb` |
| `lb_<category>_<1-10>_name` | name at that position | `/leaderboard` |
| `lb_<category>_<1-10>_value` | value at that position | `/leaderboard` |
| `lb_<category>_<1-10>_tier` | tier at that position | `/leaderboard` |
| `queue_size` | players in the RTP queue | `/rtpq` |
| `queue_in` | yes/no, you are in the RTP queue | `/rtpq`, `/rtpq leave` |
| `queue_biome`, `queue_kit`, `queue_test` | your queue choices | `/rtpq` |
| `duels_active` | running duels | `/rtpq` |
| `in_match` | yes/no, in a duel, Gladiator or FFA fight | `/leave`, `/regear` |
| `match_type` | `duel`, `gladiator`, `ffa` or `none` | `/leave` |
| `duel_opponent`, `duel_score`, `duel_opponent_score`, `duel_target` | your current duel | `/leave`, `/regear` |
| `duel_time`, `duel_totems`, `duel_tier_test` | duel seconds, your totem pops, yes/no | `/leave` |
| `gladiator_open` | open Gladiator matches | `/gladiator join` |
| `gladiator_in`, `gladiator_role`, `gladiator_host`, `gladiator_state` | your match (`role` = host/teammate/opponent/none) | `/gladiator` |
| `gladiator_team_size`, `gladiator_team_max`, `gladiator_opponents`, `gladiator_opponents_max` | team counts | `/gladiator` |
| `gladiator_alive` | 1 if you are still in the fight else 0 | `/gladiator` |
| `ffa_queue_size`, `ffa_in_queue` | FFA queue | `/ffartpq` |
| `ffa_in_match`, `ffa_arena`, `ffa_players`, `ffa_alive` | your FFA | `/ffartpq` |
| `ffa_matches`, `ffa_arenas` | running FFAs, number of arenas | `/ffartp list` |
| `totemprac_in`, `totemprac_running`, `totemprac_target` | yes/no | `/totemprac` |
| `ffa_next`, `ffa_cooldown` | seconds until the next scheduled FFA (-1 = off) | `/ffartpq` |
| `ffa_next_formatted`, `ffa_cooldown_formatted` | the same as m:ss | `/ffartpq` |
| `vspec_count` (`spectators`) | people spectating your match (or the match you watch) | `/spec` |
| `spectating`, `spectating_target`, `spectators_total` | yes/no, who you watch, all spectators | `/spec` |
| `event_state`, `event_players`, `event_in` | closed/open/running, lobby size, yes/no | `/event` |
| `verified` | yes/no, linked to Discord | `/verify`, `/unverify` |
| `discord_name` | linked Discord name | `/verify` |
| `rtp_cooldown` | seconds until you can `/rtp` again | `/rtp`, `/qrtp` |
| `rtp_biomes` | the biomes `/rtp` accepts | `/rtp` |

Categories for the leaderboard placeholders: `overall`, `kills`, `victory`, `defeat`, `matchplayed`.
Example: `%vperplayerkit_lb_kills_1_name%` and `%vperplayerkit_lb_kills_1_value%`.

## Gladiator
`/gladiator create` opens the **Gladiator** dialog (Your Team, Opponent, Settings, Create, Close; hover a button for its description).
Only **LT2, HT2, LT1, HT1** players and players with `perplayerkit.gladiator.bypass` (server owner, YouTuber) can host
(`gladiator.min-tier` in config.yml; `gladiator.join-requires-tier: true` also applies it to opponents).
* **Biome** and **Kit** pickers sit in the main dialog (Biome: Auto + your RTP worlds; Kit: Selected Kit or any saved kit). The host's kit comes from that dialog; teammates and opponents fight with the kit they select in the Kit Menu (the selector opens automatically when they join without one, or use `/gladiator kit`). Set `gladiator.use-host-kit: true` to give everybody the host's kit instead.
* **Your Team** lists teammates (1-5 slots, 1 = solo) and has **add friend** (type an online player's name, they get an Accept link).
* **Opponent** lists the opponents with their head and name, 6 per page, **more** shows the next page.
* **Settings** > Your Team Setting / Opponent-Settings: team size (yours 1-5, opponents 2-15), **BlastProt** Single/Double and **Elytra** Yes/No.
  Back saves, Close discards. Settings lock once the match is created.
  Single = Blast Protection IV on the chestplate, Double = chestplate and leggings (other pieces get Protection IV).
  Elytra No removes elytras from the kits and blocks gliding.
* **Create** opens the match: everyone online is told and can click to join. It starts automatically when both teams are full,
  or the host runs `/gladiator start` once there is at least one opponent.
* Players fight with their **selected kit** in a normal RTP arena (250x250 danger zone, beacons, restored afterwards).
  A lethal hit knocks a player out (spectator), the last team standing wins. No friendly fire. Gladiator does not change tiers or stats.

| Command | Who |
|---|---|
| `/gladiator create` | LT2+ / bypass |
| `/gladiator join [host]` | anyone (no name = pick from a list) |
| `/gladiator disband` | host |
| `/gladiator leave`, `/gladiator kit`, `/gladiator start` | member / member / host |

## Totem practice
Everyone in the practice world is invisible to everybody else. The button only affects the player who presses it (the vanilla button effect is blocked and presses have a 1 second cooldown).
Press the registered button to start: a chat countdown runs for the prepare time, you get totems in every slot and the
offhand, then you are hit `hits` times every `interval` ticks while inside the damage area. Press the button again to stop:
you are healed and refilled. Running out of totems stops the session and refills you. Leaving the practice world
(or `/totemprac` again) clears your inventory and restores health and saturation.

## During matches
In a queue duel or a Gladiator fight only `/regear`, `/leave` and `/state` work (`match-commands.allowed`), so the kit commands and kit menu loading are disabled. `/regear` gives back the kit of that match. Set `match-commands.block-all-others: false` to block only the kit commands. Ops with `perplayerkit.match.bypass` are never restricted.

## Notes
* Kits, ender chests, kit room, public kits, share codes, stats, history and tiers all live in `kits.db` or MySQL, so
  several servers can share them (`storage.type: mysql`).
* Blocks changed by liquids flowing or pistons are not tracked for restoring, only placed/broken/exploded/burnt blocks.
* Arenas that were mid-match during a crash are not restored on restart.
