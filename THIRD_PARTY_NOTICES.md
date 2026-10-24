# Third-party notices

Modern Music's original code and assets use the MIT License, Copyright (c) 2026 Aquariverr. See [LICENSE](LICENSE), also bundled at `META-INF/LICENSE`. Third-party components retain the licenses below.

## Bundled components

- **JLayer 1.0.1** (`javazoom:jlayer:1.0.1`), copyright JavaZOOM and contributors, is bundled unmodified as a separate nested JAR. Its LGPL 2-or-later notices permit LGPL 2.1; the license is at `META-INF/licenses/JLayer-LGPL-2.1.txt`. The [corresponding source archive](https://repo.maven.apache.org/maven2/javazoom/jlayer/1.0.1/jlayer-1.0.1-sources.jar), with original notices, is also bundled at `META-INF/library-sources/jlayer-1.0.1-sources.jar`. To use a compatible modified version, replace both Gradle `implementation` and `jarJar` coordinates, update its source archive and notices, then rebuild with Java 17. Reverse engineering to debug modifications to this LGPL component is permitted.
- **ZXing Core 3.5.3** (`com.google.zxing:core:3.5.3`) encodes QR codes locally and is bundled unmodified as a separate nested JAR under Apache License 2.0. Its [license](https://github.com/zxing/zxing/blob/zxing-3.5.3/LICENSE) and [notices](https://github.com/zxing/zxing/blob/zxing-3.5.3/NOTICE) are preserved at `META-INF/licenses/ZXing-LICENSE.txt` and `ZXing-NOTICE.txt`.
- **Noto Sans SC**, Copyright 2014–2021 Adobe, Reserved Font Name "Source", uses SIL Open Font License 1.1. The bundled font, "Modern Music UI Sans", is a weight-400 subset with layout tables removed and a new name. [Pinned source](https://github.com/google/fonts/tree/2894aab31764f10f29c421bdfd2340d3b382d384/ofl/notosanssc); source SHA-256: `a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da`. `scripts/build-ui-font.py` reproduces it. The license is at `META-INF/licenses/NotoSansSC-OFL.txt`; the font may be used, modified and redistributed under it, but not sold by itself.

## Protocol and design references

The music adapters are independent Java implementations. No third-party JavaScript API source or API server is bundled.

- NetEase protocol references: [song URLs](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/song_url.js), [account validation](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/login_status.js) and [Web request encryption](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/util/crypto.js).
- NetEase desktop QR login follows [NetMusic-BetterLogin](https://github.com/ming-sc/NetMusic-BetterLogin/tree/4c8ce844c20a7b6df6326a7fa5c5bd864887572d), Copyright (c) 2026 IMG, MIT License. Its license is preserved at `META-INF/licenses/NetMusic-BetterLogin-MIT.txt`.
- QQ protocol references: [QQMusicApi login](https://github.com/l-1124/QQMusicApi/blob/main/qqmusic_api/modules/login.py), [search](https://github.com/l-1124/QQMusicApi/blob/main/qqmusic_api/modules/search.py), [song queries](https://github.com/jsososo/QQMusicApi/blob/master/routes/song.js), [simple-music QQ adapter](https://github.com/Yyyangshenghao/simple-music/blob/master/server/lib/qq-client.ts) and QQ's official login scripts.
- Kugou protocol reference: [KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi), plus Kugou's public Web login and song-page scripts.
