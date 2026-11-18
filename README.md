# IdCards

An SCP-style ID card system for Paper 26.3: a card bound to a player, a full-screen card view drawn by a text
shader, presenting the card with F, placeable card scanners and scanner computers.

- Minecraft Java 26.3, Paper 26.3 (`paper-api:26.3.build.253-stable`), Java 25, Gradle (Kotlin DSL), `paper-plugin.yml`
- LuckPerms optional (prefix on the card); without it `luckperms.fallback-prefix` is shown
- The resource pack draws the card, the models and the sounds. It is offered, never required, and the plugin
  never checks whether a client has it; players without it can switch to a text card with `/idcard plain`.

Version 1.0 is a rewrite. IdCards 0.x (paper card + hologram) is in the git history; see "Upgrading from 0.x".

## Changes in 1.0.1

- **Left click no longer kicks or crashes.** In 1.0.0 the camera stood free inside the player's own hit box, so a
  left click made the client attack itself, which vanilla servers answer with a kick. The camera now rides the
  player (see "How freezing the player works").
- **Scanners and computers can be placed.** 1.0.0 refused every placement by hand (it read the click's result after
  cancelling it).
- **The pack is not required and not detected.** Nothing is blocked for players without it. `/idcard plain` is the
  text card for them.
- **F no longer shows a map.** The holder sees the card held out in the middle of their view; others see both arms
  raised with the card, as before.

## What was checked, and how

Everything marked ✅ was looked at on a real 26.3 client (two offline-mode clients against a local Paper 26.3
server, screenshots). The test clients cannot press keys or click. ⚙ means the Bukkit event a click or key causes
was fired by a test command (`/idcard test click|leftclick|f`) and the result was looked at: the plugin's handling
ran for real, the physical input did not. ☐ means not exercised at all.

| | |
|---|---|
| ✅ | Pack loads, shader compiles on the client (stand-alone and merged with Radio + SuperbVehicles + CCTV) |
| ✅ | Full-screen card: background, face, name, prefix, serial, barcode, hint in the corner, camera lock engaged |
| ✅ | Presenting: first person card in the middle of the view; third person both arms raised, card readable |
| ✅ | Inspect view on a second client looking at the presenter |
| ✅ | Scanner (wall and floor) and computer models, facing, light states, swipe animation, screen texts and face |
| ✅ | Scan granted / denied (replaced card), log dialog, blocks + links + log surviving a restart |
| ⚙ | Placing a scanner by right-click, closing the card by left click, F to present and to stop |
| ☐ | **A real left click while the card is open.** The kick was found and fixed by reading the client and server code (the client's target rule, the server's kick condition); it could not be reproduced or re-tested here |
| ☐ | Right-click to open, scrolling while frozen, breaking (survival hits, creative), protection plugins, pistons / explosions / water |
| ☐ | Link Tool clicks, log dialog page buttons, sounds (generated; nobody has listened to them) |
| ☐ | LuckPerms prefixes and change events (not installed on the test server), real skins (offline accounts) |
| ☐ | The seat-and-rotation fallback freeze, `/idcard plain`, hosting behind a proxy |

Go through "Manual testing checklist" at the end before relying on it.

## Findings for 26.3 (verified in the 26.3 client and server jars)

- Pack format 97. Shaders use `#include <ns:file>`, every varying needs `layout(location = N)`, shaders are
  compiled through SPIR-V (`gl_VertexIndex`, not `gl_VertexID`). **One core shader that fails to compile makes the
  client drop every server pack.** Validate with glslang before shipping (`tools/validate_shaders.py`).
- `rendertype_text` no longer exists. All text (GUI, signs, name tags, text displays, maps) is drawn by
  `core/text.vsh/.fsh`; the pipeline variants differ by defines (`IS_GUI`, `IS_SEE_THROUGH`, `IS_GRAYSCALE`).
- The player-head text object (`{"object":"player"}`, Adventure `ObjectContents.playerHead`) is an 8x8 glyph drawn
  by the same text shader with the skin as its texture, and the text colour reaches it. So the shader can move and
  enlarge it like any glyph: that is the photo. With the `textures` property inside the component the client needs
  no lookup, so it works for offline owners; with only a UUID the client resolves the skin itself and shows the
  default skin until it arrives.
- Boss bar names are drawn through `GUI_TEXT`, centred; the shader ignores the vanilla position.
- Third-person arm poses are tied to item TYPES, not components. The only idle pose with both arms raised is a
  charged crossbow (`is(CROSSBOW)` + non-empty `charged_projectiles`); `item_model` does not affect the pose.
- First person: a charged crossbow is drawn in the middle of the view; an item with a `map_id` component is drawn
  as a sheet in both of the player's own skinned arms (the only thing that shows the real arms; 1.0.0 used it, the
  team preferred no map).
- The client aims clicks from the camera entity. It skips entities that share a vehicle with the camera, EXCEPT one
  whose box contains the ray's start; the server kicks a client that attacks its own player.
- A client whose camera is on another entity (set-camera packet, any game mode) ignores movement keys, sends no
  position or rotation, does not draw its own body or hotbar, but still sends clicks, F, Q, number keys and scroll.
- An equipment packet about the player's own entity changes what their client shows in that hand.
- Item displays show models turned 180° about Y; transformation changes interpolate when `start_interpolation` is
  sent with them.

## Design decisions and trade-offs

| Problem | Chosen | Why, and what it costs |
|---|---|---|
| Camera lock | Set-camera packet onto an invisible item_display riding the player (`nms/CameraBridge`, 3 reflective calls) | The only method with no jitter: the client itself stops moving and looking. Costs: one packet outside the API (isolated, falls back when unavailable); the hotbar disappears while the card is open; the view rises 0.2 blocks; the hand item stays visible and tilts slightly with the mouse. Rejected: spectator mode (changes the player for everyone), per-tick rotation reset (shakes), attributes + seat alone (cannot stop looking). |
| Arm animation | Equipment packets: every client in range, the holder's included, is told the hand holds a charged crossbow with the card model | Both arms raised in third person, the card in the middle of the view in first person, with **no change to the real item**. Costs: the pose is vanilla's crossbow pose, not a custom one (a server cannot play custom player animations); the holder's own arms are not drawn in first person; a resync of the hand slot shows the plain card until the next F. |
| Player head | Player-head text object with cached `textures` | Real skin, crisp at any size, works offline, no server-side image work. |
| HUD channel | Boss bar (name = the whole card) | The only channel other plugins cannot overwrite. Costs: the pack blanks the BLUE boss bar graphic (same as the Radio plugin); other plugins' blue bars lose their bar for pack users. |
| Custom blocks | Database row + non-persistent item_display + Interaction + (computer) barrier | No orphans or duplicates by construction; survives restarts through the database, not through saved entities. Costs: breaking is "hit N times", not a mining bar; computers are blast- and piston-proof because barriers are. |

## Setup

1. `./gradlew build` (JDK 25+). Outputs `build/libs/idcards-1.0.0.jar` and the stand-alone pack
   `build/pack/idcards-pack.zip` with its SHA-1 in `idcards-pack.zip.sha1` (also printed).
2. Put the jar into `plugins/`. Start the server. Delete any older IdCards jar: Paper loads only one.
3. `/idcard give <player>`, `/idcard scanner give`, `/idcard computer give`, `/idcard linktool give`.

Regenerating the art: `python3 tools/gen_pack.py` (standard library; sounds need ffmpeg). It writes the textures,
font, models, item definitions, sounds and the layout tables for both the shader and the plugin.

## Hosting the pack

The plugin builds `plugins/IdCards/idcards-pack.zip` at every start (pack sources from the jar + your `ui.*` /
`inspect.*` settings + the merged text shader) and sends it as an additional pack, so your server pack stays.

| `pack.delivery` | |
|---|---|
| `auto` (default) | `same-port`, or `http` if the Minecraft port cannot be hooked |
| `same-port` | Served on the Minecraft port itself. Nothing to open. Does not work behind Velocity/BungeeCord. |
| `http` | Built-in HTTP server on `pack.http.port`; that port must be reachable by players. |
| `url` | You upload `plugins/IdCards/idcards-pack.zip` somewhere; set `pack.url` (and optionally `pack.sha1`). Upload again whenever the plugin logs a new SHA-1. |
| `none` | You merged the pack into your own; nothing is sent, every player counts as having it. |

The pack is optional by default (`pack.required: false`) and the plugin never checks whether a client loaded it:
every feature runs the same either way. A player without the pack sees rows of boxes instead of the card, paper
instead of the items and missing-model cubes instead of the blocks; `/idcard plain` gives them the card as a line
of ordinary text. Nothing can make the models appear without the pack.

### Sharing the text shader

Minecraft runs one `core/text` shader: the one in the topmost pack. When Radio, SuperbVehicles or CCTV are
installed, IdCards starts from their merged shader (read from their pack zips, paths in `pack.text-shader.*`) and
adds its two marked blocks; it re-merges when one of those zips changes. If a merge is impossible it ships its own
shader and logs which plugin's HUD is affected.

**Radio:** use Radio 0.2.2 or newer. Radio re-sends its pack when another pack loads after it, to stay on top;
from 0.2.2 it leaves the IdCards pack alone, because IdCards' merged shader already contains Radio's hooks. With an
older Radio both plugins re-send in turn and a joining player gets several pack reloads. Checked on a client with
Radio 0.2.2: two pack loads on join (Radio, then IdCards), radio popup and card drawn together.

## Commands and permissions

| Command | Permission (default) |
|---|---|
| `/idcard give [player] [owner]` | `idcard.give` (op) |
| `/idcard get` (needs `card.self-service.enabled`) | `idcard.self` (everyone) |
| `/idcard plain` (text card on/off, per player) | `idcard.use` (everyone) |
| `/idcard close [player]` | `idcard.close` (everyone), `idcard.close.others` (op) |
| `/idcard reload` | `idcard.reload` (op) |
| `/idcard scanner give [player]` | `idcard.scanner.give` (op) |
| `/idcard computer give [player]` | `idcard.computer.give` (op) |
| `/idcard linktool give [player]` | `idcard.linktool.give` (op) |
| `/idcard test open\|present\|place\|scan\|link\|log\|list\|click\|leftclick\|f` | `idcard.admin` (op) |

Other permissions: `idcard.use` (view and scan, everyone), `idcard.present` (everyone), `idcard.scanner.place`,
`idcard.scanner.break`, `idcard.computer.place`, `idcard.computer.break`, `idcard.computer.log`, `idcard.link`
(all op). `idcard.admin` includes everything.

`/idcard test` runs the code behind each click as a command, for consoles and test rigs:
`test place <scanner|computer> <x y z> <north|east|south|west> [floor|wall]`, `test scan <player> <scanner id>`,
`test link <scanner id> <computer id>`, `test log <player> <computer id>`, `test list`.

## The card

A paper item with the pack's card model. Its persistent data holds the owner's UUID, a card id, the issue time and
an HMAC over the three made with `plugins/IdCards/secret.key`. Name and lore are decoration. The name on the card
is looked up from the UUID every time (online player, then the plugin's profile cache, then Mojang), so name
changes show up. A card with hand-written data (creative mode can write any item data) reads as FORGED; with
`card.one-per-player` an older card of the same owner reads as REVOKED. **Keep `secret.key`**: without it every
issued card becomes invalid.

Cards cannot be placed, crafted with, smelted, or put into any workstation; `card.allow-drop`, `card.on-death` and
`card.allow-containers` control the rest.

## How freezing the player works

Right-click opens the card at once. The server spawns an invisible, unsaved item_display (visible to that player
only) riding the player on a 0.02 block spacer, and two ticks later (the client must know the entity first) sends
the set-camera packet. From then on the client ignores WASD and jump, sends no movement, and the mouse turns
nothing visible. The server refuses everything else while the card is open: item use, block breaking and placing,
inventory clicks, opening containers, hotbar changes, F, Q, pickups. A left click (swing, block hit or entity hit)
closes it, as do death, quitting, teleports, world and game mode changes, damage (`ui.close-on-damage`), plugin
disable and `/idcard close`. On close the camera is given back and the player is teleported to where they stood,
because their client kept simulating its hidden body.

Why the camera rides the player: clicks are aimed from the camera, and a camera standing free at the player's eyes
is inside the player's own hit box, so a left click attacks the player itself and the server kicks for it. The
client never aims at something that shares a vehicle with the camera, unless the ray starts inside its box; riding
the player on a spacer satisfies both.

Failure modes:
- During the first 2 ticks only position changes are cancelled; a fast mouse flick in that moment turns the view.
- Opened in mid-air, the player is also seated on a second invisible entity (vanilla kicks hovering clients that send no movement).
- If a helper entity is removed (`/kill @e`), the card closes on the next tick.
- If the packet class cannot be found (`ui.freeze-method: mount`, or other server software), the fallback seats the
  player on the anchor and resets their rotation every tick: it holds, but the view shakes when the mouse moves.
- After a crash nothing needs undoing on the client (camera and anchor do not survive a relog); a marker in the
  player's data makes the plugin tidy up on the next join.

## How presenting works

F with a card in the main hand: the swap is cancelled and the card is held out. F again, another hotbar slot,
dropping, opening or clicking an inventory, death, quit, teleport, world or game mode change, or the card leaving
the hand stops it. `present.cooldown-ticks` rate-limits starts.

- Players within `present.render-distance`, and the presenter's own client, are told (equipment packet) that the
  presenter holds a charged crossbow with the card model: vanilla raises both arms in third person and puts the
  card in the middle of the view in first person. Refreshed every `present.update-interval-ticks`.
- A player within `inspect.range` blocks whose crosshair is on the presenter (box ray test, then a block ray test
  for walls) gets the card on the right side of their HUD until they look away. They are not frozen.
- The real item is never touched. The stand-in item is tagged and is refused and deleted wherever a creative
  client could make them real.

Cannot be done by a plugin: a custom arm animation (one arm reaching out, for example). Vanilla clients only have
the poses their items trigger, and the charged crossbow's is the only idle one with raised arms. A mod or a
custom player model would be needed for anything else.

## How the placeable blocks work

`block/BlockManager` is shared by both block types.

- **Placing:** right-click a block with the item. Scanners go on walls (4 directions) or on a floor (facing the
  placer) and need a solid block behind or below; computers stand anywhere and face the placer. A normal
  `BlockPlaceEvent` is fired first and a cancel is honoured, so protection plugins apply.
- **What exists:** a database row; while the chunk is loaded, an item_display (model), an Interaction (click box),
  for computers a barrier block (collision) and text displays (screen). The entities are never saved.
- **Chunk unload / restart:** entities are removed on unload and created from the database on load.
- **Self-heal:** an entity removed by anything else (`/kill`, clear plugins) is recreated on the next tick; every
  loaded block is re-checked every `blocks.validate-interval-ticks`.
- **Breaking:** creative: one left click. Survival: `blocks.break-hits` left clicks in a row (each within
  `blocks.break-window-ticks`), with particles and a hit sound. A `BlockBreakEvent` is fired and honoured. The item
  drops (not in creative).
- **Losing support:** a scanner whose wall or floor block is broken, blown up, burnt or pushed away, or whose cell
  is filled by a block or by water, drops as an item. A computer whose barrier is removed drops as an item.
  Explosions and pistons do not move or destroy computers.
- **World deleted:** blocks of a world that was deleted and created again under the same name are forgotten at
  start; blocks of worlds that are not loaded are kept.

### Scanner and computer

Scan by right-clicking the scanner with a card in either hand, or by presenting (F) within
`scanner.present-range` while looking at it (one scan per look). A card prop slides through the slot (one
interpolated transformation), the light goes blue, then green or red with a sound, then idle; the scanner ignores
input until `scanner.cooldown-ticks` after that. Granted = the card reads as VALID; anything else is denied.

Linked computers receive: owner UUID, time, scanner id and name, result. The screen shows face, name, prefix, time,
scanner and GRANTED / DENIED for `computer.screen-timeout-seconds`, then the idle screen. Right-click (with
`idcard.computer.log`) opens the paginated log dialog.

Link Tool: right-click a scanner, then a computer. Sneak + right-click either: remove all its links.
`computer.auto-link` links new blocks to counterparts within a radius.

## API

```java
@EventHandler
public void onScan(CardScanEvent e) {          // cancellable; fired before the swipe starts
    if (e.isGranted() && !mayPass(e.getCardOwner(), e.getScannerId())) e.setGranted(false);
}

@EventHandler
public void onPresent(CardPresentEvent e) { }  // cancellable
```

`CardScanEvent`: `getPlayer()` (holder), `getCardOwner()` (null if unreadable), `getScannerId()`,
`getScannerName()`, `getScannerLocation()`, `getReason()` (VALID, MALFORMED, FORGED, REVOKED), `isGranted()`,
`setGranted()`.

## Known limitations

- The card font has capitals, digits, ASCII punctuation and Ä Ö Å Ü É. Other characters print as `?`; lower case
  prints as capitals. Prefix colours are matched to a 32-colour palette on the HUD (the marker colour carries the
  palette index); decorations (bold, italic) are dropped.
- The text shader moves any GUI text whose colour is exactly red 252 and green 184-191. Nothing in vanilla uses
  those; another plugin's text in such a colour (possible inside a gradient) would be moved for pack users.
- A HUD cannot be hidden behind other HUD elements: chat and dialogs draw over or under the card as vanilla orders them.
- With many boss bars above it, vanilla stops drawing bars below a third of the screen height; the card is a boss bar.
- Skins: on offline-mode servers players have no skin data, so faces are the default skin.
- Scan times use the server's clock and `computer.time-zone`.
- Folia is not supported.

## Upgrading from 0.x

1.0 does not issue cards on every join and respawn by default (`card.give-on-join: true` brings joins back), does
not hide name tags by default (`nametags.hide: true`; the old `hide-nametags` key is still read), and old paper
cards are not cards any more. The old `resource-pack/` folder is still in the repository because the combined
server pack build reads it; 1.0 does not use it.

## Manual testing checklist

Card
- [ ] `/idcard give` yourself a card; rename it on an anvil; it still opens and scans as yours
- [ ] Right-click (air, block, either hand): card opens at once, no chat message
- [ ] While open: WASD, jump, mouse, scroll, number keys, F, Q, E do nothing
- [ ] **Left click closes it, looking in every direction (straight down too), without a kick or crash**
- [ ] Closes on damage, death, `/tp`, `/gamemode`, `/idcard close`, `/idcard reload` (stays open), plugin disable
- [ ] Open it while jumping; open it, then kill the server process, rejoin: you can move
- [ ] Card of an offline player (`/idcard give you <name>`): name and face correct
- [ ] Change a LuckPerms prefix while a card is open: it updates
- [ ] Cannot be crafted with, put in a furnace / anvil / grindstone; drop, death and chest rules follow the config
- [ ] Creative: give yourself a copy with edited data; a scanner denies it (forged)

Presenting
- [ ] F holds the card out; F again puts it away; so do scrolling, dropping, opening the inventory
- [ ] You see the card held in the middle of your view. Another player sees both arms raised and the card
- [ ] Another player within 5 blocks looking at you gets the card on their HUD; it goes when they look away
- [ ] Creative: while presenting, open the inventory and click the card: no stand-in item appears

Blocks
- [ ] Place a scanner on each wall side and on the floor, a computer in each direction
- [ ] Protected region: placing and breaking are refused
- [ ] Break in survival (4 hits) and creative (1); the item drops in survival
- [ ] Break the wall behind a scanner; blow it up; push a block into it with a piston; pour water on it
- [ ] `/kill @e[type=item_display]` near them: they come back
- [ ] Restart the server; unload and reload the chunk: one of each, links intact
- [ ] Link Tool: link, link again (already linked), sneak-click to unlink
- [ ] Scan by right-click and by presenting; swipe, light, sounds; computer shows the scan and returns to idle
- [ ] Computer log: entries, page buttons, permission

Pack
- [ ] Decline the pack: nothing is blocked; `/idcard plain` shows the text card
- [ ] Vanilla chat, signs, name tags, maps and other plugins' HUDs look as before
