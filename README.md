# Minecraft MCP

A Fabric client mod that runs an [MCP](https://modelcontextprotocol.io) server inside Minecraft, so an agent can see and control *your* player in a singleplayer world. There is nothing to configure: install the mod, start the game, and point your agent at the server.

> [!NOTE]
> **Written with AI.** The code and docs were written by an AI coding agent (Claude Code), with me directing it and testing the result in game. I haven't reviewed the code line by line, so treat it accordingly.

- Endpoint: `http://127.0.0.1:25599/mcp` (Streamable HTTP, MCP revision 2026-07-28 only)
- Minecraft 26.3, Fabric Loader 0.19.0 or newer, Java 25. Fabric API is not needed.
- Tested with Minecraft 26.3, Fabric Loader 0.19.0 and 0.20.0, and Java 25.

## Setup

1. Build with `./gradlew build` (needs JDK 25) and copy `build/libs/minecraft-mcp-<version>.jar` into your instance's `mods` folder.
2. Add the server to your agent, e.g. for Claude Code:

   ```sh
   claude mcp add --transport http minecraft http://127.0.0.1:25599/mcp
   ```

3. Start the game and open a singleplayer world. Commands need cheats (Allow Commands) to be on; everything else works without.

## Tools

| Tool | What it does |
| --- | --- |
| `get_status` | Text summary: open screen, position, facing, targeted block, health, inventory, whether commands are available, recent chat. |
| `get_blocks` | Blocks in a box (up to 4096 positions) as the client has them loaded, with which sides redstone components work with and, with cheats, which blocks are powered. |
| `get_surface` | Top-down map of an area (up to 4096 columns, 25×25 around the player by default): the height and kind of each column's top block, skipping plants and leaves. |
| `get_entities` | Entities near the player, nearest first, with their UUIDs. |
| `get_mob_brain` | What a mob's AI is thinking: its target and path, then its brain (activities, running behaviors, memories) or its prioritized goals, marking the running ones. Takes a UUID or entity type, or reads the mob the player is looking at. Needs cheats. |
| `screenshot` | The game window as an image, without the HUD unless asked; menus are always shown. |
| `run_command` | Runs a command, or a batch of up to 256 in one game tick, as the player and returns the output once the game shows the changes. Can also watch a box, like `watch_blocks`, to see what the commands set off. Fails immediately when cheats are off. |
| `use_block` | Aims at a block by its coordinates and right-clicks it, like a player would; fails with the reason if it's out of reach or hidden. Can also watch a box, like `watch_blocks`, to see what the click sets off (needs cheats). |
| `input` | A sequence of key presses/holds, clicks, cursor moves, scrolling, typing, `look`/`look_at`/`look_at_block` and waits, run one step per game tick. |
| `update_blocks` | Makes the blocks in a box react to their surroundings, which blocks placed by `/setblock` and `/fill` don't do on their own. Needs cheats. |
| `watch_blocks` | Records every change to the blocks in a box over a number of ticks, in order and tick by tick, e.g. to see what a circuit does. Needs cheats. |

## Behaviour to know about

- Input goes straight to the game's input handlers, so it works while the game window is in the background. The window must not be minimized for screenshots.
- The game never pauses: not when its window loses focus, and not while a menu is open. The mod is meant for a separate instance the agent plays in, not your own play. Opening the pause menu still saves the world.
- Keys the agent left held are released once it hasn't used a tool for 5 minutes.
- Commands the agent runs are shown in chat as `[MCP] /command`, followed by their output, unless the `sendCommandFeedback` game rule is off.
- Only one game can run the server at a time; a second instance fails to start with a message saying the port is taken.
- There is no authentication. The server only listens on 127.0.0.1 and refuses requests from web pages on other sites, but any program on your machine can drive the game and, with cheats on, run any command.

## Prior art

- [yuniko-software/minecraft-mcp-server](https://github.com/yuniko-software/minecraft-mcp-server) is a Node MCP server that joins a world opened to LAN as a separate bot player through [Mineflayer](https://github.com/PrismarineJS/mineflayer), with a tool per bot action.
- [denfry/mcpfabric](https://github.com/denfry/mcpfabric) is a Fabric and NeoForge mod with 50+ tools for many Minecraft versions, including dedicated servers, reached through a separate Node MCP server and a bearer token.

This one makes different trade-offs:

- **Few, general tools.** Coding agents do well with little more than a shell, so this MCP server gives the agent the game's own general interfaces instead of a tool per action: commands, the keyboard and mouse, and the screen, plus fast read-only views of the world. Anything a player can do is in reach without a tool written for it.
- **Nothing to set up.** One jar in the `mods` folder, without Fabric API, Node, a config file, a token or opening the world to LAN. The agent plays as you, not as a bot that joins.
- **The latest MCP revision.** It is a stateless server for MCP 2026-07-28 over Streamable HTTP, with no sessions to keep alive.

## Development

`./gradlew runClient` starts a development client with an offline account. Add `--args="--quickPlaySingleplayer '<world folder>'"` to go straight into a world.

## License

[MIT](LICENSE)
