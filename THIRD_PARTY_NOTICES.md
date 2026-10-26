# 第三方组件与素材

GPL-3.0-or-later 覆盖本项目自有代码和自制配图，不替代以下组件的许可证。
依赖版本以 `gradle/libs.versions.toml`、Gradle Wrapper 配置和实际解析结果为准。

| 组件 | 使用范围 | 许可证 / 原始来源 |
| --- | --- | --- |
| Gradle Wrapper 8.14.3 | 仓库随附的构建脚本与 wrapper JAR | Apache-2.0；JAR 内保留 `META-INF/LICENSE`，脚本保留原始版权头。[Gradle](https://github.com/gradle/gradle) |
| Kotlin、kotlinx.coroutines、kotlinx.serialization | 语言、协程与序列化 | Apache-2.0；[Kotlin](https://github.com/JetBrains/kotlin)、[coroutines](https://github.com/Kotlin/kotlinx.coroutines)、[serialization](https://github.com/Kotlin/kotlinx.serialization) |
| AndroidX（Compose、TV Material、Lifecycle、Room、Activity、JankStats、ProfileInstaller） | Android 界面、存储与性能 | Apache-2.0；[AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| OkHttp、MockWebServer | 网络与测试 | Apache-2.0；[OkHttp](https://github.com/square/okhttp) |
| OkHttp 内含 Public Suffix List 数据 | 域名后缀匹配 | MPL-2.0；[原始数据](https://publicsuffix.org/list/public_suffix_list.dat)，保留 OkHttp JAR 内的 NOTICE。 |
| Coil | 图片加载 | Apache-2.0；[Coil](https://github.com/coil-kt/coil) |
| pinyin4j 2.5.1（com.belerweb） | 拼音匹配 | 按 [PinyinHelper 源码](https://github.com/belerweb/pinyin4j/blob/master/src/main/java/net/sourceforge/pinyin4j/PinyinHelper.java) 的 **GPL-2.0-or-later** 声明处理；内含 [Sparta](https://github.com/belerweb/pinyin4j/blob/master/src/main/java/com/hp/hpl/sparta/Parser.java) 为 **LGPL-2.1-or-later**，Copyright © 2002 Hewlett-Packard Company。[POM](https://github.com/belerweb/pinyin4j/blob/master/pom.xml) 虽写 BSD，本项目不以此覆盖源码的许可声明。 |
| ZXing core | 二维码 | Apache-2.0；[ZXing](https://github.com/zxing/zxing) |
| JUnit 4 | 单元测试 | EPL-1.0；[原始许可](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) |
| Robolectric | Android JVM 测试 | MIT，部分文件为 Apache-2.0；[原始许可](https://github.com/robolectric/robolectric/blob/master/LICENSE) |

Apache-2.0 全文随仓库保存在 [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)。
GPLv3 全文见根 [LICENSE](LICENSE)；Sparta 的 LGPLv2.1 全文见 [LICENSES/LGPL-2.1.txt](LICENSES/LGPL-2.1.txt)。
上表是直接使用组件的导航，不是 APK 中全部传递依赖的完整许可清单。正式分发二进制前，
应按实际 release 依赖图收集每个组件的 LICENSE / NOTICE，保留必要的版权声明。

GitHub Release 同时提供第三方材料 ZIP：包含实际解析的运行依赖清单、原始 POM、
依赖内嵌的 LICENSE / NOTICE、适用的完整许可文本，以及 pinyin4j（含 Sparta）和
OkHttp 对应版本的源码 JAR。pinyin4j 的源码 JAR 同时包含 Java 源码和拼音资源表；
其源码许可声明优先于 POM 的 BSD 标签。

README 的 PNG 截图由本项目在 Android TV 模拟器中运行产生；演示封面、背景与占位 logo
由 `tools/mock_emby.py` 生成。`docs/images/mark.svg` 按本项目 Android 矢量标志绘制。
仓库不附带影视海报、视频、真实服务器配置、第三方 APK 或反编译源码。

Emby 与 Zidoo 的名称用于说明兼容目标，商标与产品归各自权利人所有。


## 外部影视数据库

TMDB API 与图片：https://www.themoviedb.org 。`tmdb_logo.xml` 将其官方 blue_short SVG 转为 Android VectorDrawable，保留图形、配色和宽高比，仅用于关于页归属标识；不按本项目 GPL 重新许可 TMDB 商标。This product uses the TMDB API but is not endorsed or certified by TMDB. 用户自行提供 Read Access Token；影视数据和图片的权利属于相应权利人，不随本项目源码许可证重新授权。

Bangumi API：https://bgm.tv （API 文档：https://github.com/bangumi/api）。资料由运行时直连获取，不随源码打包。

bangumi-data/bangumi-data：https://github.com/bangumi-data/bangumi-data ，数据许可证 CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）。运行时通过 jsDelivr / unpkg 获取，仅用于原名 / 译名 / Bangumi 入口识别；未捆绑数据副本，也不将其当作逐集映射表。
