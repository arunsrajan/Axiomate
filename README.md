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

### 12. 🚀 50 Advanced Autonomous Agentic AI Capabilities

Axiomate includes 50 purpose-built agentic capabilities accessible via the top-level **`Agent Features`** menu, quick action chips in the AI dock, and dedicated interactive Swing dialogs:

#### 1. Planning & Task Understanding
1. **Intent Clarification Loop (`IntentClarificationService`)**: Identifies underspecified requirements, ambiguities, and assumptions before large changes, prompting structured multi-choice developer clarification.
2. **Living Plan Canvas (`LivingPlanCanvas`, Dialog `Ctrl+Shift+L`)**: Editable visual plan (steps, files, risks, progress) that dynamically updates as the agent executes and allows mid-run reordering and additions.
3. **Blast-Radius Preview (`BlastRadiusAnalyzer`)**: Dependency-tree analysis computing every file, class, API, test, and downstream service touched by a proposed change with risk score ratings.
4. **Effort and Cost Estimator (`EffortCostEstimator`)**: Accurately predicts execution time (seconds), token consumption (input/output/total), estimated API cost ($ USD), and confidence score before running tasks.
5. **Spec-to-Ticket Decomposition (`SpecToTicketDecomposer`)**: Ingests PRDs, RFCs, or feature specs and decomposes them into sized (S/M/L/XL), dependency-ordered tickets for autonomous parallel execution.
6. **"Why This Approach" Rationale (`ApproachRationaleEngine`)**: Generates architectural decision records (ADRs) comparing chosen implementation strategies against rejected alternatives with explicit tradeoff justifications.

#### 2. Execution & Autonomy
7. **Autonomy Dial per Task (`AutonomyDial`)**: Per-directory and risk-level permission controls: `SUGGEST_ONLY` (read-only), `EDIT_WITH_APPROVAL` (diff review required), and `FULLY_AUTONOMOUS` (unattended execution).
8. **Parallel Agent Swarm with Merge Arbitration (`AgentSwarmCoordinator`)**: Coordinates multiple isolated worker agents running on dedicated Git worktrees with an arbitrator agent reconciling merge conflicts.
9. **Speculative Branches (`SpeculativeBranchManager`)**: Generates 2–3 competing implementations in parallel, benchmarks throughput and execution latency, and automatically adopts the winning strategy.
10. **Checkpoint and Time-Travel (`CheckpointTimeTravelManager`)**: Snapshots workspace state and file diffs at every agent step, enabling one-click rewind and forking to any prior step.
11. **Long-Running Background Jobs (`BackgroundJobEngine`)**: Offloads lengthy migrations and codebase refactorings to background worker threads, notifying developers upon completion.
12. **Self-Healing CI (`SelfHealingCiService`)**: Parses build and CI failure logs (`mvn`, `npm`, `gcc`), diagnoses root failure causes, proposes pinpoint patches, and opens pull requests automatically.
13. **Scheduled Maintenance Agents (`ScheduledMaintenanceManager`)**: Cron-like background maintenance runner for dependency bumps, dead code sweeps, and flaky test triage.
14. **Human-in-the-Loop Breakpoints (`HumanInTheLoopGate`)**: Configurable safety breakpoints triggering mandatory developer approvals before touching sensitive paths (e.g. `/auth/`, `/billing/`, SQL migrations).

#### 3. Code Understanding
15. **Codebase Knowledge Graph (`CodebaseKnowledgeGraph`, Dialog `Ctrl+Shift+K`)**: Queryable indexed graph of modules, ownership, call chains, and data flow kept up-to-date across the workspace.
16. **Architecture Drift Detector (`ArchitectureDriftDetector`)**: Validates codebase against layering constraints and forbidden dependency rules (e.g. `domain` cannot import `controller`), alerting on architectural violations.
17. **"Explain This Repo to Me" Onboarding Tour (`OnboardingTourGuide`)**: Guided interactive onboarding tour detailing key components, core lifecycles, and configuration points for new engineers.
18. **Historical Context Lens (`HistoricalContextLens`)**: Contextual hover lens displaying Git blame, author, PR reference, and architectural intent behind any selected line of code.
19. **Semantic Diff (`SemanticDiffEngine`)**: Summarizes unified diffs into human-readable behavioral transformations (e.g. *"Retry logic now capped at 5 attempts"*) rather than raw line changes.
20. **Hidden Coupling Finder (`HiddenCouplingFinder`)**: Co-change commit miner surfacing files that frequently modify together despite lacking explicit code imports.

#### 4. Testing & Verification
21. **Test-First Mode / TDD (`TestFirstModeEngine`)**: Enforces Red-Green-Refactor by writing failing tests from specifications first, then generating implementation code until all tests pass.
22. **Mutation Testing on Agent Code (`MutationTestingEngine`)**: Injects AST mutations (conditional boundary, arithmetic negation, return null) to measure the kill-rate and bug-detection power of test suites.
23. **Property-Based Test Generation (`PropertyBasedTestGenerator`)**: Infers state invariants and synthesizes parameterized fuzz tests and boundary-condition generators.
24. **Independent Verifier Agent (`IndependentVerifierAgent`)**: Isolated agent with zero implementation history that independently evaluates code quality, compliance, and regression safety.
25. **Visual Regression for UI Changes (`VisualRegressionEngine`)**: Renders and compares Swing/web UI components before and after changes, generating pixel-by-pixel diff heatmaps and similarity scores.
26. **Runtime Behavior Replay (`RuntimeBehaviorReplay`)**: Replays recorded production traces against newly written code to detect behavioral drifts and breaking API changes.

#### 5. Debugging & Runtime
27. **Live Debugger Agent (`LiveDebuggerAgent`)**: Autonomous debugging agent that formulates hypotheses, places synthetic probe breakpoints, inspects runtime state, and isolates culprits.
28. **Log-to-Root-Cause Analyzer (`LogToRootCauseAnalyzer`)**: Correlates stack traces and log dumps with recent Git commits to identify the root cause and offending source lines.
29. **Performance Profiler Agent (`PerformanceProfilerAgent`)**: Analyzes hot execution paths, detects CPU/memory bottlenecks, generates microbenchmarks, and validates optimization gains.
30. **Reproduction Builder (`ReproductionBuilder`)**: Converts bug reports and symptom logs into minimal, self-contained, reproducible test cases.

#### 6. Security & Safety
31. **Sandboxed Execution by Default (`ExecutionSandbox`)**: Enforces strict workspace path allowlists, prevents directory traversal attacks, and restricts command executions.
32. **Secret-Leak Guard (`SecretLeakGuard`)**: Scans code diffs, logs, and prompt outputs for API keys, AWS credentials, JWTs, and private keys, redacting and blocking leaks.
33. **Prompt-Injection Shield (`PromptInjectionShield`)**: Treats external files and web content as untrusted data, sanitizing instruction override attacks and delimiters before LLM ingestion.
34. **Supply-Chain Vetting (`SupplyChainVettingService`)**: Vets newly introduced dependencies for known CVEs, license compliance (MIT/Apache vs GPL copyleft), and maintenance vitality.
35. **Irreversible-Action Gate (`IrreversibleActionGate`)**: Enforces mandatory human confirmation for destructive operations (file deletion, `git push --force`, `DROP TABLE`).
36. **Full Audit Trail (`AuditTrailService`, Dialog `Ctrl+Shift+A`)**: Tamper-evident, cryptographically hashed audit log tracking every agent action, tool invocation, and decision rationale.

#### 7. Collaboration
37. **Multiplayer Agent Sessions (`MultiplayerSessionManager`)**: Multi-developer collaborative agent sessions supporting live participant presence, synchronized cursors, and shared steering.
38. **Agent Handoff Notes (`AgentHandoffNotesService`)**: Generates structured transition briefs (completed work, active hypotheses, blockers, open questions) when transferring tasks between agents or engineers.
39. **PR Review Copilot (`PrReviewCopilot`)**: Autonomous pull request reviewer that answers reviewer questions, verifies suggestions, and applies requested changes.
40. **Team Conventions Memory (`TeamConventionsMemoryService`)**: Learns code style conventions, naming patterns, and idiomatic preferences from accepted code reviews and updates agent memory.
41. **Stakeholder Summaries (`StakeholderSummaryGenerator`)**: Translates technical code changes into tailored executive summaries for Product Managers, QA, and Customer Support.

#### 8. Customization & Extensibility
42. **Custom Agent Roles (`CustomAgentRolesRegistry`)**: Configurable agent personas (Security Auditor, DB Migration Expert, Accessibility Specialist) with tailored system prompts and allowed tools.
43. **Workflow Recorder to Skill (`WorkflowRecorderSkillService`)**: Observes manual multi-step developer operations and converts them into reusable, automated agent skills.
44. **Tool and Connector Marketplace (`ToolConnectorMarketplace`)**: Plug-and-play directory for installing external connectors (Jira, Figma, Datadog, GitHub, AWS, Postgres).
45. **Policy-as-Code for Agents (`PolicyAsCodeEngine`)**: Enterprise rule engine defining path restrictions, forbidden imports, and mandatory reviews as enforceable code policies.

#### 9. Developer Experience
46. **Voice and Sketch Input (`VoiceSketchInputProcessor`)**: Transcribes spoken feature requests and UI whiteboard sketch descriptions into structured requirements and code scaffolds.
47. **Confidence Heatmap (`ConfidenceHeatmapService`)**: Computes per-line agent confidence scores, generating visual heatmaps so reviewers focus attention on high-risk code sections.
48. **Learning Mode (`LearningModeEngine`)**: Explains agent modifications as educational micro-lessons covering design patterns, language features, and architectural reasoning.
49. **Focus Guardian (`FocusGuardianService`)**: Batches non-critical agent questions and notifications during active developer typing, releasing them during natural pauses.
50. **Agent Analytics Dashboard (`AgentAnalyticsDashboard`, Dialog `Ctrl+Shift+D`)**: Comprehensive dashboard tracking acceptance rate, token spend, time saved, rework rate, and task performance metrics.

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
│       ├── CodeRefactorTool.java      # Tool: source patching & refactoring
│       └── AutonomousCodeEditorTool.java # Tool: autonomous multi-file inspection and patching
├── features/                          # 50 Advanced Agentic Capabilities
│   ├── planning/                      # F1-F6: Clarification, Plan Canvas, Blast Radius, Effort Estimator, Specs, Rationale
│   ├── execution/                     # F7-F14: Autonomy Dial, Swarm, Speculative, Checkpoints, Background, CI, Cron, Gate
│   ├── codeunderstanding/             # F15-F20: Knowledge Graph, Drift, Onboarding, Context Lens, Semantic Diff, Coupling
│   ├── testing/                       # F21-F26: Test-First, Mutation Testing, Property Fuzzing, Verifier, Visual Regr, Replay
│   ├── debugging/                     # F27-F30: Live Debugger, Log Root-Cause, Profiler Agent, Reproduction Builder
│   ├── security/                      # F31-F36: Sandbox, Secret Guard, Injection Shield, Supply Chain, Action Gate, Audit
│   ├── collaboration/                 # F37-F41: Multiplayer, Handoff Notes, PR Copilot, Team Conventions, Stakeholder Summaries
│   ├── extensibility/                 # F42-F45: Custom Roles, Workflow Recorder, Tool Marketplace, Policy as Code
│   ├── devexperience/                 # F46-F50: Voice & Sketch, Confidence Heatmap, Learning Mode, Focus Guardian, Analytics
│   └── ui/                            # Interactive Swing dialogs for all 50 Agentic AI features
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
│   │   ├── AIAgentPanel.java          # AI Agent dock: sessions, model chooser, token meter, feature chips
│   │   ├── ProviderSettingsPanel.java # Configurable URLs, models, and task routing UI
│   │   ├── MemoryPanel.java           # Agentic Memory browser, search, and import UI
│   │   ├── McpSettingsPanel.java      # Configurable settings UI for MCP servers
│   │   ├── TerminalPanel.java         # Shell, memory tab, tool trace logs, build output
│   │   ├── StatusBar.java             # Caret coords, active session, tokens & memory count
│   │   ├── ToolBar.java               # Top toolbar with Import Memory button
│   │   └── SettingsDialog.java        # Tabbed configuration modal (AI + MCP + Editor)
│   ├── menu/
│   │   └── AppMenuBar.java            # File, Edit, Agent, Agent Features, View, Run, Help menus
│   └── util/
│       └── UIUtils.java               # Themes, vector icons, pill buttons
└── util/
    └── ProjectManager.java            # Workspace directory & active file manager
```


