# IdCards

An SCP-style ID card system for Paper 26.3: a card bound to a player that shows its owner's face, name and rank
when held, a vanilla spear animation to hold it out for others, placeable card scanners and scanner computers.

- Minecraft Java 26.3, Paper 26.3 (`paper-api:26.3.build.253-stable`), Java 25, Gradle (Kotlin DSL), `paper-plugin.yml`
- LuckPerms optional (prefix on the card); without it `luckperms.fallback-prefix` is shown
- The resource pack draws the card, the models and the sounds. It is offered, never required, and the plugin never
  checks whether a client has it. It only adds files in its own namespace: no shader, no vanilla file overridden.

## Changes in 1.1.0

The card no longer takes over the player. 1.0 opened a full-screen card drawn by a text shader, locked the camera and
froze the player; with the team's other HUD plugins that shader chain broke (the "yellow, shifted UI with the text
cramped at the top"), the camera trick hid the player from themselves in third person, and the freeze made players
slide. All of it is gone:

- **No shader, no camera, no freezing.** Nothing about the player changes. They walk, jump, look around and use
  other items as usual.
- **Holding the card** shows it, with the owner's face, name, prefix, serial and barcode, at the bottom right of the
  holder's view. It follows the camera every frame, the same way the Firearms viewmodel does.
- **Holding the right mouse button** plays the vanilla spear hold-out animation (the card is a spear underneath) and
  shows a card with the owner's details in front of the player to everyone else. Releasing the button takes it back:
  the holder sees their own details again.
- **The spear does no harm:** no damage, knockback, dismounting, durability or slowdown. Hits made while holding a
  card are cancelled.
- Old cards (paper) are turned into the new kind automatically, keeping owner and signature.
- `/idcard close` and `/idcard plain` are gone (nothing to close; nothing to replace).

Seen on two real 26.3 clients with Radio, SuperbVehicles, CCTV, Lighting, Phone and Firearms installed: the held card
(looking straight, down), held out from the front, the side and after turning, the spear pose, the card turning its
back to the holder. Not seen by a person: a real right-click held and released, walking and sprinting with the card
held out, the "no damage" rule against a real hit.

## How holding and showing the card works

- **Holding.** While a card is the main-hand item, the holder's client is told about a few display entities (a thin
  slab with the bare card on it, text displays for the details) that only that client knows. They are billboarded
  to the camera, so they stay at the same place on screen whatever the holder does. The details are written in the
  card font from the pack; the face is the player-head text object, so it is the owner's real skin.
- **Showing.** The card is a wooden spear with the card's model, so holding the right mouse button is a vanilla
  "use" that every client animates (the spear pose). The plugin sees the use and shows everyone near a second group
  of display entities: the card, front out, with the details, in front of the player's hands, turned with their
  body. The holder's own card turns its back to them and moves out while it is shown.
- **Attached on the client.** Both groups are attached to the player as client-side passengers (one passenger packet,
  `nms/RideBridge`), so the client moves them with the player every frame: no trailing, no jitter. On the server they
  are ordinary, unsaved entities; the player is NOT a vehicle there, so other plugins can still teleport them.
  Without the packet (other server software) the entities are teleported every tick instead.
- The third-person card in the hand is hidden while it is held out (the shown card takes its place), and the
  first-person hand model is empty (the holder's card is the entities).

Cannot be done by a plugin: an arm animation that is not one of vanilla's. The spear's hold-out pose is the closest.

## Commands and permissions

| Command | Permission (default) |
|---|---|
| `/idcard give [player] [owner]` | `idcard.give` (op) |
| `/idcard get` (needs `card.self-service.enabled`) | `idcard.self` (everyone) |
| `/idcard close [player]` | `idcard.close` (everyone), `idcard.close.others` (op) |
| `/idcard reload` | `idcard.reload` (op) |
| `/idcard scanner give [player]` | `idcard.scanner.give` (op) |
| `/idcard computer give [player]` | `idcard.computer.give` (op) |
| `/idcard linktool give [player]` | `idcard.linktool.give` (op) |
| `/idcard test hold\|place\|scan\|link\|log\|list\|click` | `idcard.admin` (op) |

Other permissions: `idcard.use` (see a held card's details, scan; everyone), `idcard.present` (hold it out; everyone), `idcard.scanner.place`,
`idcard.scanner.break`, `idcard.computer.place`, `idcard.computer.break`, `idcard.computer.log`, `idcard.link`
(all op). `idcard.admin` includes everything.

`/idcard test` runs the code behind clicks as commands, for consoles and test rigs: `test hold <player>` (as if the
right mouse button were held with the card, until given again), `test click <player> <x y z> <face>` (a right-click
on a block), `test place <scanner|computer> <x y z> <north|east|south|west> [floor|wall]`, `test scan <player> <scanner id>`,
`test link <scanner id> <computer id>`, `test log <player> <computer id>`, `test list`.

## The card

A wooden spear with the pack's card model. Its persistent data holds the owner's UUID, a card id, the issue time and
an HMAC over the three made with `plugins/IdCards/secret.key`. Name and lore are decoration. The name on the card is
looked up from the UUID every time (online player, then the plugin's profile cache, then Mojang), so name changes
show up. A card with hand-written data (creative mode can write any item data) reads as FORGED; with
`card.one-per-player` an older card of the same owner reads as REVOKED. **Keep `secret.key`**: without it every
issued card becomes invalid.

Cards cannot be crafted with, smelted, or put into any workstation; `card.allow-drop`, `card.on-death` and
`card.allow-containers` control the rest.

## Hosting the pack

The plugin builds `plugins/IdCards/idcards-pack.zip` at every start and sends it as an additional pack, so your
server pack stays. Without it, cards still work: the held card shows boxes instead of letters and the items and
blocks show missing-model shapes.

| `pack.delivery` | |
|---|---|
| `auto` (default) | `same-port`, or `http` if the Minecraft port cannot be hooked |
| `same-port` | Served on the Minecraft port itself. Nothing to open. Does not work behind Velocity/BungeeCord. |
| `http` | Built-in HTTP server on `pack.http.port`; that port must be reachable by players. |
| `url` | You upload `plugins/IdCards/idcards-pack.zip` somewhere; set `pack.url` (and optionally `pack.sha1`). |
| `none` | You merged the pack into your own; nothing is sent. |

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
  prints as capitals. Prefix decorations (bold, italic) are dropped; colours are kept.
- Skins: on offline-mode servers players have no skin data, so faces are the default skin.
- The fixed captions on the card (NAME, TITLE, SERIAL, header) are part of its texture, in English.
- About 7 unsaved display entities per player holding a card, 7 more while held out.
- Folia is not supported.

## Upgrading

- From 1.0.x: replace the jar. Paper cards become spear cards when a player joins or holds one. `ui.*`, `inspect.*`
  and `pack.text-shader.*` in config.yml are no longer read. The English lang file is replaced (old copy:
  `lang/en.yml.old`).
- From 0.x: 1.x does not issue cards on every join by default (`card.give-on-join: true`), does not hide name tags by
  default (`nametags.hide: true`), and old 0.x cards are not cards.

## Manual testing checklist

Card
- [ ] Hold a card: your details at the bottom right; walk, sprint, jump, look up and down, turn fast: it stays put
- [ ] Hold right-click: spear pose; another player sees the card with your details in front of you; release: your
      details come back
- [ ] Walk, sprint and turn while holding it out: the shown card stays in front of you for the other player
- [ ] Hit a player and a mob with the card, also while holding it out: no damage, no knockback
- [ ] Teleport (/tp, an elevator, a door plugin) while holding a card: works
- [ ] Third person (F5) while holding and showing: you are visible, the card is drawn
- [ ] An old paper card turns into the new card when held, and still scans as yours
- [ ] Cannot be crafted with, put in a furnace / anvil / grindstone; drop, death and chest rules follow the config

Blocks
- [ ] Place a scanner on each wall side and on the floor, a computer in each direction
- [ ] Protected region: placing and breaking are refused
- [ ] Break in survival (4 hits) and creative (1); the item drops in survival
- [ ] Right-click a scanner with the card (no spear pose starts), and hold the card out while looking at it
- [ ] Computer shows the scan and returns to idle; log dialog, page buttons, permission
- [ ] Link Tool: link, link again (already linked), sneak-click to unlink
- [ ] Restart the server; unload and reload the chunk: one of each, links intact
