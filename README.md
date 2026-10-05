<p align="center">
  <img src="docs/icon.webp" width="112" alt="AiCode" />
  <h1 align="center">AiCode</h1>
  <p align="center">
    Android 上的通用 AI Coding Agent · Linux 开发环境 · 支持本地与远程 SSH
    <br />
    <a href="README.md">中文</a> · <a href="README.en.md">English</a>
  </p>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android Platform" />
  <a href="https://github.com/jieapi/aicode/releases"><img src="https://img.shields.io/github/v/release/jieapi/aicode?display_name=tag&include_prereleases" alt="Latest Release" /></a>
</p>

<p align="center">
  <table>
    <tr>
      <td align="center"><img src="docs/screenshots/home.png" alt="AiCode 主页 - AI 对话界面，支持代码生成与 Markdown 渲染" width="270"/></td>
      <td align="center"><img src="docs/screenshots/git.png" alt="AiCode Git 集成 - 可视化提交记录与分支管理" width="270"/></td>
    </tr>
    <tr>
      <td align="center">主页 · AI 对话</td>
      <td align="center">Git · 提交历史</td>
    </tr>
    <tr>
      <td align="center"><img src="docs/screenshots/container.png" alt="AiCode 容器设置 - 容器镜像管理" width="270"/></td>
      <td align="center"><img src="docs/screenshots/models.png" alt="AiCode 模型列表 - 多供应商模型管理" width="270"/></td>
    </tr>
    <tr>
      <td align="center">容器 · 镜像管理</td>
      <td align="center">模型 · 列表配置</td>
    </tr>
  </table>
</p>

---

## 简介

AiCode 是运行在 Android 上的通用 AI Coding Agent，把一套完整的 Linux 开发环境装进手机：内置 Alpine Linux 容器与终端，AI Agent 能读写文件、执行 Shell 命令、运行构建工具，写代码、调试到构建都在手机本地完成；也可改用远程 SSH 服务器作为执行后端，把手机变成远程项目的移动工作站。

上手无需任何准备：装上 App、在「AI 供应商」配好模型即可直接开发，不用电脑，也不用自己搭建环境。AiCode 不内置模型、不绑定供应商，支持 OpenAI / Anthropic / Gemini 三类协议与自定义供应商，模型、密钥与端点都由你自行配置。

## 赞助商

| 图标 | 描述 |
|------|------|
| <img src="https://www.rainyun.com/favicon.ico" width="24" alt="RainYun" /> | **[雨云](https://www.rainyun.com/logins_)** — 本项目服务器赞助商，国产云服务商，主营云服务器与游戏云（Minecraft 等预装服务端一键开服），兼有裸金属物理机与对象存储，新用户优惠 |
| <img src="https://ai.onyxaxis.org/api/site/logo" width="24" alt="Axis AI" /> | **[Axis AI](https://ai.onyxaxis.org/register?invite=AICODE)** — 汇聚前沿大模型能力的免费公益 AI 平台，使用邀请码 `AICODE` 注册可享 7 天 Go 权益 |

## 广告

| 图标 | 描述 |
|------|------|
| <img src="https://opencode.ai/favicon-96x96-v3.png" width="24" alt="OpenCode" /> | **[OpenCode Go](https://opencode.ai/go?ref=8Q5GA5B1NY)** — 低价订阅，提供最强大开源模型的慷慨额度与可靠访问 |
| <img src="https://www.qiniu.com/favicon.ico" width="24" alt="Qiniu" /> | **[七牛云 AI](https://s.qiniu.com/vUryau)** — 新用户注册赠 300 万 Token（2 年有效），支持 50+ 热门大模型 |

## 功能特性

### AI Agent

- **AI Agent** — 内置文件读写与编辑、Shell 执行、后台终端、代码与网页搜索、图片识别、待办清单等工具；流式输出并实时渲染 Markdown，长对话自动压缩上下文
- **子代理并行** — 主会话可派生独立上下文的子代理在后台并行调研、审查或对比方案，不阻塞当前对话；内置只读的 Explore 子代理，也可自定义模型、工具集与提示词
- **三种运行模式** — BUILD 正常开发、PLAN 只读规划（工具层拦截写操作）、AUTO 免授权全放行，按信任程度切换 AI 权限
- **检查点与撤销** — Agent 改代码前自动记录文件快照，对话中可一键回滚代码、对话或两者
- **技能与自动记忆** — 支持全局/项目级技能（Skills）与长期记忆，AI 可跨会话复用经验与项目约定
- **MCP 协议** — 支持连接本地（stdio）与远程（HTTP）MCP 服务器，动态扩展 AI 工具能力
- **工具授权与自定义提示词** — 逐工具配置授权规则，系统提示词支持用户覆盖且 App 升级不丢失

### 模型与供应商

- **模型无关** — 不内置模型、不绑定供应商，模型、密钥与端点自行配置
- **协议兼容** — 兼容 OpenAI / Anthropic / Gemini 三类协议，内置多家官方预设，也支持自定义供应商，模型列表与单价可自定义
- **多 Key 轮换** — 同一供应商可配多个 Key，按顺序或轮询自动轮换，思考强度可调

### 开发环境

- **内置终端与容器** — 基于 Termux 与 PRoot 的本地 Linux 容器，内置 Alpine 镜像，支持导入自定义 rootfs、挂载宿主目录；终端多标签、可后台常驻
- **远程 SSH 模式** — 把远程服务器作为执行后端，命令、文件与终端都作用于远端项目
- **文件树与代码编辑器** — 缩进式文件树点开即进全屏编辑器，支持主流语言语法高亮与 Markdown 预览；AI 回复里的 `文件:行号` 链接可直接跳转到对应行，本地与远程 SSH 工作区都支持
- **Git 集成** — 可视化管理状态、分支、提交历史、差异与标签，支持暂存与回退改动、署名与凭据配置
- **工作区同步** — 支持 SFTP / FTP 同步，内置 FTP 服务端方便电脑端管理文件

### 使用体验

- **平板与大屏适配** — 按窗口宽度自适应：宽屏常驻侧边栏，聊天旁并排显示代码或终端；变窄时退回单栏
- **Token 统计** — 按渠道与模型统计用量、估算费用，可下钻查看调用明细
- **外观与语言** — 主题明暗、预设配色、莫奈取色、自定义背景图，中英双语界面
- **网络代理** — 支持全局代理与供应商级代理分别配置
- **备份与还原** — 加密导出/导入供应商配置、凭据、聊天历史与工作区文件

## 快速开始

| 项目 | 说明 |
|------|------|
| 系统要求 | Android 8.0+（API 26），arm64-v8a / x86_64 |
| 下载地址 | [GitHub Releases](https://github.com/jieapi/aicode/releases/latest)：真机选 `armsolo`、模拟器选 `x86solo`、通用选 `universal` 包 |
| 快速上手 | 「设置 → AI 供应商」配模型 →「容器与镜像」选本地或 SSH → 新建会话开始对话 |
| 更新记录 | [Releases](https://github.com/jieapi/aicode/releases)（历史版本与更新说明） |
| 使用指南 | [在线文档](https://aicode.murk.top)：快速上手、使用手册与进阶教程（与 App 内置文档同源） |

## Star

如果 AiCode 对你有帮助，欢迎 [Star](https://github.com/jieapi/aicode) 支持，让更多开发者看到这个项目。

## Star History

<a href="https://www.star-history.com/?repos=jieapi%2Faicode&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=jieapi/aicode&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=jieapi/aicode&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=jieapi/aicode&type=date&legend=top-left" />
 </picture>
</a>

## 反馈与贡献

- **交流群**：加入 [AiCode QQ 交流群](https://qm.qq.com/q/ByvqODJdIs)（群号：1107110698），交流使用心得、反馈问题
- **Bug 反馈**：到 [Issues](https://github.com/jieapi/aicode/issues) 提交，附上复现步骤、设备型号与系统版本，便于定位
- **功能建议**：想加新功能或改进，欢迎先在 [Issues](https://github.com/jieapi/aicode/issues) 讨论
- **贡献代码**：欢迎提交 [Pull Request](https://github.com/jieapi/aicode/pulls)

## 致谢

- [OpenCode](https://github.com/anomalyco/opencode) — 终端 AI 编码工具，本项目的核心灵感来源
- [Termux](https://github.com/termux/termux-app) — Android 终端模拟器，提供了终端组件与 PRoot 方案
- [Kelivo](https://github.com/Chevey339/kelivo) — 跨平台 LLM 聊天客户端，AI 对话界面设计参考

## 开源协议

本项目基于 [GPL-3.0](LICENSE) 协议开源。
