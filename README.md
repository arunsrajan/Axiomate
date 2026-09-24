# Axiomate AI Agent IDE

A modern, high-performance Java desktop IDE built under the package **`com.github.axiomate.agentic.ide`**, featuring **Multi-Provider AI Models (Anthropic, OpenAI, Google Gemini, Custom/Local)**, **Autonomous Task-Based Routing**, **Multi-Agent Sessions**, **Token Usage & Limit Meter**, **95% Context Compression Utility**, **Model Context Protocol (MCP) Server Integration**, **Agentic AI Memory**, and a rich RSyntaxTextArea code editor.

---

## 🌟 Key Features

### 1. 🌐 Configurable Multi-Provider Support (Anthropic, OpenAI, Gemini & Custom)
- **Configurable Endpoints & URLs**:
  - **Anthropic Claude**: `https://api.anthropic.com/v1` (or enterprise proxy)
  - **OpenAI**: `https://api.openai.com/v1` (or Azure OpenAI)
  - **Google Gemini**: `https://generativelanguage.googleapis.com/v1beta`
  - **Custom / Local**: `http://localhost:11434/v1` (Ollama, LM Studio, vLLM, DeepSeek)
- **Multiple Models per Provider**:
  - **Anthropic**: Claude 3.7 Sonnet (200k context), Claude 3.5 Sonnet (200k), Claude 3.5 Haiku (200k)
  - **OpenAI**: GPT-4o (128k context), GPT-4o-mini (128k), o1 (200k), o3-mini (200k)
  - **Google Gemini**: Gemini 2.0 Flash (1M context), Gemini 1.5 Pro (2M context), Gemini 1.5 Flash (1M)
  - **Custom / Local**: Qwen 2.5 Coder (32k), Llama 3.2 (8k), DeepSeek Coder (64k)
- **Dynamic Model Chooser & Custom Model Registry**:
  - Dynamically switch providers and models on the fly directly in the AI Agent Dock.
  - Add custom model definitions with custom context window and output token limits in **Settings (`Ctrl+,`)**.

### 2. 🎯 Autonomous Task-Based Model Routing
- Automatically classifies user requests into specific task types and routes them to the best suited provider and model:
  - **Code Refactoring & Modernization** $\rightarrow$ `ANTHROPIC:claude-3-7-sonnet`
  - **Code Explanation & Architectural Walkthrough** $\rightarrow$ `GEMINI:gemini-2.0-flash`
  - **Unit Test Generation (JUnit 5)** $\rightarrow$ `OPENAI:gpt-4o`
  - **Bug Fixing & Diagnostics** $\rightarrow$ `ANTHROPIC:claude-3-7-sonnet`
  - **Terminal Commands & MCP Tools** $\rightarrow$ `GEMINI:gemini-2.0-flash`
- Fully configurable in the **AI Providers & Task Routing** tab in Settings.

### 3. 👥 Multi-Agent Sessions
- Launch, name, and switch between multiple concurrent autonomous agent sessions (e.g. *Code Architect*, *Security Auditor*, *Test Specialist*).
- Each agent session maintains its own message history, provider, model configuration, and dedicated token tracker.
- Quick session management in the UI: **+ New Agent**, **Rename**, and **Close** buttons, plus menu shortcuts (`Ctrl+Shift+N`).

### 4. 📊 Real-Time Token Usage & Context Limit Meter
- Tracks prompt tokens, completion tokens, and total usage against the model's maximum context limit.
- Real-time percentage indicator and color-coded progress bar:
  - 🟢 **Green** ($< 60\%$)
  - 🟡 **Yellow** ($60\% - 84\%$)
  - 🟠 **Orange** ($85\% - 94\%$)
  - 🔴 **Red** ($\ge 95\%$)
- Integrated directly in the AI Dock and bottom Status Bar (`Tokens: 12,400 / 200,000 (6.2%)`).

### 5. ⚡ 95% Context Compression Utility
- Automatic context compression triggered when token consumption reaches $\ge 95\%$ of the model's maximum context limit.
- Synthesizes earlier conversation turns into a structured context summary.
- Archives condensed history into episodic long-term memory in `MemoryManager` to prevent loss of knowledge.
- Preserves recent turns and reduces active token consumption back to safe limits ($\sim 5-15\%$).
- Manual **⚡ Compress (95%)** button for on-demand context optimization.

### 6. 🔌 Model Context Protocol (MCP) Server Support
- Connects to any standard MCP server via **Stdio** (`ProcessBuilder`) or **SSE** (`HttpClient`) transports.
- Dynamic tool discovery via JSON-RPC 2.0 handshake (`initialize`, `notifications/initialized`, `tools/list`).
- Integrated **MCP Settings (`Ctrl+Shift+P`)** to test connections, ping servers, and preview tool schemas.
- Configuration persisted in `~/.axiomate-ide/mcp_servers.json`.

### 7. ⚡ Autonomous Multi-Turn Tool Calling Loop
- Seamless integration of all tools into LangChain4j `ToolSpecification` format:
  - `file_system`: Read files, write code, list directories in the workspace.
  - `terminal`: Execute shell commands (`mvn`, `git`, `javac`) with stdout/stderr inspection.
  - `code_refactor`: Automatic code modernization and targeted patch application.
  - `agent_memory`: Store, recall, and search agentic memories.
  - `mcp_<server>_<tool>`: Dynamically discovered tools from external MCP servers.
- Executes full multi-turn loop (`AiMessage.hasToolExecutionRequests()`) until final solution.

### 8. 🧠 Agentic AI Memory Subsystem
- **Four Memory Tiers**:
  - **`WORKING`**: Immediate task context, goals, and scratchpad.
  - **`LONG_TERM`**: Persistent knowledge, architectural concepts, and domain facts.
  - **`EPISODIC`**: History of completed agent tasks, execution traces, and compressed turns.
  - **`PROJECT_RULE`**: Repository-specific guidelines, clean code rules, and lint invariants.
- **Import / Export All Memory**: Batch import memories from `.json` memory dumps, `.md` markdown rulebooks, or directories (`Ctrl+Shift+M`).

### 9. 📝 Pro Code Editor (`RSyntaxTextArea`) & File Explorer
- Multi-tabbed document architecture with dirty markers (`*`) and unsaved change protection.
- Rich syntax highlighting for **Java, Python, TypeScript, JavaScript, JSON, XML, HTML, CSS, SQL, Markdown, Shell**.
- Workspace file tree with context actions: *New File...*, *New Folder...*, *Delete*, *Open in Editor*, *Ask AI Agent*.
- Integrated terminal, build runner, memory inspector (`Alt+4`), and real-time status bar.

### 10. 💾 Project State Persistence (`~/.axiomate-ide/project_state.json`)
- Remembers and restores open editor tabs, active files, and timestamps across project folder open/close events and IDE restarts.
- Multi-project workspace state tracking keyed by canonical path.
- Centralized configuration directory in `~/.axiomate-ide/` with automated migration of legacy configuration files from `~/.agentforge-ide/` and `~/.agentic-ide/`.

### 11. 📎 Workspace File Mentions (`@` Symbol) & Auto-Context Injection
- Type `@` in the AI Agent chat window to display a searchable, keyboard-navigable (`↑`/`↓`/`Enter`/`Tab`/`Esc`) popup list of workspace files.
- Selecting a file autocompletes `@<relativePath>`.
- On prompt submission, `@` references are parsed and the exact file contents are automatically extracted and injected into the AI agent context.
- Fully configurable in IDE Settings (`fileMentionsEnabled`, custom trigger symbol).

---

## 🚀 Quick Start

### Prerequisites
- **JDK 21** or later (Tested on OpenJDK 23 / 21)
- **Apache Maven 3.9+**

### 1. Run Directly with Maven
```bash
mvn compile exec:java
```

### 2. Build Executable Fat JAR
```bash
mvn clean package -DskipTests
```
Run the packaged application:
```bash
java -jar target/ai-agent-ide-1.0.0-SNAPSHOT.jar
```

### 3. Run Test Suite
```bash
mvn test
```

---

## 📁 Package & Directory Structure

```
src/main/java/com/github/axiomate/agentic/ide/
├── Main.java                          # Launcher: DPI scaling, theme setup, EDT lifecycle
├── config/
│   ├── IdeConfig.java                 # Configuration model: providers, models, routing, compression, @ mentions
│   ├── ConfigManager.java             # JSON persistence (~/.axiomate-ide/config.json)
│   ├── ProjectStateManager.java       # Project state persistence (~/.axiomate-ide/project_state.json)
│   ├── ProviderConfig.java            # Provider endpoint URLs, API keys, models list
│   ├── ModelDefinition.java           # Model metadata: context limits, tags, output limits
│   └── TaskType.java                  # GENERAL, EXPLAIN, REFACTOR, GENERATE_TESTS, DEBUG_FIX, TERMINAL_TOOL
├── agent/
│   ├── AIAgentService.java            # Agent service contract
│   ├── AgentRole.java                 # Role enum (USER, ASSISTANT, TOOL, SYSTEM)
│   ├── AgentMessage.java              # Chat message model
│   ├── AgentListener.java             # Callbacks for streaming tokens, thoughts, tools
│   ├── AgentManager.java              # Coordinates active provider and tool registry
│   ├── MockAgentService.java          # Offline simulator with tool & memory execution
│   ├── LangChainAgentService.java     # LangChain4j multi-turn tool calling loop
│   ├── UniversalChatModelFactory.java # Instantiates Anthropic, OpenAI, Gemini chat models
│   ├── router/
│   │   └── AutonomousTaskRouter.java  # Task classifier and multi-provider model router
│   ├── session/
│   │   ├── AgentSession.java          # Multi-agent session model
│   │   ├── SessionManager.java        # Coordinates concurrent agent sessions
│   │   ├── TokenTracker.java          # Real-time token tracking & percentage limits
│   │   └── ContextCompressor.java     # 95% limit threshold context compression utility
│   ├── memory/
│   │   ├── MemoryType.java            # WORKING, LONG_TERM, EPISODIC, PROJECT_RULE
│   │   ├── MemoryItem.java            # Memory data unit (title, content, tags, importance)
│   │   ├── AgentMemoryStore.java      # Memory store interface
│   │   ├── JsonAgentMemoryStore.java  # File-backed store, search, and import/export
│   │   └── MemoryManager.java         # Singleton coordinator & episodic recorder
│   └── tools/
│       ├── AgentTool.java             # Tool contract
│       ├── MemoryTool.java            # Tool: search, remember, recall agentic memories
│       ├── FileSystemTool.java        # Tool: read, write, list files in workspace
│       ├── TerminalTool.java          # Tool: run terminal commands and capture output
│       └── CodeRefactorTool.java      # Tool: source patching & refactoring
├── mcp/
│   ├── McpTransport.java              # STDIO, SSE
│   ├── McpServerConfig.java           # MCP server configuration data model
│   ├── McpClient.java                 # JSON-RPC 2.0 handshake, listTools, and callTool
│   ├── McpTool.java                   # Adapter bridging MCP tool to AgentTool
│   └── McpManager.java                # Singleton managing MCP servers and tool lifecycle
├── ui/
│   ├── MainFrame.java                 # Main IDE layout, split panes, execution
│   ├── components/
│   │   ├── EditorPanel.java           # RSyntaxTextArea tabbed editor with dirty flags
│   │   ├── ProjectTreePanel.java      # Workspace file explorer tree with context menus
│   │   ├── AIAgentPanel.java          # AI Agent dock: sessions, model chooser, token meter
│   │   ├── ProviderSettingsPanel.java # Configurable URLs, models, and task routing UI
│   │   ├── MemoryPanel.java           # Agentic Memory browser, search, and import UI
│   │   ├── McpSettingsPanel.java      # Configurable settings UI for MCP servers
│   │   ├── TerminalPanel.java         # Shell, memory tab, tool trace logs, build output
│   │   ├── StatusBar.java             # Caret coords, active session, tokens & memory count
│   │   ├── ToolBar.java               # Top toolbar with Import Memory button
│   │   └── SettingsDialog.java        # Tabbed configuration modal (AI + MCP + Editor)
│   ├── menu/
│   │   └── AppMenuBar.java            # File, Edit, Agent, View, Run, Help menus
│   └── util/
│       └── UIUtils.java               # Themes, vector icons, pill buttons
└── util/
    └── ProjectManager.java            # Workspace directory & active file manager
```

