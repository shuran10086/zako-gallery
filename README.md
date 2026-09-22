# 杂鱼图库 (RandomWallpaper)

一个定时随机壁纸 / 全屏弹图的 Android 小应用。

- **零第三方依赖**：只用 AndroidX 与 Material Components，不引入任何图片加载库、网络库
- **无网络权限**：应用不申请 INTERNET 权限，所有数据仅存本地，无任何上传行为
- **纯 Java**，minSdk 26（Android 8.0）/ targetSdk 33（Android 13）

## 功能特性

- **随机壁纸**：定时从指定图库随机选图设为壁纸，间隔两端可调（1–60 分钟），可选同时设置锁屏壁纸
- **全屏弹图**：定时从指定图库全屏弹出图片，间隔 1–60 分钟、显示时长 3–60 秒，两端均可调；显示结束后才重新随机下一轮倒计时
- **图库管理**：支持多个图库，逐张停用 / 启用图片，只从已启用的图片中随机
- **添加图片**：
  - 内置相册浏览器，直接遍历文件系统
  - 也可从其他应用（相册、微信等）分享图片到本应用导入
- **应用锁**：设置密码后锁定主页，锁定期间主页功能与图库选项全部冻结；验证密码后有 10 秒时间可关闭锁定
- **权限引导**：电池优化白名单、悬浮窗权限、应用详情，实时显示授权状态
- **开机 / 应用更新后**自动恢复之前开启的服务

## 软件使用说明

### 1. 首次启动

首次打开会请求照片 / 媒体权限（Android 14 的"部分访问"同样可用，仅能读到被选中的图片）。
随后建议进入 **设置 → 权限引导**，开启：

- 电池优化白名单（否则后台定时可能被系统杀掉）
- 悬浮窗权限（全屏弹图需要）
- 通知权限（Android 13+，前台服务通知）

### 2. 主页：开壁纸 / 弹图

- 打开 **随机壁纸** 或 **全屏弹图** 开关，服务立即启动
- 拖动双滑杆设定间隔范围（壁纸 1–60 分钟；弹图另有显示时长 3–60 秒）
- 壁纸卡内可勾选"同时设置锁屏壁纸"
- 每张卡下方显示当前使用的图库与已启用图片数量

### 3. 图库与添加图片

- **设置 → 图库管理**：新建 / 重命名 / 删除图库（默认图库不可删除）
- 进入图库 → **添加图片**：
  - 从内置相册浏览器勾选（"最近"为单位时间内的最新图片，下方按文件夹列出全部相册）
  - 或在任意应用中选择图片 → 分享 → 杂鱼图库 → 选择目标图库
- 图库内点击缩略图可 **停用 / 启用**（停用的图片不参与随机），或删除

### 4. 密码锁

- **主页 → 密码**：输入两次相同密码。密码仅允许**英文字母、数字和 `.` `*` `$` `%`** 四种符号
- 设置成功后打开 **锁定主页** 开关：
  - 主页功能全部隐藏，显示"锁定中"
  - 设置页的图库选择与图库管理被禁止打开
  - 开关本身同时锁死，点击会提示"当前锁定中，请解锁后重试"
- 在解锁框输入正确密码后，有 **10 秒**时间可以关闭"锁定主页"开关；超时未操作则重新冻结，需再次输入密码
- 密码以随机 salt + SHA-256 存储，不明文保存

## 构建

环境要求：JDK 17、Android SDK（platform `android-33`、build-tools `30.0.3`）。

```bash
# 命令行
./gradlew assembleRelease

# 或直接用 Android Studio 打开运行
```

- 仓库**不包含签名密钥**。未在项目根目录放置 `keystore.properties` 时，构建产出**未签名** APK，可直接安装运行
- 如需签名：自备密钥库，在根目录创建 `keystore.properties`（内容为 `storeFile` / `storePassword` / `keyAlias` / `keyPassword`），该文件已被 `.gitignore` 忽略

## 技术要点

- 单 Activity + `BottomNavigationView` + Fragment，Material Components 主题
- 图库数据：SharedPreferences 存 JSON 索引，图片文件存应用私有目录 `files/galleries/<id>/`
- 密码：随机 salt + SHA-256（`AppLock.java`）
- 壁纸 / 弹图为两个前台服务，`BootReceiver` 在开机与 `MY_PACKAGE_REPLACED` 后恢复服务
- 相册扫描直接走文件系统（`AlbumScanner.java`），不依赖 MediaStore，可发现 `.nomedia` 隐藏目录

## 目录结构

```
app/src/main/java/com/zako/gallery/
  MainActivity.java            底部导航宿主（主页 / 密码 / 设置三页）
  HomeFragment.java            主页：壁纸与弹图开关、间隔滑杆、图库信息
  PasswordFragment.java        密码页：设置密码、锁定主页开关、解锁倒计时
  SettingsFragment.java        设置页：图库选择、图库管理、权限引导、关于
  AppLock.java                 应用锁：密码哈希、锁定态与授权窗
  GalleryStore.java            图库数据层（JSON 索引 + 私有目录文件）
  GalleryPrefs.java            服务参数与启用状态的统一存取
  GalleryListActivity.java     图库管理列表
  GalleryDetailActivity.java   图库详情（网格、添加图片、停用 / 删除）
  GalleryFolderAdapter.java    图库列表适配器
  GalleryImageAdapter.java     图库网格适配器
  AlbumBrowserActivity.java    添加图片：相册浏览器
  AlbumScanner.java            文件系统相册扫描
  AlbumFolderAdapter.java      相册文件夹列表适配器
  AlbumImageAdapter.java      相册内图片网格适配器
  ShareImportActivity.java     分享导入（SEND / SEND_MULTIPLE）
  PhotoAccess.java             照片权限状态判断
  WallpaperChangeService.java  随机壁纸前台服务
  PopupOverlayService.java     全屏弹图前台服务
  PermissionGuideActivity.java 权限引导
  BootReceiver.java            开机 / 更新后恢复服务
  GridSpacingItemDecoration.java 网格等间距装饰
```

## 作者

- **恕染**（shuran）
- QQ：2556246092
- X（Twitter）：[@0shuran0](https://x.com/0shuran0)

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 开源。
