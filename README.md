# 🔨 DeepSeekChat

AI chat + pocket app-builder for Android. Talk to DeepSeek models through
NVIDIA's free API endpoint — and since v2.0, make it **build APKs on GitHub
Actions without ever touching a PC**.

Built *on a phone* (Termux + proot Ubuntu + opencode), built *by* phones,
for phones.

## ✨ Features

### Chat
- 💬 Streaming replies from `deepseek-ai/deepseek-v4-flash-0731` (configurable)
- 💭 Collapsible "thinking" bubbles with reasoning-effort control
- 📝 Markdown rendering: bold, italic, inline code, fenced code blocks
- 📋 Long-press any bubble: Copy · Share · Copy code · ↻ Regenerate
- ■ Stop mid-stream (partial answers kept)
- 💾 Multiple chats: create / switch / delete, all persisted locally
- 🔐 API key stored in Android Keystore–backed encryption

### Pocket Builder (v2.0+)
- 📂 Browse any GitHub repo's files from your phone
- 🤖 AI Edit: describe a change → DeepSeek rewrites the file → review → push
- ⏳ Live GitHub Actions build status polling
- ⬇ One-tap artifact download → unzip → install prompt

## 📲 Install
Grab the latest signed APK from [Releases](../../releases).

Setup after install:
1. Get a free key at [build.nvidia.com](https://build.nvidia.com) → Settings
2. Optional: paste a GitHub PAT (`repo` + `workflow` scopes) + target repo to unlock 🔨 Builder

## 🏗 How releases are made
Every tag `vX.Y` triggers CI to build & sign `app-release.apk` automatically
and attach it to a GitHub Release. No computer involved.

## License
Copyright (c) 2026 akarshjadhav21. All rights reserved.
