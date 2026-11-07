# Smart Life 前端与 Nginx

前端源码在 `frontend/`，包含 10 个 HTML 页面、CSS、图片、字体与 JavaScript。Vue 3.5.45、Element Plus 2.14.8 和 Axios 1.21.0 通过本地脚本加载，运行页面不需要 Node.js，也不需要打包。

## 本机启动

先按照根目录 README 启动 MySQL、Redis、RocketMQ 和 Java 后端。后端默认监听 `127.0.0.1:8081`。

安装 Nginx 后，在 **项目根目录 `smart-life/`** 执行以下命令。`-p` 必须指向项目根目录，前端、上传目录和日志路径均相对于这个位置解析。

### Windows / PowerShell

```powershell
cd D:\shixi\smart-life
$nginx = 'C:\tools\nginx\nginx.exe' # 改为本机 Nginx 可执行文件位置
$prefix = (Get-Location).Path.Replace('\', '/') + '/'
& $nginx -p $prefix -c deploy/nginx/nginx.conf -t
Start-Process -FilePath $nginx -ArgumentList @('-p', $prefix, '-c', 'deploy/nginx/nginx.conf') -WindowStyle Hidden
```

停止本项目的 Nginx：

```powershell
& $nginx -p $prefix -c deploy/nginx/nginx.conf -s quit
```

### Linux / macOS

```bash
cd /path/to/smart-life
nginx -p "$PWD/" -c deploy/nginx/nginx.conf -t
nginx -p "$PWD/" -c deploy/nginx/nginx.conf
# 停止：nginx -p "$PWD/" -c deploy/nginx/nginx.conf -s quit
```

浏览器访问：

- 首页：http://127.0.0.1:8080/
- 登录：http://127.0.0.1:8080/login.html

## 页面与接口的关系

- HTML、CSS、JS：Nginx 从 `frontend/` 读取。
- `/api/shop/1`：Nginx 去掉 `/api/` 前缀，转发到后端 `/shop/1`。
- `/imgs/...`：先查找前端自带图片，找不到时到后端默认 `uploads/` 目录查找。
- 如果修改了 `SMARTLIFE_UPLOAD_DIR`，请同步修改 Nginx 中上传图片位置的 `root uploads`。
- 如果修改了后端端口，请同步修改 Nginx 中的 `proxy_pass`。

本仓库只收录前端源码和 Nginx 配置，Nginx 程序由本机自行安装。启动前请确认 `8080` 未被其他服务占用。

## 前端检查

```bash
npm ci --ignore-scripts
npm run check:frontend
```

该命令检查 JavaScript 语法、HTML/CSS 资源引用，并用同版本官方编译器编译全部 Vue 页面模板。npm 依赖仅用于开发检查，不参与页面运行。需要 Node.js 22+；这是开发检查工具，不是前端构建步骤。

CI 另外使用 Nginx 容器检查配置与页面访问。静态页面检查通过不代表数据库、登录、抢购等业务链路已完成集成验证。

前端沿用面向手机页面的布局，可在浏览器设备预览中查看。已保存一次实际页面检查的 [Vue 3 登录页截图](screenshots/login-vue3.png)。

## Vue 3 兼容适配

`js/bootstrap.js` 统一创建 Vue 应用、注册 Element Plus 中文语言包与底部导航；保留原有页面 Options API。只读评分使用单向绑定，命名插槽使用 `template`，标签页读取组件的新事件结构。图标继续使用原 Element UI 图标字体，并保留其许可证。JS/CSS URL 携带版本，避免旧浏览器缓存混用 Vue 2 与 Vue 3。
