# WatchNeko

**WatchNeko** 是 [Grim](https://github.com/GrimAnticheat/Grim) 的一个分支，Grim 是自由开源的模拟式
Minecraft 反作弊。本分支在上游 Grim 代码库之外新增了大量检测和检测模块。

> **免责声明** — 本项目包含大量 AI 生成的代码。在生产环境部署前请务必充分审查和测试。

## 分支基础

- **上游项目：** [GrimAnticheat/Grim](https://github.com/GrimAnticheat/Grim)
- **本分支：** [jiuxian1337/WatchNeko](https://github.com/jiuxian1337/WatchNeko)
- **许可证：** GPLv3

## 项目描述

基于 PacketEvents 2.0 的自由仿真反作弊，专为 1.21 设计，同时支持 1.8–1.21。

## 第三方代码引用

本分支从以下反作弊项目中汲取灵感和实现细节：

| 项目              | 来源                                                                                              |
|-------------------|---------------------------------------------------------------------------------------------------|
| Intave            | [intave/intave](https://github.com/intave/intave)                                                 |
| AntiCheatAddition | [Photon-GitHub/AntiCheatAddition](https://github.com/Photon-GitHub/AntiCheatAddition)             |
| Artemis           | [artemisac/artemis-minecraft-anticheat](https://github.com/artemisac/artemis-minecraft-anticheat) |
| FairFight         | [dw1e/FairFight](https://github.com/dw1e/FairFight)                                               |
| Karhu Fixed       | [Dg32z/Karhu-Fixed](https://github.com/Dg32z/Karhu-Fixed)                                         |
| LonAntiCheat      | [Araykal/LonAntiCheat](https://github.com/Araykal/LonAntiCheat)                                   |
| Medusa            | [infiniteSM/Medusa](https://github.com/infiniteSM/Medusa)                                         |
| MX-Project        | [kireikosasha/MX-Project](https://github.com/kireikosasha/MX-Project)                             |
| NoCheatPlus       | [Updated-NoCheatPlus/NoCheatPlus](https://github.com/Updated-NoCheatPlus/NoCheatPlus)             |

## 构建

```bash
./gradlew build
```

要求：

- JDK 21+
- Gradle（包含 wrapper）

## 平台支持

- Paper / Spigot 1.8–1.21
- Folia 调度器支持
- PacketEvents 2.0
- ViaVersion 兼容

## 贡献

此为个人分支，不活跃关注 Issue 和 PR。

## 许可证

GPLv3 — 详见 [LICENSE](LICENSE)。修改后的二进制文件或复制了 Grim 代码的插件必须保持私有，或向接收者免费提供完整源代码。
