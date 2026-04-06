# PackSync

PackSync is a custom Forge mod locator designed to automatically synchronize your Minecraft modpack files (mods, configs, assets) with a remote Caddy file server before the game even finishes loading. 

This ensures players always have the correct, up-to-date files every time they launch the game.

Currently only Minecraft Forge 1.19.2 is tested. Other versions may work as well.

## Features

- **Early-Boot Synchronization**: Runs during Forge's earliest mod discovery phase, syncing files before Forge loads them.
- **Smart Updates**: Only downloads files if they are missing locally, have a different file size, or if the server's modification time is strictly newer.
- **Mirror Support (Failover)**: Specify multiple server URLs. If one fails, PackSync automatically tries the next.
- **Safe Downloads**: Files are downloaded to a `.download` temp file and atomically moved only when complete, preventing corrupted jar files on connection loss.
- **Automated Removals**: Deletes obsolete files securely using SHA-256 hash matching via a `.removal` manifest.
- **GUI Feedback**: Displays progress and issues directly on the Forge loading screen and triggers alert dialogs if failures occur so players know exactly what's happening.

## Setup & Configuration

Upon first launch, PackSync creates a config file in your Minecraft directory at `packsync.properties`. 

Edit this file to point to your update server(s).

### packsync.properties Example

```properties
# Set the URL(s) of your Caddy file server root
# Multiple mirrors can be comma-separated; failover is automatic
remote.url=https://primary.example.com/modpack/,https://mirror1.example.com/modpack/

# Set to false to disable syncing completely
enabled=true
```

## Server Requirements

PackSync is designed to work seamlessly with a **Caddy** web server hosting your files.

1. **JSON API**: Caddy natively supports returning JSON directory listings when the `Accept: application/json` header is sent. PackSync relies on this.
2. **`.revision` file**: Place a `.revision` file in the root of your web server (e.g., alongside the `mods/` folder). This file should contain a single string (like a commit hash or version number). PackSync checks this file first; if it hasn't changed since the last successful sync, the download phase is entirely skipped for faster startup.
3. **`.removal` file (optional)**: To delete files from players' clients, provide a `.removal` file at the server root. Each line should be formatted as: `<relative_path> <sha256_hash>`. 
    - *Example*: `mods/old-mod-1.0.jar a1b2c3d4e5f6...`
    - PackSync will only delete the file if the local SHA-256 hash matches the expected hash.

## How it works (The Sync Process)

1. Connects to `remote.url` and fetches `.revision`.
2. Compares the remote revision against the local `.packsync/revision.cache`.
3. If different, crawls the remote Caddy server to build a file tree.
4. Compares local files against the remote tree (size, existence, mod time) and downloads updates atomically.
5. Processes `.removal` to delete obsolete files securely.
6. Presents the final `.jar` files in `.packsync/managed-mods` to Forge for loading.
7. Saves the new `.revision` locally only if there were zero errors during the sync. If errors occur, the user is prompted to proceed anyway, but the revision cache isn't updated, forcing a retry on the next launch.
