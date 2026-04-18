# PackSync

PackSync 是一个针对 Minecraft Forge 整合包的自动同步工具。其通过 IModLocator 调整被托管的 Mod 的优先级来实现在不替换原 Mod 文件的情况下部署任意版本的 Mod。

PackSync 不依赖于 Minecraft, 理应支持所有具有 IModLocator 的 FML。 然而，其实现上依赖于 Forge 内部实现，因此可能会有不成功的情况。对于此类情况，请在 issues 中反馈。

# Feature

- 依赖于 Caddy `file_server browse` 实现文件列表爬虫。除 version manifest 需要更新外不需要服务端进行额外配置。
- 支持多服务器镜像，自动重试。支持替换 `.minecraft` 中的任意文件，只需要 caddy 上配置同样的目录布局即可。
- 启动时有 GUI。


# Usage

1. 在服务器上安装 Caddy

示例配置：
```
my-awesome-site {
  root /somewhere
  file_server browse
}
```

接着，将 `/somewhere` 作为游戏根目录摆放需要更新的文件。示例拓扑如下：

```
- somewhere
  - mods
    - packsync-managed
      - extendedae_...jar
  - config
    - ...
  - version.json
```

注意：请将所有需要更新的文件放入 `packsync-managed` 目录下，否则 Mod 不受 PackSync 托管。

在 `/somewhere/version.json` 中写入版本信息:

```json
{
  "version": "any string that is different to client cache",
  "removalFileHashes": {
    "./mods/outdated-mods.jar": "sha256sum in lowercase hex"
  }
}
```

服务器设置完毕。

2. 在客户端安装 PackSync 并且在 `.minecraft` 或游戏根目录下编写配置文件 `packsync.json`。内容如下：

```json
{
  "mirrors": ["https://my-fast-dirrectory-server/modpack/xxx/"]
}
```

注意：路径尾部必须有 `/` 否则 Caddy 不会直接响应目录枚举请求。


3. 启动游戏，观察日志或 GUI 变化。

GUI 在 headless 环境下默认关闭，这使得 PackSync 同时兼容服务器情景。对于有桌面环境的服务器，可以通过 JVM 参数 `-Dpacksync.allowDialogs=false` 关闭 GUI。


-- end --