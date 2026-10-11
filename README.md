# Modern Music

在 Minecraft 中收听网易云音乐、QQ 音乐、酷狗音乐和本地音频，支持个人播放和多人同步收听。

**1.0.0** · Minecraft **1.21.1** · NeoForge **21.1.1 及以上的 21.1.x 版本** · Java **21**

- 搜索歌曲、导入歌单，管理多个播放队列。
- 播放、暂停、切歌、调节进度和音量，切换循环模式。
- 显示封面、歌词和游戏内 HUD，提供十种外观主题。
- 创建听歌群组，或与 FTB Teams 队友一起听。
- 浏览并播放本机 MP3、WAV，支持拖入文件复制到本机音频库；管理员也可拖入上传到服务器，供个人或群组队列使用。

![Modern Music 播放器](docs/images/ui-player.png)

## 安装

将 `modernmusic-1.0.0.jar` 放入游戏目录的 `mods` 文件夹，进入世界后按 **Ctrl+M** 打开播放器。无需额外音乐 API 服务。

个人播放只需客户端安装。多人同步和共享账号需要服务器与参与玩家安装兼容版本；FTB 队伍收听还需 FTB Teams 及其依赖。歌曲是否可播放取决于音乐平台和账号权限。

## 构建与兼容性验证

默认使用 NeoForge 21.1.1（Minecraft 1.21.1 分支的首个发布版本）编译，兼容范围为 `[21.1.1,21.2)`。可通过 `-Pneo_version` 切换验证版本，构建时不会改变声明的最低兼容版本。

```powershell
./scripts/dev.ps1 build
./scripts/dev.ps1 runSearchSmoke runRoomServerSmoke
./scripts/dev.ps1 build '-Pneo_version=21.1.256'
```

## 文档

[使用指南](docs/USER-GUIDE.md) · [QQ 音乐](docs/QQ-MUSIC.md) · [酷狗音乐](docs/KUGOU-MUSIC.md) · [服务器共享账号](docs/SERVER-SHARED-ACCOUNT.md) · [外观主题](docs/THEMES.md)

本项目采用 [MIT License](LICENSE)，Copyright (c) 2026 Aquariverr。第三方组件见 [第三方声明](THIRD_PARTY_NOTICES.md)。
