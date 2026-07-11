# IdCards — physical identity for RP servers

[![Reviewed by PatchPilots](https://img.shields.io/badge/Reviewed%20by-PatchPilots-8A2BE2)](https://github.com/alavesa/patchpilots)

Nametags are gone. Identity lives on a **physical ID card**: issued to every player
on join and respawn, showing their **LuckPerms rank prefix** and `clearance` meta,
and presented on right-click as a **static two-sided hologram**. Replaces a Skript
prototype whose NBT tagging silently died on 1.21's item components. Part of the SCP
facility family.

## Install

1. `IdCards-x.y.z.jar` → `plugins/`. Paper 1.21.4+, Java 21. **LuckPerms** optional
   but recommended (without it, ranks show as "Personnel").
2. The combined **scp_and_chemistry.zip** resource pack includes the card texture.

## How it works

- **Nametags hidden server-wide** on enable (scoreboard team, nametag visibility
  NEVER; every joiner is added). `/idcard nametags on|off` toggles.
- **Everyone carries a card**: a paper item (deliberately not a book — books eat the
  right-click) with name, colored rank prefix and clearance level in the lore. A
  fresh card is issued on every join and respawn, so ranks never go stale; stale
  copies are replaced, never duplicated.
- **Cards stay on your person**: they can't be dropped and never appear in death
  drops.
- **Right-click with the card**: a static, two-sided hologram appears half a block
  ahead — name, rank, clearance — and folds away after `hologram-seconds` (default
  3) or on re-click.
- `/idcard give [player]` reissues manually (e.g. right after a promotion).

## LuckPerms fields used

- **Rank**: the user's prefix (legacy `&` colors rendered), falling back to the
  primary group name.
- **Clearance**: the `clearance` meta key (`/lp user <name> meta set clearance 3`),
  defaulting to 0.

## Notes

- The card texture hook is `lab_idcard` on paper — one file to reskin
  (`assets/idcards/textures/item/idcard.png`).
- Holograms are tagged and non-persistent; leftovers from crashes are swept on
  enable.
