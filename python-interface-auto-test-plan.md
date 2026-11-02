# Python 接口自动化测试方案（最终修订版）

## Context

原始方案存在多处与真实代码不符的设计缺陷，已在三条反馈中被逐一指出。本方案逐条修正并补充遗漏场景，确保方案与项目真实代码一致。

---

## 已知修正清单（来自反馈）

| 编号 | 问题 | 修正方案 |
|------|------|----------|
| F1 | `BaseClient.request()` 硬断言 `code == 200`，负面用例无法执行 | 拆分为 `request()`（校验成功）和 `raw_request()`（无校验） |
| F2 | token 取值路径错误：`data["token"]` → 真实路径 `data["data"]["token"]` | conftest.py 修正 |
| F3 | `code` 字段是字符串 `"200"`，不是整数 `200` | 所有断言改为字符串比较 |
| F4 | 登录/注册是公开接口，BaseClient 不应强制注入 token | BaseClient 接受可选 token |
| F5 | springdoc v2 不支持 Boot 4，需确认 v3 兼容版本 | 后端 pom 查最新兼容版本 |
| F6 | `pytest.ini` 的 `env = dev:xxx` 语法不存在 | 改用 python-dotenv + conftest 自定义 `--env` 参数 |
| F7 | JwtAuthticationFilter 手动 `new` 实例化，@Resource UserService 不注入 → NPE 500 | **负面用例必测**，验证带有效 token 访问受保护接口 |
| F8 | SSE data 事件内嵌一层 Result JSON，需二次 `json.loads()` | sse_parser.py 增加二次解析 |
| F9 | sessionId 传参与数据库格式不一致（`sessionId_1` vs 纯数字） | 测试时做格式转换 |
| F10 | 注册密码用 BCrypt 加密，SQL 直插需哈希值 | 测试账号初始化走注册接口 |

---

## 目录结构

```
api-auto-test/
├── config/
│   ├── .env.dev                  # 开发环境变量
│   ├── .env.test                 # 测试环境变量
│   └── conftest.py               # pytest 全局 fixtures（修正后）
├── api/
│   ├── __init__.py
│   ├── base.py                   # BaseClient（拆分 request/raw_request）
│   ├── user_api.py               # 用户接口封装
│   └── chat_api.py               # 聊天接口封装（含 SSE）
├── testcases/
│   ├── __init__.py
│   ├── test_login.py             # 登录/注册（含负面用例）
│   ├── test_user.py              # 用户信息接口
│   ├── test_chat_session.py      # 会话 CRUD + DB 断言
│   ├── test_chat_stream.py       # SSE 流式对话
│   ├── test_chat_emotion.py      # 情绪分析（含非法 sessionId）
│   └── test_security.py          # 认证链路负面用例（覆盖 F7 NPE bug）
├── utils/
│   ├── __init__.py
│   ├── db_helper.py              # MySQL 测试库操作（独立测试库）
│   ├── assert_helpers.py         # Result<T> 断言封装（区分两种错误形态）
│   └── sse_parser.py             # SSE 解析（支持嵌套 Result）
├── data/
│   ├── test_users.yaml           # 测试账号模板
│   └── chat_scenes.yaml          # 聊天场景数据
├── reports/                      # HTML 报告输出
├── requirements.txt
├── pytest.ini
└── README.md
```

---

## 关键文件实现

### 1. `api/base.py` — 拆分请求方法

```python
import requests
from typing import Optional, Any


class BaseClient:
    """
    统一 HTTP 客户端。
    - token 放在自定义 header "token"（非标准 Bearer）
    - request()：校验成功路径（code == "200"）
    - raw_request()：无校验，返回完整 Response，供负面用例使用
    """

    def __init__(self, base_url: str, token: Optional[str] = None):
        self.base_url = base_url.rstrip("/")
        self.headers = {"Content-Type": "application/json"}
        # F4 修正：token 可选，公开接口调用时不传
        if token:
            self.headers["token"] = token

    def request(self, method: str, path: str, **kwargs) -> Any:
        """
        发送请求并断言成功。
        负面用例请使用 raw_request()。
        """
        resp = self.raw_request(method, path, **kwargs)
        result = resp.json()
        # F3 修正：code 是字符串 "200"，不是整数
        assert result.get("code") == "200", f"接口异常: {result.get('msg')}"
        return result.get("data")

    def raw_request(self, method: str, path: str, **kwargs) -> requests.Response:
        """底层请求，不做任何校验，返回原始 Response"""
        url = f"{self.base_url}{path}"
        merged_headers = {**self.headers, **kwargs.pop("headers", {})}
        return requests.request(method, url, headers=merged_headers, **kwargs)
```

### 2. `config/conftest.py` — 修正 fixture 和取值

```python
import os
import pytest
from dotenv import dotenv_values
from api.base import BaseClient
from api.user_api import UserAPI
from utils.db_helper import DBHelper


def _load_env(env_name: str) -> dict:
    """加载 .env.{env_name} 文件"""
    env_file = f".env.{env_name}"
    if not os.path.exists(env_file):
        raise FileNotFoundError(f"环境配置文件不存在: {env_file}")
    return dotenv_values(env_file)


def pytest_addoption(parser):
    """注册 --env 命令行参数"""
    parser.addoption(
        "--env", action="store", default="test",
        help="测试环境: dev / test",
    )


@pytest.fixture(scope="session")
def env_config(request):
    """会话级环境配置"""
    env_name = request.config.getoption("--env")
    return _load_env(env_name)


@pytest.fixture(scope="session")
def client(env_config) -> BaseClient:
    """未登录的原始客户端，用于公开接口"""
    return BaseClient(env_config["BASE_URL"])


@pytest.fixture(scope="session")
def auth_client(env_config) -> UserAPI:
    """
    已登录的高层 API 客户端。
    整轮测试只登录一次，token 共享。
    """
    base = BaseClient(env_config["BASE_URL"])
    user_api = UserAPI(base)

    # F10 修正：用注册接口创建测试账号（BCrypt 由后端处理）
    user_api.ensure_test_user(
        username=env_config["TEST_USERNAME"],
        password=env_config["TEST_PASSWORD"],
    )

    # F2 修正：token 在 result["data"]["token"]，不是 data["token"]
    login_data = base.request("POST", "/api/user/login", json={
        "username": env_config["TEST_USERNAME"],
        "password": env_config["TEST_PASSWORD"],
    })
    token = login_data["token"]

    return UserAPI(BaseClient(env_config["BASE_URL"], token=token))


@pytest.fixture(scope="session")
def db(env_config) -> DBHelper:
    """会话级数据库连接（独立测试库）"""
    return DBHelper(env_config["TEST_DB_URL"])
```

### 3. `api/user_api.py`

```python
from api.base import BaseClient


class UserAPI:
    """用户相关接口封装"""

    def __init__(self, client: BaseClient):
        self.client = client

    def login(self, username: str, password: str) -> dict:
        """登录，返回完整 Result（含 code/msg/data），供负面用例校验"""
        resp = self.client.raw_request("POST", "/api/user/login", json={
            "username": username,
            "password": password,
        })
        return resp.json()

    def register(self, username: str, password: str, email: str) -> dict:
        """注册，返回完整 Result"""
        resp = self.client.raw_request("POST", "/api/user/add", json={
            "username": username,
            "password": password,
            "email": email,
        })
        return resp.json()

    def get_current_user(self) -> dict:
        """获取当前用户信息"""
        return self.client.request("GET", "/api/user/current")

    def logout(self) -> None:
        """登出"""
        self.client.request("POST", "/api/user/logout")

    def ensure_test_user(self, username: str, password: str, email: str = None):
        """
        确保测试用户存在：先尝试注册，已存在则忽略。
        使用注册接口，密码由后端 BCrypt 加密。
        """
        if email is None:
            email = f"{username}@test.local"
        result = self.register(username, password, email)
        # 重复注册返回 BUSINESS_ERROR，忽略；其他错误抛出
        if result.get("code") not in ("200", "BUSINESS_ERROR"):
            raise AssertionError(f"注册测试用户失败: {result}")
```

### 4. `utils/sse_parser.py` — 支持嵌套 Result

```python
import json


def parse_sse_events(response) -> list[dict]:
    """
    解析 SSE (text/event-stream) 响应。
    本项目 data 事件形如：
        data: {"code":"200","msg":"操作成功","data":{"message":"...","type":"normal"}}
    最后有 done 事件：data:{}
    返回所有有效事件的 data 字段（已二次解析为 dict）。
    """
    events = []
    for line in response.iter_lines(decode_unicode=True):
        if line.startswith("data:"):
            raw = line[5:].strip()
            if not raw or raw == "{}":
                continue
            # F8 修正：data 内嵌 Result JSON，需二次解析
            outer = json.loads(raw)
            events.append(outer.get("data"))
    return events


def collect_stream_text(response) -> str:
    """从 SSE 流中拼接所有 message 文本"""
    events = parse_sse_events(response)
    return "".join(
        event["message"] for event in events if event and "message" in event
    )
```

### 5. `utils/db_helper.py`

```python
import pymysql


class DBHelper:
    """
    数据库辅助类。
    使用独立测试库 mental_health_assistant_test，不影响开发数据。
    外键约束已配置 ON DELETE CASCADE。
    """

    def __init__(self, db_url: str):
        self.conn = pymysql.connect(
            host="localhost",
            port=3306,
            user="root",
            password="123456",
            database="mental_health_assistant_test",
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
        )

    def fetch_one(self, sql: str, params: tuple = None) -> dict:
        with self.conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchone()

    def fetch_all(self, sql: str, params: tuple = None) -> list[dict]:
        with self.conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchall()

    def execute(self, sql: str, params: tuple = None):
        with self.conn.cursor() as cur:
            cur.execute(sql, params)
        self.conn.commit()

    def user_count(self, username: str = None) -> int:
        if username:
            row = self.fetch_one(
                "SELECT COUNT(*) AS cnt FROM user WHERE username = %s", (username,)
            )
        else:
            row = self.fetch_one("SELECT COUNT(*) AS cnt FROM user")
        return row["cnt"]

    def session_exists(self, session_id: str) -> bool:
        """F9 修正：sessionId 传参可能是 sessionId_1 格式，数据库存纯数字"""
        pure_id = session_id.replace("sessionId_", "") if "sessionId_" in session_id else session_id
        row = self.fetch_one(
            "SELECT COUNT(*) AS cnt FROM consultation_session WHERE id = %s",
            (pure_id,),
        )
        return row["cnt"] > 0

    def message_count_in_session(self, session_id: str) -> int:
        pure_id = session_id.replace("sessionId_", "") if "sessionId_" in session_id else session_id
        row = self.fetch_one(
            "SELECT COUNT(*) AS cnt FROM consultation_message WHERE session_id = %s",
            (pure_id,),
        )
        return row["cnt"]

    def clear_test_data(self):
        """每轮测试前清理（外键 CASCADE，只需删顶层）"""
        self.execute("DELETE FROM consultation_message WHERE 1=1")
        self.execute("DELETE FROM consultation_session WHERE 1=1")
        self.execute("DELETE FROM user WHERE username LIKE 'testuser%' OR email LIKE '%@test.local'")

    def close(self):
        self.conn.close()
```

### 6. `utils/assert_helpers.py` — 区分两种错误形态

```python
"""
针对本项目 Result<T> 响应结构的断言封装。
区分两种错误形态：
  - 业务错误：HTTP 200 + code="BUSINESS_ERROR"
  - 认证拦截：HTTP 401/403 + Result JSON（ResponseUtil 写回）
"""


def assert_success(result: dict, data_key: str = None):
    """断言 Result 为成功"""
    assert result.get("code") == "200", f"业务异常: {result.get('msg')}"
    if data_key:
        assert data_key in result.get("data", {}), f"响应 data 缺少字段: {data_key}"


def assert_business_error(result: dict, expected_msg: str = None):
    """断言 Result 为业务错误（HTTP 200）"""
    assert result.get("code") == "BUSINESS_ERROR", (
        f"期望 BUSINESS_ERROR，实际: {result.get('code')} - {result.get('msg')}"
    )
    if expected_msg:
        assert expected_msg in result.get("msg", ""), (
            f"错误消息不匹配: 期望包含 '{expected_msg}'，实际 '{result.get('msg')}'"
        )


def assert_unauthorized(result: dict):
    """断言认证失败（HTTP 401/403）"""
    assert result.get("code") in ("401", "403"), f"期望认证失败，实际: {result}"
```

### 7. `pytest.ini`

```ini
[pytest]
addopts = --html=reports/report.html --self-contained-html -v
testpaths = testcases
python_files = test_*.py
python_classes = Test*
python_functions = test_*
```

用法：`pytest --env=dev` 或 `pytest --env=test`

### 8. `.env.test` 示例

```env
BASE_URL=http://localhost:8080
TEST_DB_URL=mysql+pymysql://root:123456@localhost:3306/mental_health_assistant_test
TEST_USERNAME=testuser01
TEST_PASSWORD=Test@123456
```

---

## 测试场景完整清单

### 登录/注册（`test_login.py`）

| # | 场景 | 预期 code | 关键断言 |
|---|------|-----------|----------|
| S1 | 正确账号密码登录 | "200" | data 含 token 字段 |
| S2 | 密码错误 | "BUSINESS_ERROR" | msg 含密码相关提示 |
| S3 | 用户名不存在 | "BUSINESS_ERROR" | msg 含用户不存在提示 |
| S4 | 正常注册新用户 | "200" | data 含新用户信息 |
| S5 | 重复注册同名 | "BUSINESS_ERROR" | msg 含用户名已存在 |
| S6 | 重复注册同邮箱 | "BUSINESS_ERROR" | msg 含邮箱已被注册 |
| S7 | 空用户名注册 | "BUSINESS_ERROR" | 验证 Jakarta Validation 拦截 |

### 用户信息（`test_user.py`）

| # | 场景 | 预期 |
|---|------|------|
| S8 | 带 token 获取当前用户 | "200" + 用户信息完整 |
| S9 | 登出 | "200" |

### 会话管理（`test_chat_session.py`）

| # | 场景 | 断言 |
|---|------|------|
| S10 | 创建会话 | "200" + DB 断言 session 落库 |
| S11 | 查询会话列表 | "200" + 分页数据 |
| S12 | 查询会话消息 | "200" + 消息列表非空（创建后有用户消息） |

### SSE 流式对话（`test_chat_stream.py`）

| # | 场景 | 断言 |
|---|------|------|
| S13 | 正常流式对话 | SSE 事件非空 + collect_stream_text 非空 |
| S14 | 不存在的 sessionId 流式 | 返回错误事件或空流 |
| S15 | AI 返回空内容保护 | 流中不包含空消息 |

### 情绪分析（`test_chat_emotion.py`）

| # | 场景 | 断言 |
|---|------|------|
| S16 | 正常查询情绪分析 | "200" + data 含情绪数据 |
| S17 | 非法 sessionId | "BUSINESS_ERROR" 或 400 |

### 认证安全（`test_security.py`）— 覆盖 F7 NPE bug

| # | 场景 | 预期 |
|---|------|------|
| S18 | 无 token 访问受保护接口 | HTTP 401/403 |
| S19 | 伪造 token 访问 | HTTP 401/403 |
| S20 | 有效 token 访问非公开接口 | "200"（不是 500 NPE，验证 filter 注入修复） |

---

## 后端配合改动清单

### P0：必须改（不动这些，自动化跑不起来）

**1. 修复 JwtAuthticationFilter NPE（F7）**

`config/SecurityConfig.java:52`：
```java
// 问题代码：手动 new 实例，@Resource 不注入
JwtAuthticationFilter filter = new JwtAuthticationFilter();

// 修复：改为 @Bean 方法，让 Spring 管理依赖注入
@Bean
public JwtAuthticationFilter jwtAuthenticationFilter(UserService userService) {
    JwtAuthticationFilter filter = new JwtAuthticationFilter();
    filter.setUserService(userService);  // 需加 setter
    return filter;
}
```

同时 `JwtAuthticationFilter` 中 `UserService` 字段加 `@Autowired` 或 setter。

**2. 确认 SecurityConfig 公开接口配置**

`SecurityConfig` 中 `PUBLIC_PATHS` 需包含 `/api/user/login` 和 `/api/user/add`，否则登录/注册用例全部 403。

### P1：建议加（自动化价值最高）

**3. 加 springdoc-openapi**

`pom.xml` 加依赖：
```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <!-- 需确认 Boot 4.0.7 兼容的最新版本 -->
    <version>2.8.5</version>
</dependency>
```

控制器加注解：
```java
@Operation(summary = "用户登录")
@PostMapping("/api/user/login")
public Result<UserLoginResponseDTO> login(@Valid @RequestBody UserLoginCommandDTO dto) { ... }
```

> **注意**：Spring Boot 4.0.7 对应 springdoc 兼容版本需确认。如无正式支持，用手写 OpenAPI JSON 过渡。

**4. SSE 接口 OpenAPI 标注**

```java
@GetMapping(value = "/api/psychological-chat/stream", produces = "text/event-stream")
@Operation(summary = "流式对话", produces = "text/event-stream")
```

---

## 执行方式

```bash
# 开发环境
pytest --env=dev -v

# 测试环境
pytest --env=test -v

# 单文件
pytest testcases/test_login.py --env=dev -v

# 单用例
pytest testcases/test_login.py::test_login_success --env=dev -v
```

---

## 验证清单

| 验证项 | 方法 |
|--------|------|
| 框架可运行 | `pytest --env=dev --collect-only` 能发现所有用例 |
| 登录链路正常 | `test_login.py::test_login_success` PASS |
| 负面用例正常 | `test_login.py::test_login_fail_wrong_password` 正确断言 BUSINESS_ERROR |
| NPE bug 被捕获 | `test_security.py::test_valid_token_access_protected` 断言不返回 500 |
| SSE 流式正常 | `test_chat_stream.py::test_stream_response` 能收集到非空文本 |
| DB 断言正常 | `test_chat_session.py::test_create_session_persists` 验证 session 落库 |
| HTML 报告生成 | `reports/report.html` 存在且可打开 |

---

## 风险与注意事项

1. **AI 接口非确定性**：`/stream` 返回内容每次不同，断言应检查**结构**（非空、含 message 字段），不应断言具体文本。
2. **BCrypt 性能**：BCrypt 编码慢（~100ms/次），`ensure_test_user` 在 session 级只执行一次。
3. **测试库隔离**：必须用 `mental_health_assistant_test` 独立库，`ON DELETE CASCADE` 处理外键，每轮跑前 TRUNCATE 脏表。
4. **SSE 超时**：后端 `.delayElements(50ms)`，单条消息流约 500-2000ms，`requests` 超时设 30s。
5. **Boot 4 + springdoc**：如无正式兼容版本，用手写 OpenAPI JSON 过渡，先保证自动化能跑。
