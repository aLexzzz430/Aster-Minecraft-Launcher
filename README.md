# AsterRPG 启动器

AsterRPG 的 Windows 客户端启动器源码。启动器使用 Java 21 和 Swing，安装入口使用 NSIS。玩家先在账号页登录或注册，成功后进入启动页查看游戏版本、官网公告、服务器状态、更新、安装位置与设置，再启动游戏。

## 代码范围

- `src/main/java`：账号认证、游戏启动、热更新、资源包管理、设置、两页界面。
- `src/main/resources`：启动器界面素材、性能预设和账号服务的公开 TLS 证书。`ui/OFL.txt` 是随附字体的许可。
- `src/test/java`：启动与更新逻辑、资源包、界面流程的本地测试。
- `installer`：Windows 启动入口及完整客户端安装器的 NSIS 源码。
- `scripts/build.py`：独立编译、测试与导出安装器图标。

本仓库不包含 Minecraft 游戏文件、模组、地图、玩家数据、服务端代码、私钥或已打包的客户端。完整安装器需要另行提供有权分发的游戏载荷。官网公告接口也部署在官网项目中，本仓库只包含读取它的客户端代码。

## 构建与测试

需要 Java 21 JDK 和 Python 3。设置 `JAVA_HOME` 后运行：

```bash
python3 scripts/build.py --test
```

脚本从 Maven Central 获取 Gson 2.13.2、JNA 5.17.0、JNA Platform 5.17.0 和可选远景种子功能使用的 SQLite JDBC 3.50.3.0，放入忽略提交的 `vendor/` 目录。编译结果在 `build/aster-auth-launcher.jar`，图标在 `build/brand/`，界面测试截图在 `build/ui/`。Windows 完整客户端运行时还需要相应的运行环境、游戏载荷以及 `client-manifest.json`。

安装了 NSIS 后，可单独编译 Windows 启动入口：

```bash
python3 scripts/build.py --bootstrap
```

这会生成 `build/bootstrap/AsterRPG.exe`。完整客户端安装器使用 `installer/single-file-client.nsi`，需要外部暂存的 `app` 载荷、发布版本号，以及上述启动入口与品牌图标。例如：

```bash
makensis -DOUTPUT=build/AsterRPG.exe -DPAYLOAD=/path/to/app -DRELEASE=2026.9.28.2 -DBOOTSTRAP=build/bootstrap -DBRAND=build/brand installer/single-file-client.nsi
```

## 运行时接口

账号页调用 AsterRPG 的 HTTPS 账号接口，取得一次性入服凭证和独立更新令牌；两者只保留在本次启动器内存中。成功登录后，启动页才会检查更新；版本清单和文件下载都通过服务端校验更新令牌。断点续传复用同一下载会话。服务端按游戏名与目标版本统计实际开始传输的会话，执行可配置的重复下载限速；新版本重新计数。官网公告来自公开接口 `/api/launcher/news/`，在线状态来自 Minecraft 服务器列表协议，登录凭据不发送给官网公告接口。

旧版启动器没有更新令牌，需从官网下载 `2026.9.28.3` 或更新的完整安装器一次，之后可使用登录后增量更新。完整安装器仍公开可下载；限速规则由服务端执行，不依赖玩家本机自律。

登录后的启动页可上传 64×64 或旧版 64×32 PNG 皮肤，并选择经典或纤细手臂模型。启动器使用当前账号的更新令牌调用 `/v1/skin`，等待服务端生成签名纹理；完成后显示皮肤头像。签名纹理由主服保存并供主服与星塔工作服应用，启动器不直接修改本地游戏皮肤文件。

该仓库保存源码，不代表 GitHub 上的提交已经完成 Windows 安装、真人登录入服或正式服发布验收。
