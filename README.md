<div align="center">

<p align="center">
  <img src="mateclaw-ui/public/logo/mateclaw_logo_s.png" alt="MateClaw Logo" width="120">
</p>

# MateClaw

<p align="center"><b>Build AI that thinks, acts, remembers, and ships.</b></p>

[![GitHub Repo](https://img.shields.io/badge/GitHub-Repo-black.svg?logo=github)](https://github.com/matevip/mateclaw)
[![Documentation](https://img.shields.io/badge/Docs-Website-green.svg?logo=readthedocs&label=Docs)](https://claw.mate.vip/docs)
[![Live Demo](https://img.shields.io/badge/Demo-Online-orange.svg?logo=vercel&label=Demo)](https://claw-demo.mate.vip)
[![Website](https://img.shields.io/badge/Website-claw.mate.vip-blue.svg?logo=googlechrome&label=Site)](https://claw.mate.vip)
[![Java Version](https://img.shields.io/badge/Java-17+-blue.svg?logo=openjdk&label=Java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D.svg?logo=vuedotjs)](https://vuejs.org/)
[![Last Commit](https://img.shields.io/github/last-commit/matevip/mateclaw)](https://github.com/matevip/mateclaw)
[![License](https://img.shields.io/badge/license-Apache--2.0-red.svg?logo=opensourceinitiative&label=License)](LICENSE)

[[Website](https://claw.mate.vip)] [[Live Demo](https://claw-demo.mate.vip)] [[Documentation](https://claw.mate.vip/docs)] [[中文](README_zh.md)]

</div>

<p align="center">
  <img src="assets/images/preview.png" alt="MateClaw Preview" width="800">
</p>

---

An AI agent. A knowledge engine. A memory system. A tool runtime. A multi-channel presence.

**One product. The whole widget.**

MateClaw is a personal AI operating system built with **Java + Vue 3**, powered by [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba). It's not a chatbox, not a workflow builder, not just another coding assistant. It's the entire system — from reasoning to remembering to shipping — in one deployment.

Three things make it insanely different:

1. **Agents do work, not just talk** — ReAct + Plan-and-Execute. Not one-shot answers — iterative reasoning that actually completes tasks
2. **Knowledge is shaped, not just stored** — An LLM Wiki that digests raw material into structured, linked pages. The difference between a warehouse and a library
3. **End-to-end, no compromises** — Web console, desktop app, 7 IM channels, tool guardrails, enterprise auth. One team, one deployment, one experience

---

## Why MateClaw

Most AI tools do one thing well. MateClaw does the whole thing.

| Capability | MateClaw | [OpenClaw](https://github.com/openclaw/openclaw) | [CoPaw](https://github.com/agentscope-ai/CoPaw) | QClaw | [Claude Code](https://github.com/anthropics/claude-code) | [Cursor](https://cursor.com) | [Windsurf](https://windsurf.com) |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Multi-Agent Orchestration | **ReAct + Plan-Execute** | Single agent | Single agent | Single agent | Parallel agents | Background agents | Cascade engine |
| LLM Wiki Knowledge Base | **Yes** | No | No | Knowledge graph | No | No | No |
| Compound Memory | **Extract + Consolidate + Dream** | Plugin-based | ReMe memory | Plugin-based | Auto-memory | No | Session only |
| Tool Guard & Approval | **Yes** | No | No | No | No | No | No |
| Multi-Channel IM | **7 channels** | 25+ channels | 5+ channels | WeChat/QQ | Terminal only | IDE only | IDE only |
| Web Management UI | **Full dashboard** | No | Limited | No | No | No | No |
| Desktop App | **Electron + JRE** | CLI | CLI | CLI | CLI | Yes | Yes |
| Multimodal Creation | **TTS/STT/Img/Music/Video** | No | No | No | No | No | No |
| Skill Marketplace | **ClawHub** | Plugin system | Python skills | Templates | MCP skills | No | MCP |
| Enterprise Auth | **RBAC + JWT** | No | No | No | No | Teams plan | Teams plan |
| Open Source | **Apache 2.0** | Yes | Yes | Partial | CLI only | No | No |
| Pricing | **Free** | Free | Free | Unreleased | Token-based | $0–200/mo | $0–60/mo |

> **The difference**: OpenClaw gives you channels. Claude Code gives you a terminal. Cursor gives you an editor. MateClaw gives you an AI operating system — agents that reason, remember, create, and show up everywhere your work happens. With a management dashboard. For free.

---

## Architecture

<p align="center">
  <img src="assets/architecture-biz-en.svg" alt="Business Architecture" width="800">
</p>

<details>
<summary><b>Technical Architecture</b></summary>
<p align="center">
  <img src="assets/architecture-tech-en.svg" alt="Technical Architecture" width="800">
</p>
</details>

---

## Core Capabilities

### Agent Runtime

- **ReAct agents** — Think, act, observe, repeat. Iterative reasoning that gets things done
- **Plan-and-Execute** — Decompose complex work into ordered steps, then execute each one
- **Dynamic configuration** — Load agent personality, tools, and constraints from the database at runtime
- **Runtime resilience** — Context pruning, smart truncation, stale stream cleanup, and recovery

### Knowledge & Memory

- **LLM Wiki** — AI-powered knowledge base that digests raw materials into structured, linked pages with summaries
- **Workspace memory** — `AGENTS.md`, `SOUL.md`, `PROFILE.md`, `MEMORY.md`, daily notes
- **Memory lifecycle** — Post-conversation extraction, scheduled consolidation, dreaming workflows
- **Compound memory** — Understanding improves over time instead of resetting every query

### Tools, Skills & MCP

- **Built-in tools** — Web search, file ops, memory access, date/time, and more
- **MCP integration** — stdio, SSE, and Streamable HTTP transports
- **Skill system** — Installable `SKILL.md` packages with ClawHub marketplace
- **Tool guard** — Approval flows, file-path protection, runtime filtering

### Multimodal Creation

Text-to-speech · Speech-to-text · Image generation · Music generation · Video generation

### Model Flexibility

14+ providers including DashScope, OpenAI, Anthropic, Gemini, DeepSeek, Kimi, Ollama, LM Studio, MLX, and more. Configure everything in the web UI.

### Surfaces

- **Web console** — Chat, agents, tools, skills, knowledge, models, security, settings
- **Desktop app** — Electron with bundled JRE 21, no Java installation needed
- **Channels** — DingTalk, Feishu, WeChat Work, Telegram, Discord, QQ

---

## Quick Start

### Prerequisites

- Java 17+ · Node.js 18+ · pnpm · Maven 3.9+

### Local Development

```bash
# Backend
cd mateclaw-server
mvn spring-boot:run          # http://localhost:18088

# Frontend
cd mateclaw-ui
pnpm install && pnpm dev     # http://localhost:5173
```

Login: `admin` / `admin123`

### Docker

```bash
cp .env.example .env
docker compose up -d          # http://localhost:18080
```

### Desktop App

Download from [GitHub Releases](https://github.com/matevip/mateclaw/releases). Bundles JRE 21 — no Java needed.

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Spring Boot 3.5 · Spring AI Alibaba 1.1 |
| Agent | StateGraph Runtime |
| Database | H2 (dev) / MySQL 8.0+ (prod) |
| ORM | MyBatis Plus 3.5 |
| Auth | Spring Security + JWT |
| Frontend | Vue 3 · TypeScript · Vite |
| UI | Element Plus · TailwindCSS 4 |
| Desktop | Electron · electron-updater |

---

## Project Structure

```
mateclaw/
├── mateclaw-server/     Spring Boot backend
├── mateclaw-ui/         Vue 3 SPA frontend
├── mateclaw-desktop/    Electron desktop app
├── docker-compose.yml
└── .env.example
```

---

## Documentation

Full docs at **[claw.mate.vip/docs](https://claw.mate.vip/docs)**

---

## Roadmap

- Richer multi-agent collaboration
- Smarter model routing
- Deeper multimodal understanding
- Stronger long-term memory
- Richer ClawHub ecosystem

---

## Contributing

```bash
git clone https://github.com/matevip/mateclaw.git
cd mateclaw
cd mateclaw-server && mvn clean compile
cd ../mateclaw-ui && pnpm install && pnpm dev
```

---

## Why The Name

**Mate** is companion. **Claw** is capability.

A system that stays with you, and a system that grabs work and moves it.

---

## License

[Apache License 2.0](LICENSE)
