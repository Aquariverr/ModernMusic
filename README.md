# Modern Music

简体中文 | [English](README.en.md)

本分支为 **Minecraft 1.20.1 / Forge / Java 17** 移植版，源自 [Modern Music 1.21.1 NeoForge 版本](https://github.com/Aquariverr/ModernMusic/tree/1.21.1-neoforge)。

在 Minecraft 中收听网易云音乐、QQ 音乐、酷狗音乐和本地音频，支持个人播放和多人同步收听。

- 搜索歌曲、导入歌单，管理多个播放队列。
- 播放、暂停、切歌、调节进度和音量，切换循环模式。
- 显示封面、歌词和游戏内 HUD，提供十种外观主题。
- 直接加入服务器公共群组、创建听歌群组，或与 FTB Teams 队友一起听；公共群组管理员可任命 DJ 和授予点歌权限。
- 浏览并播放本机 MP3、WAV，支持拖入文件复制到本机音频库；管理员也可拖入上传到服务器，供个人或群组队列使用。
- 客户端需装，服务端可选。连接未安装的服务器无法使用群组和FTB Teams相关功能。

![Modern Music 播放器](docs/images/ui-player.png)

## 安装

将 `modernmusic-1.20.1-1.0.0.jar` 放入游戏目录的 `mods` 文件夹，进入世界后按 **Ctrl+M** 打开播放器。无需额外音乐 API 服务。

构建生成的安装文件为 `build/libs/modernmusic-1.20.1-1.0.0.jar`，已内置 MP3 解码和二维码依赖；不要安装 `-slim.jar` 或 `-sources.jar`。使用 Minecraft 1.20.1、Forge 47.2 或更高的 47.x 版本与 Java 17。开发与启动验证使用 Forge 47.4.22。

个人播放只需客户端安装。多人同步和共享账号需要服务器与参与玩家安装兼容版本；FTB 队伍收听还需 FTB Teams 及其依赖。歌曲是否可播放取决于音乐平台和账号权限。

本次修复更新了群组通信协议，服务器和参与群组的客户端需同步更新。公共群组最多同时加入 1000 人，此上限是协议边界，并非实测在线容量；无管理员或 DJ 留在房间时，收听者会自动补报当前歌曲的未知时长，使队列继续播放。

## 构建

安装 JDK 17 后运行 `./gradlew build`；Windows 可运行 `powershell -ExecutionPolicy Bypass -File scripts/dev.ps1 build` 自动查找本机 JDK 17。`runClient` 和 `runServer` 使用独立的 `runs/` 开发目录。

`build` 执行单元测试、Forge 重混淆和发布包检查。启动验证任务包括 `runRoomServerSmoke`、`runFontSmoke`、`runUiGallery`、`runSearchSmoke`；测试模组不会打入发布包。

本移植版的网络协议仅与同版本 Forge 移植版互通。客户端单独安装仍支持个人播放；群组同步、服务器音频及共享账号需要服务端也安装此 Forge 版本。使用服务器公共群组时，请同时更新服务端和参与玩家的客户端。

## 使用文档

[使用指南](docs/USER-GUIDE.md) · [QQ 音乐](docs/QQ-MUSIC.md) · [酷狗音乐](docs/KUGOU-MUSIC.md) · [服务器共享账号](docs/SERVER-SHARED-ACCOUNT.md) · [外观主题](docs/THEMES.md)

本项目采用 [MIT License](LICENSE)，Copyright (c) 2026 Aquariverr。第三方组件见 [第三方声明](THIRD_PARTY_NOTICES.md)。
