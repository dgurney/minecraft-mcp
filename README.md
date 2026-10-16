# Minecraft MCP

A Fabric client mod that runs an [MCP](https://modelcontextprotocol.io) server inside Minecraft, so an agent can see and control *your* player in a singleplayer world. There is nothing to configure: install the mod, start the game, and point your agent at the server.

- Endpoint: `http://127.0.0.1:25599/mcp` (Streamable HTTP, MCP revision 2026-07-28 only)
- Minecraft 26.3, Fabric Loader 0.19.5 or newer, Java 25. Fabric API is not needed.

## Setup

1. Build with `./gradlew build` and copy `build/libs/minecraft-mcp-<version>.jar` into your instance's `mods` folder.
2. Add the server to your agent, e.g. for Claude Code:

   ```sh
   claude mcp add --transport http minecraft http://127.0.0.1:25599/mcp
   ```

3. Start the game and open a singleplayer world. Commands need cheats (Allow Commands) to be on; everything else works without.

## Tools

| Tool | What it does |
| --- | --- |
| `get_status` | Text summary: open screen, position, facing, targeted block, health, inventory, whether commands are available, recent chat. |
| `get_blocks` | Blocks in a box (up to 4096 positions) as the client has them loaded. |
| `get_surface` | Top-down map of an area (up to 4096 columns, 25×25 around the player by default): the height and kind of each column's top block, skipping plants and leaves. |
| `get_entities` | Entities near the player, nearest first. |
| `screenshot` | The game window as an image, without the HUD unless asked; menus are always shown. |
| `run_command` | Runs a command as the player and returns its output. Fails immediately when cheats are off. |
| `use_block` | Aims at a block by its coordinates and right-clicks it, like a player would; fails with the reason if it's out of reach or hidden. |
| `input` | A sequence of key presses/holds, clicks, cursor moves, scrolling, typing, `look`/`look_at`/`look_at_block` and waits, run one step per game tick. |
| `update_blocks` | Makes the blocks in a box react to their surroundings, which blocks placed by `/setblock` and `/fill` don't do on their own. Needs cheats. |

## Behaviour to know about

- Input goes straight to the game's input handlers, so it works while the game window is in the background. The window must not be minimized for screenshots.
- The game never pauses: not when its window loses focus, and not while a menu is open. The mod is meant for a separate instance the agent plays in, not your own play. Opening the pause menu still saves the world, as it did when it paused the game.
- Keys the agent left held are released once it hasn't used a tool for 5 minutes.
- Commands the agent runs are shown in chat as `[MCP] /command`, followed by their output.
- Only one game can run the server at a time; a second instance fails to start with a message saying the port is taken.

## Development

`./gradlew runClient` starts a development client with an offline account. Add `--args="--quickPlaySingleplayer '<world folder>'"` to go straight into a world.
