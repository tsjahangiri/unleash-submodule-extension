# Task 2 — Multi-Repo Release Automation
### Unleash Maven Plugin · Custom CDI Extension

| | |
|---|---|
| **Plugin** | `io.github.mavenplugins:unleash-maven-plugin:3.3.0` |
| **Extension** | [`unleash-submodule-extension`](https://github.com/tsjahangiri/unleash-submodule-extension) |
| **Repos** | [`multi-repo-poc`](https://github.com/tsjahangiri/multi-repo-poc) · poc-util · poc-service · poc-api |
| **Entry point** | `poc-util` — single command releases all modules automatically |
| **SCM** | GitHub (Git submodules + GitHub Packages) |

---

## 1. The Problem — Before vs After

### Before (Manual — 5 steps per release)

```
Developer                poc-util          poc-service         poc-api
    │                       │                   │                  │
    ├─ mvn unleash:perform ─►│                   │                  │
    │                       │ released ✅        │                  │
    │                       │                   │                  │
    ├─ edit pom.xml ────────────────────────────►│                  │
    │  (update poc-util version manually)        │                  │
    │                       │                   │                  │
    ├─ mvn unleash:perform ─────────────────────►│                  │
    │                       │                   │ released ✅       │
    │                       │                   │                  │
    ├─ edit pom.xml ─────────────────────────────────────────────── ►│
    │  (update poc-service version manually)                         │
    │                       │                   │                  │
    └─ mvn unleash:perform ──────────────────────────────────────── ►│
                                                                     │ released ✅
```

**5 manual commands. Easy to forget a step. Easy to release in wrong order.**

---

### After (Automated — 1 command)

```
Developer                Extension (OrchestrateSubmoduleRelease)
    │                               │
    ├─ mvn unleash:perform ─────────►│
       (from poc-util only)         │
                                    ├─ releases poc-service automatically
                                    ├─ updates poc-api pom automatically
                                    ├─ releases poc-api automatically
                                    └─ poc-util finishes releasing itself
                                                        ALL DONE ✅
```

---

## 2. Repository Structure

```
multi-repo-poc/                  ← parent repo (Git submodule host)
│   .gitmodules                  ← registers the 3 submodules
│
├── poc-util/                    ← index 0 — no sibling deps — ENTRY POINT
│   ├── pom.xml
│   └── submodule-workflow.wf
│
├── poc-service/                 ← index 1 — depends on poc-util
│   ├── pom.xml
│   └── submodule-workflow.wf
│
└── poc-api/                     ← index 2 — depends on poc-service
    ├── pom.xml
    └── submodule-workflow.wf
```

### Dependency Chain

```
┌──────────┐        ┌─────────────┐        ┌─────────┐
│ poc-util │ ──────►│ poc-service │ ──────►│ poc-api │
│ index 0  │        │   index 1   │        │ index 2 │
└──────────┘        └─────────────┘        └─────────┘
  no deps             needs poc-util         needs poc-service
  ENTRY POINT
```

### Release Order (enforced by the extension)

```
poc-service  ──►  poc-api  ──►  poc-util (self, releases last)
```

> **Why does poc-util release last?**
> The `orchestrateSubmodules` step sits at position 20 in the workflow — after `installArtifacts` and `deployArtifacts`. This means poc-util first builds, tags, and deploys its own artifact, THEN orchestrates the others. At that point `poc-util:1.0.X` already exists in the local `.m2` and on GitHub Packages — so poc-service can compile against it successfully.

---

## 3. Problems Solved

### Problem 1 — Unleash is Blind to Other Repos

```
Standard unleash:                   Our extension:

   poc-util                            poc-util
      │                                   │
   unleash                             unleash
      │                                   │
   sees only                          reads .gitmodules
   itself ❌                          discovers all siblings ✅
```

Unleash has zero native awareness that Git submodules exist. It releases one repo and stops. Our extension reads `.gitmodules` to discover all sibling repos and orchestrates them.

---

### Problem 2 — SNAPSHOT Dependencies Block Release

```
poc-service/pom.xml BEFORE fix:        poc-service/pom.xml AFTER fix:

<dependency>                           <dependency>
  <artifactId>poc-util</artifactId>      <artifactId>poc-util</artifactId>
  <version>1.0.8-SNAPSHOT</version>      <version>1.0.8</version>
</dependency>                          </dependency>
        │                                      │
        ▼                                      ▼
unleash checkDependencies            unleash checkDependencies
    REJECTS ❌                           PASSES ✅
"SNAPSHOT dep found"
```

The extension updates sibling SNAPSHOT deps to release versions **before** triggering each release.

---

### Problem 3 — Infinite Recursion

```
WITHOUT guard:                         WITH guard (env var):

poc-util orchestrates                  poc-util orchestrates
  └─ triggers poc-service                └─ triggers poc-service
       └─ orchestrates                          (UNLEASH_ORCHESTRATED=true)
            └─ triggers poc-util               └─ execute() checks env var
                 └─ orchestrates               └─ "already orchestrated, skip"
                      └─ ... ♾️               └─ just releases itself ✅
```

---

## 4. Solution Architecture

```
unleash-submodule-extension/
├── OrchestrateSubmoduleRelease.java   ← core step (runs last in workflow)
├── CheckSubmoduleVersions.java        ← observability step (runs first)
└── META-INF/beans.xml                 ← CDI config (annotated mode)
         │
         │  installed as plugin dependency in each submodule's pom.xml
         ▼
unleash-maven-plugin picks it up via CDI
         │
         │  step IDs referenced in workflow file
         ▼
submodule-workflow.wf (copied to each submodule directory)
```

### 4.1 Extension pom.xml Dependencies

| Dependency | Scope | Why |
|---|---|---|
| `cdi-plugin-utils:4.0.1` | provided | CDI framework unleash uses for step discovery |
| `unleash-maven-plugin:3.3.0` | provided | Access to step interfaces and annotations |
| `maven-core:3.8.1` | provided | `MavenProject` injection |
| `jakarta.inject-api:2.0.1` | provided | `@Inject`, `@Named` annotations |
| `maven-invoker:3.1.0` | compile | Spawn child Maven processes |
| `org.eclipse.jgit:5.13.3.*` | provided | Already in `unleash-scm-provider-git` at runtime |

> **Why `provided` scope for JGit?** JGit is already bundled inside `unleash-scm-provider-git` which is on the classpath at runtime. Using `provided` means it is available at compile time but not duplicated in the jar — avoids classpath conflicts.

### 4.2 CDI Bean Discovery

```xml
<!-- META-INF/beans.xml -->
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
       bean-discovery-mode="annotated">   ← MUST be annotated, not "all"
</beans>
```

> Using `bean-discovery-mode="all"` causes unleash to discover its own internal steps twice — resulting in a duplicate step ID error at startup.

---

## 5. Workflow File

```
┌─────────────────────────────────────────────────────────────┐
│                   submodule-workflow.wf                     │
├──────────────────────────────┬──────────────────────────────┤
│  PHASE 1 — CHECKS            │  PHASE 2 — RELEASE           │
│                              │                              │
│  checkSubmoduleVersions  ①   │  setReleaseVersions      ⑩  │
│  storeScmRevision        ②   │  addSpyPlugin            ⑪  │
│  checkProjectVersions    ③   │  buildReleaseArtifacts   ⑫  │
│  checkParentVersions     ④   │  removeSpyPlugin         ⑬  │
│  checkDependencies       ⑤   │  checkForScmChanges      ⑭  │
│  checkPlugins            ⑥   │  tagScm                  ⑮  │
│  checkPluginDependencies ⑦   │  detectReleaseArtifacts  ⑯  │
│  prepareVersions         ⑧   │  setDevVersion           ⑰  │
│  checkAether             ⑨   │  serializeMetadata       ⑱  │
│                              │  installArtifacts        ⑲  │
│                              │  deployArtifacts         ⑳  │
│                              │                              │
│                              │  orchestrateSubmodules   ㉑  │ ← LAST
└──────────────────────────────┴──────────────────────────────┘
```

> **Why `orchestrateSubmodules` is last:** Steps ⑲ and ⑳ install and deploy the current module's artifact. Only after ⑳ is `poc-util:1.0.8` available in `.m2` and on GitHub Packages. Child releases that depend on `poc-util` can only resolve it after this point.

---

## 6. Core Step — OrchestrateSubmoduleRelease

### 6.1 Execution Flow

```
execute() called by unleash
         │
         ▼
┌─────────────────────────────────────────┐
│  UNLEASH_ORCHESTRATED env var set?      │
│  (child process triggered by parent?)   │
└─────────────────────────────────────────┘
         │ YES                    │ NO
         ▼                        ▼
    return immediately      continue orchestration
    (just release self)
                                   │
                                   ▼
                         Read .gitmodules
                         → [poc-util, poc-service, poc-api]
                                   │
                                   ▼
                         Find currentIndex
                         (position of self in list)
                                   │
                                   ▼
                    ┌──────────────────────────────────┐
                    │  FOR EACH submodule in list      │
                    └──────────────────────────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                   │                    │
        index < self?      canonical path        no -SNAPSHOT
              │             = self?              in own version?
              ▼               ▼                    ▼
          SKIP            SKIP                  SKIP
        (upstream)        (self)           (already released)
                                   │
                                   ▼ (otherwise)
                    ┌──────────────────────────────────┐
                    │  updateSubmoduleDepsBeforeRelease │  PRE-RELEASE
                    │  triggerRelease                  │  RELEASE
                    │  updateDependentPoms             │  POST-RELEASE
                    └──────────────────────────────────┘
```

---

### 6.2 Infinite Loop Prevention

```
┌──────────────────────────────────────────────────────────────┐
│                    HOW THE GUARD WORKS                       │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  Parent process (poc-util):                                  │
│    triggerRelease(poc-service) {                             │
│      request.addShellEnvironment(                            │
│        "UNLEASH_ORCHESTRATED", "true")  ← sets flag         │
│    }                                                         │
│           │                                                  │
│           │  spawns child process                            │
│           ▼                                                  │
│  Child process (poc-service):                                │
│    execute() {                                               │
│      System.getenv("UNLEASH_ORCHESTRATED") → "true"         │
│      return; ← exits immediately, no orchestration          │
│    }                                                         │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│  WHY ENV VAR AND NOT -Dproperty=value ?                      │
│                                                              │
│  setProperties("unleash.orchestrated", "true")               │
│    → becomes Maven USER property                             │
│    → readable in pom.xml as ${unleash.orchestrated}          │
│    → NOT readable via System.getProperty() in Java ❌        │
│                                                              │
│  addShellEnvironment("UNLEASH_ORCHESTRATED", "true")         │
│    → becomes OS environment variable                         │
│    → inherited by ALL child processes automatically          │
│    → readable via System.getenv() in Java ✅                 │
└──────────────────────────────────────────────────────────────┘
```

---

### 6.3 Self-Skip — Canonical Path Comparison

```
.gitmodules contains:       currentDir.getCanonicalPath() returns:
  path = ./poc-util    →    /Users/.../multi-repo-poc/poc-util

new File(parentDir, "./poc-util").getCanonicalPath() returns:
                             /Users/.../multi-repo-poc/poc-util
                                        │
                                  EQUAL ✅ → skip self

vs string comparison:
  "./poc-util" == "poc-util"  → false ❌ → would try to release itself
```

---

### 6.4 Upstream Skip — Index-Based

```
.gitmodules order defines dependency direction:

  index 0 → poc-util     ◄── upstream (dependency)
  index 1 → poc-service  ◄── self (when running from poc-service)
  index 2 → poc-api          ▼ downstream (orchestrate this)

Running from poc-service (currentIndex = 1):
  i=0  poc-util    → 0 < 1 → SKIP upstream ⏭
  i=1  poc-service → canonical path match → SKIP self ⏭
  i=2  poc-api     → 2 > 1 → RELEASE 🚀

Running from poc-util (currentIndex = 0):
  i=0  poc-util    → canonical path match → SKIP self ⏭
  i=1  poc-service → 1 > 0 → RELEASE 🚀
  i=2  poc-api     → 2 > 0 → RELEASE 🚀
```

---

### 6.5 Critical Rule — Pending Modules Skipped in POST-RELEASE

```
WRONG (causes build failure):          CORRECT (current behaviour):

poc-service releases 1.0.9             poc-service releases 1.0.9
    │                                      │
POST-RELEASE updates poc-api:          POST-RELEASE:
  poc-service → 1.0.10-SNAPSHOT ❌       poc-api is PENDING → SKIP ✅
    │                                      │
PRE-RELEASE for poc-api:              PRE-RELEASE for poc-api:
  sees poc-service:1.0.10-SNAPSHOT      sees poc-service:1.0.9-SNAPSHOT
  strips -SNAPSHOT → 1.0.10             strips -SNAPSHOT → 1.0.9
  updates: poc-service:1.0.10           updates: poc-service:1.0.9
    │                                      │
poc-api build:                         poc-api build:
  needs poc-service:1.0.10              needs poc-service:1.0.9
  DOESN'T EXIST ❌                      EXISTS (just released) ✅
  BUILD FAILURE                          BUILD SUCCESS
```

---

### 6.6 Maven Invoker — Child Process Configuration

```java
InvocationRequest request = new DefaultInvocationRequest();

request.setBaseDirectory(directory);       // run in poc-service/
request.setGoals(List.of("unleash:perform"));

request.setBatchMode(true);
//  ↑ equivalent to mvn -B
//  without this: calculateVersions prompts "Please specify release version:"
//  child has no terminal → hangs forever

request.setLocalRepositoryDirectory(new File(localRepoPath));
//  ↑ child is a fresh JVM — doesn't know about local .m2
//  without this: tries to download unleash-submodule-extension
//  from Maven Central → not there → BUILD FAILURE

request.addShellEnvironment("UNLEASH_ORCHESTRATED", "true");
//  ↑ env var (not Maven property) — reliably inherited by child JVM
//  prevents child from re-orchestrating → no infinite loop

request.setOutputHandler(line -> log.info("  [SUB] " + line));
//  ↑ streams child output into parent log
//  [SUB] prefix tells you the line came from a child process
```

---

## 7. Submodule pom.xml Configuration

### poc-util (entry point)

```xml
<plugin>
    <groupId>io.github.mavenplugins</groupId>
    <artifactId>unleash-maven-plugin</artifactId>
    <version>3.3.0</version>
    <dependencies>
        <dependency>
            <groupId>io.github.mavenplugins</groupId>
            <artifactId>unleash-scm-provider-git</artifactId>
            <version>3.3.0</version>
        </dependency>
        <!-- Our custom extension — must be installed locally first -->
        <dependency>
            <groupId>com.poc.unleash</groupId>
            <artifactId>unleash-submodule-extension</artifactId>
            <version>1.0.6</version>
        </dependency>
    </dependencies>
    <configuration>
        <tagNamePattern>@{project.artifactId}-@{project.version}</tagNamePattern>
        <scmMessagePrefix>[unleash-release]</scmMessagePrefix>
    </configuration>
</plugin>
```

### poc-service and poc-api (have sibling dependencies)

Same as above, with one extra flag:

```xml
<configuration>
    <tagNamePattern>@{project.artifactId}-@{project.version}</tagNamePattern>
    <scmMessagePrefix>[unleash-release]</scmMessagePrefix>
    <!--
        Forces dependency resolution from GitHub Packages — not from local .m2.
        Without this, poc-service would find poc-util:1.0.8 in local .m2
        and think it's fine — bypassing the remote registry entirely.
        With false, it fetches from GitHub Packages, ensuring the artifact
        was properly deployed and is available to other teams.
    -->
    <allowLocalReleaseArtifacts>false</allowLocalReleaseArtifacts>
</configuration>
```

---

## 8. How to Run

### Prerequisites

```bash
# Step 1 — build and install the extension to local .m2
cd unleash-submodule-extension
mvn clean install

# Step 2 — copy workflow file to each submodule
cp src/main/resources/submodule-workflow.wf ../multi-repo-poc/poc-util/submodule-workflow.wf
cp src/main/resources/submodule-workflow.wf ../multi-repo-poc/poc-service/submodule-workflow.wf
cp src/main/resources/submodule-workflow.wf ../multi-repo-poc/poc-api/submodule-workflow.wf
```

### ~/.m2/settings.xml Required Entries

```xml
<servers>
    <!-- JGit uses this to push tags and version bump commits -->
    <server>
        <id>github.com</id>
        <username>YOUR_GITHUB_USERNAME</username>
        <password>ghp_yourPersonalAccessToken</password>
    </server>
    <!-- Maven deploy uses this to upload jars to GitHub Packages -->
    <server>
        <id>github</id>
        <username>YOUR_GITHUB_USERNAME</username>
        <password>ghp_yourPersonalAccessToken</password>
    </server>
</servers>
```

> **Two server entries are required.** `github.com` is used by JGit for pushing commits and tags. `github` is the distribution repository ID in each pom's `<distributionManagement>` for Maven artifact deployment. Same credentials, different IDs.

### Trigger the Release

```bash
cd multi-repo-poc/poc-util

mvn unleash:perform \
  -Dworkflow=submodule-workflow.wf \
  -Dunleash.scmUsername=YOUR_USERNAME \
  -Dunleash.scmPassword=ghp_yourToken
```

---

## 9. Full Release Flow — End to End

```
┌─────────────────────────────────────────────────────────────────────┐
│  Developer: mvn unleash:perform from poc-util (1.0.8-SNAPSHOT)     │
└─────────────────────────────────────────────────────────────────────┘
                              │
                    workflow steps ①–⑳
                              │
          ┌───────────────────▼──────────────────────┐
          │   poc-util runs its own release first    │
          │   build → tag → install → deploy         │
          │   poc-util 1.0.8 ✅ → bumped 1.0.9-SNAP  │
          └───────────────────┬──────────────────────┘
                              │
                    step ㉑ orchestrateSubmodules
                              │
          ┌───────────────────▼──────────────────────┐
          │  reads .gitmodules                       │
          │  [poc-util(0), poc-service(1), poc-api(2)]│
          │  currentIndex = 0                        │
          └───────────────────┬──────────────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         │                   │                    │
    i=0 poc-util        i=1 poc-service       i=2 poc-api
         │                   │                    │
    SKIP (self)         ┌────▼────┐          ┌────▼────┐
                        │PRE-REL  │          │PRE-REL  │
                        │poc-util │          │poc-svc  │
                        │1.0.8-SN │          │1.0.9-SN │
                        │→ 1.0.8  │          │→ 1.0.9  │
                        │commit ✅│          │commit ✅│
                        └────┬────┘          └────┬────┘
                             │                    │
                        ┌────▼────┐          ┌────▼────┐
                        │RELEASE  │          │RELEASE  │
                        │child    │          │child    │
                        │Maven    │          │Maven    │
                        │1.0.9 ✅ │          │1.0.5 ✅ │
                        └────┬────┘          └────┬────┘
                             │                    │
                        ┌────▼────┐          ┌────▼────┐
                        │POST-REL │          │POST-REL │
                        │push     │          │push     │
                        │1.0.10-SN│          │1.0.6-SN │
                        │skip     │          │to others│
                        │poc-api  │          │         │
                        │(pending)│          │         │
                        └─────────┘          └─────────┘

══════════════════════════════════════════════════════════
  RESULT:
  poc-util    → 1.0.8 released  │ now on 1.0.9-SNAPSHOT
  poc-service → 1.0.9 released  │ now on 1.0.10-SNAPSHOT
  poc-api     → 1.0.5 released  │ now on 1.0.6-SNAPSHOT

  GitHub Packages: poc-util:1.0.8, poc-service:1.0.9, poc-api:1.0.5
  All poms updated for next cycle — zero manual steps ✅
══════════════════════════════════════════════════════════
```

---

## 10. Version Lifecycle — Fully Automated

```
CYCLE N — starting state                CYCLE N+1 — ready automatically

poc-util    1.0.8-SNAPSHOT              poc-util    1.0.9-SNAPSHOT
poc-service 1.0.9-SNAPSHOT              poc-service 1.0.10-SNAPSHOT
poc-api     1.0.5-SNAPSHOT              poc-api     1.0.6-SNAPSHOT
         │                                       │
         ▼                                       ▼
mvn unleash:perform                     mvn unleash:perform
         │                                       │
         ▼                                       ▼
  releases:                               releases:
  poc-util    1.0.8 ✅                    poc-util    1.0.9 ✅
  poc-service 1.0.9 ✅                    poc-service 1.0.10 ✅
  poc-api     1.0.5 ✅                    poc-api     1.0.6 ✅
         │                                       │
         ▼                                       ▼
  POST-RELEASE auto-updates:              POST-RELEASE auto-updates:
  poc-service pom:                        poc-service pom:
    poc-util → 1.0.9-SNAPSHOT               poc-util → 1.0.10-SNAPSHOT
  poc-api pom:                            poc-api pom:
    poc-service → 1.0.10-SNAPSHOT           poc-service → 1.0.11-SNAPSHOT
         │                                       │
         ▼                                       ▼
  READY FOR CYCLE N+1 ✅                  READY FOR CYCLE N+2 ✅
```

**No manual pom updates ever needed between cycles.**

---

## 11. Key Design Decisions

| Decision | Choice | Reason |
|---|---|---|
| **Loop prevention** | Environment variable `UNLEASH_ORCHESTRATED=true` | Maven properties (`-D`) are user properties — not JVM system properties. `System.getProperty()` cannot read them in a forked process. Env vars are reliably inherited. |
| **Self-skip comparison** | `getCanonicalPath()` | String comparison fails when `.gitmodules` uses `./poc-util` vs `poc-util`. Canonical path resolves all variations to one definitive absolute path. |
| **Upstream detection** | Index position in `.gitmodules` | Modules listed before the current one are dependencies — must not be re-released. Index order encodes the dependency direction. |
| **Pom update method** | String replacement (not DOM parsing) | DOM re-serialises the entire file — destroys formatting, comments, and indentation. String replacement changes only the one version string. |
| **SNAPSHOT check** | XPath on `<project><version>` only | `content.contains("-SNAPSHOT")` returns true even if only a dependency is a SNAPSHOT — causing false positives. XPath reads only the project's own version tag. |
| **Workflow step position** | `orchestrateSubmodules` last (step 20) | Child releases need the parent artifact deployed first. Placing it after `deployArtifacts` guarantees the jar exists in GitHub Packages before any child tries to compile against it. |
| **Child Maven discovery** | `setLocalRepositoryDirectory()` | The child Maven is a fresh JVM. Without this, it tries to download `unleash-submodule-extension` from Maven Central where it does not exist. |
| **Interactive prompt prevention** | `setBatchMode(true)` | The child has no terminal. Without batch mode, `calculateVersions` prompts for the release version and hangs forever. |
| **CDI discovery mode** | `annotated` (not `all`) | `all` mode discovers unleash's own internal steps a second time — causing duplicate step ID errors at startup. |
| **Pending module exclusion in POST-RELEASE** | Skip modules still pending | Pushing the next SNAPSHOT to a pending module causes PRE-RELEASE to compute a version that does not exist yet — build failure. |

---

## 12. Repository Links

| Resource | Link |
|---|---|
| Parent repo | https://github.com/tsjahangiri/multi-repo-poc |
| Custom extension | https://github.com/tsjahangiri/unleash-submodule-extension |
| Unleash Maven Plugin | https://github.com/mavenplugins/unleash-maven-plugin |
