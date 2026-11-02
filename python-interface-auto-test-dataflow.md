# 测试执行数据流走向详解

> 以 `username=admin, password=123456` 为例，追踪一次完整 pytest 运行中
> 每个文件的每一步操作、数据传递、HTTP 请求/响应、DB 查询。

---

## 一、启动阶段

```
用户输入
  → pytest --env=dev testcases/test_login.py -v
```

### `pytest.ini` 被加载

```ini
addopts = --html=reports/report.html --self-contained-html -v
testpaths = testcases
```

- pytest 读取配置，确定报告输出路径和测试文件扫描目录。

---

## 二、Fixture 加载阶段（在第一个用例执行前）

pytest 收集到 `test_login.py`，发现其中引用了 `client`、`auth_client`、`db` 三个 fixture，
按 `conftest.py` 中的定义逐一实例化。

### 2.1 `config/conftest.py` — `env_config` fixture（scope=session）

```python
def pytest_addoption(parser):
    parser.addoption("--env", action="store", default="test", ...)
```

- pytest 从命令行拿到 `--env=dev`
- `env_config` 调用 `_load_env("dev")`
- `_load_env` 读取 `config/.env.dev` 文件（`python-dotenv` 逐行解析）

**此时内存中的数据：**

```
env_config = {
    "BASE_URL":              "http://localhost:8080",
    "TEST_DB_URL":           "mysql+pymysql://root:123456@localhost:3306/mental_health_assistant_test",
    "TEST_USERNAME":          "admin",
    "TEST_PASSWORD":          "123456",
}
```

---

### 2.2 `config/conftest.py` — `client` fixture（scope=session）

```python
@pytest.fixture(scope="session")
def client(env_config) -> BaseClient:
    return BaseClient(env_config["BASE_URL"])
```

调用 `api/base.py` → `BaseClient.__init__`：

```python
class BaseClient:
    def __init__(self, base_url: str, token: Optional[str] = None):
        self.base_url = base_url.rstrip("/")     # "http://localhost:8080"
        self.headers = {"Content-Type": "application/json"}
        # token 为 None，不注入 token header
        # 所以 self.headers = {"Content-Type": "application/json"}
```

**此时内存中的数据：**

```
client = BaseClient(
    base_url = "http://localhost:8080",
    headers  = {"Content-Type": "application/json"}   # 没有 token
)
```

> 这个 client 是**未登录**的，专门给注册、登录等公开接口使用。

---

### 2.3 `config/conftest.py` — `db` fixture（scope=session）

```python
@pytest.fixture(scope="session")
def db(env_config) -> DBHelper:
    return DBHelper(env_config["TEST_DB_URL"])
```

调用 `utils/db_helper.py` → `DBHelper.__init__`：

```python
class DBHelper:
    def __init__(self, db_url: str):
        self.conn = pymysql.connect(
            host="localhost",
            port=3306,
            user="root",
            password="123456",
            database="mental_health_assistant_test",   # 独立测试库
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
        )
```

- 与 MySQL 建立物理连接（TCP 连接池）
- **注意**：这里连的是 `mental_health_assistant_test`，不是开发库 `mental_health_assistant`

**此时内存中的数据：**

```
db = DBHelper(conn = <pymysql.connections.Connection object>)
```

---

### 2.4 `config/conftest.py` — `auth_client` fixture（scope=session）

这是最复杂的一步，包含 **注册 → 登录 → 取 token → 构造已认证客户端** 三步。

#### 步骤 A：构造未登录的 BaseClient + UserAPI

```python
base = BaseClient(env_config["BASE_URL"])
# base.headers = {"Content-Type": "application/json"}

user_api = UserAPI(base)
```

**此时内存中的数据：**

```
base    = BaseClient(base_url="http://localhost:8080",
                     headers={"Content-Type": "application/json"})
user_api = UserAPI(client=base)
```

---

#### 步骤 B：调用 `ensure_test_user` — 注册测试账号

```python
user_api.ensure_test_user(
    username="admin",
    password="123456",
)
```

调用 `api/user_api.py` → `ensure_test_user`：

```python
def ensure_test_user(self, username, password, email=None):
    if email is None:
        email = f"{username}@test.local"    # "admin@test.local"
    result = self.register(username, password, email)
    if result.get("code") not in ("200", "BUSINESS_ERROR"):
        raise AssertionError(...)
```

调用 `register`：

```python
def register(self, username, password, email):
    resp = self.client.raw_request("POST", "/api/user/add", json={
        "username": username,
        "password": password,
        "email":    email,
    })
    return resp.json()
```

调用 `api/base.py` → `raw_request`：

```python
def raw_request(self, method, path, **kwargs):
    url = f"{self.base_url}{path}"
    # url = "http://localhost:8080/api/user/add"
    merged_headers = {**self.headers, **kwargs.pop("headers", {})}
    # merged_headers = {"Content-Type": "application/json"}
    return requests.request("POST", url, headers=merged_headers, json={
        "username": "admin",
        "password": "123456",
        "email":    "admin@test.local",
    })
```

**实际发出的 HTTP 请求：**

```
POST http://localhost:8080/api/user/add
Content-Type: application/json

{
  "username": "admin",
  "password": "123456",
  "email":    "admin@test.local"
}
```

---

**后端处理链路（`User.java` controller）：**

```
1. Spring Security 检查 PUBLIC_PATHS → /api/user/add 是公开的 → 放行
2. User.add() 方法接收 @RequestBody UserRegisterCommandDTO
3. Jakarta Validation 校验字段非空 → 通过
4. 调用 UserService.register()
5. UserService 检查用户名是否已存在
   → SELECT COUNT(*) FROM user WHERE username = 'admin'
   → 第一次运行：0 条（不存在）
6. BCryptPasswordEncoder.encode("123456") → 生成哈希值（如 "$2a$10$xxxxx..."）
7. INSERT INTO user (username, password, email, ...) VALUES ('admin', '$2a$10$...', 'admin@test.local', ...)
8. 返回 Result.ok(newUser) → code="200"
```

**HTTP 响应返回给 Python：**

```json
{
  "code": "200",
  "msg":  "操作成功",
  "data": {
    "id":      1,
    "username": "admin",
    "email":    "admin@test.local",
    "roletype": "1",
    ...
  }
}
```

回到 `ensure_test_user`：

```python
result = {
    "code": "200",
    "msg":  "操作成功",
    "data": { ... }
}
# result.get("code") == "200" → 在 ("200", "BUSINESS_ERROR") 内 → 不抛异常
```

> **第二次运行时**：`admin` 已存在，后端返回 `code="BUSINESS_ERROR", msg="用户名已存在"`，`ensure_test_user` 捕获后忽略，继续下一步。

---

#### 步骤 C：调用 `login` — 获取 token

```python
login_data = base.request("POST", "/api/user/login", json={
    "username": "admin",
    "password": "123456",
})
```

`request()` 内部调用 `raw_request()`，发送：

```
POST http://localhost:8080/api/user/login
Content-Type: application/json

{
  "username": "admin",
  "password": "123456"
}
```

**后端处理链路：**

```
1. Spring Security → PUBLIC_PATHS → 放行
2. User.login() 接收 UserLoginCommandDTO
3. 调用 UserService.login(username, password)
4. UserService 查询用户：
   SELECT * FROM user WHERE username = 'admin'
   → 找到记录，取出 BCrypt 哈希密码
5. BCryptPasswordEncoder.matches("123456", "$2a$10$xxxxx...")
   → true（明文密码与哈希匹配）
6. 生成 JWT token：
   JwtTokenUtil.generateToken(admin, "1", 86400)
   → "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInJvbGUi..."
7. 返回 Result.ok(LoginResponse(token, roletype, userinfo))
```

**HTTP 响应：**

```json
{
  "code": "200",
  "msg":  "操作成功",
  "data": {
    "token":    "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInJvbGUi...",
    "roletype": "1",
    "userinfo": {
      "id":       1,
      "username": "admin",
      "email":    "admin@test.local",
      ...
    }
  }
}
```

回到 `request()`：

```python
resp = raw_request(...)              # Response 对象
result = resp.json()                  # 解析上面的 JSON
assert result.get("code") == "200"    # "200" == "200" → PASS
return result.get("data")             # 返回 data 部分
```

**login_data 现在的值：**

```python
login_data = {
    "token":    "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInJvbGUi...",
    "roletype": "1",
    "userinfo": { "id": 1, "username": "admin", ... }
}
```

#### 步骤 D：提取 token，构造已认证客户端

```python
token = login_data["token"]
# token = "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInJvbGUi..."

return UserAPI(BaseClient(env_config["BASE_URL"], token=token))
```

`BaseClient.__init__` 被调用：

```python
self.headers = {"Content-Type": "application/json"}
if token:                                    # token 非 None
    self.headers["token"] = token
# 最终 self.headers = {
#     "Content-Type": "application/json",
#     "token":       "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInJvbGUi..."
# }
```

**此时内存中的最终状态：**

```
auth_client = UserAPI(
    client = BaseClient(
        base_url = "http://localhost:8080",
        headers  = {
            "Content-Type": "application/json",
            "token":       "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInJvbGUi..."
        }
    )
)
```

> 从此刻起，`auth_client` 发出的所有请求都自动携带 `token` header，
> 后端 `JwtAuthticationFilter` 会解析这个 token 并放行。

---

## 三、用例执行阶段

现在 pytest 开始执行 `test_login.py` 中的第一个用例。

### 3.1 正向用例：`test_login_success`

```python
# testcases/test_login.py

def test_login_success(client, auth_client):
    """
    fixtures:
      client     → BaseClient（无 token，来自步骤 2.2）
      auth_client → UserAPI（有 token，来自步骤 2.4）
    """

    # 使用无 token 的 client 调用登录（登录是公开接口，不需要 token）
    result = client.raw_request("POST", "/api/user/login", json={
        "username": "admin",
        "password": "123456",
    }).json()

    # 断言：HTTP 200 + BUSINESS_ERROR（这里其实是成功，code="200"）
    assert result["code"] == "200"
    assert "token" in result["data"]
```

**数据流：**

```
client (无 token)
  → raw_request("POST", "/api/user/login", json={...})
  → HTTP POST http://localhost:8080/api/user/login
     Headers: {Content-Type: application/json}
     Body: {"username": "admin", "password": "123456"}
  → 后端返回: {"code": "200", "msg": "操作成功", "data": {"token": "eyJ...", ...}}
  → result = 解析后的 dict
  → assert result["code"] == "200"        → PASS
  → assert "token" in result["data"]      → PASS
```

---

### 3.2 负面用例：`test_login_wrong_password`

```python
def test_login_wrong_password(client):
    """
    密码错误场景。
    注意：使用 raw_request，不走 request() 的断言，
    因为预期返回 BUSINESS_ERROR，不是 "200"。
    """

    result = client.raw_request("POST", "/api/user/login", json={
        "username": "admin",
        "password": "wrong_password",
    }).json()

    # 预期：业务错误（HTTP 200 + code="BUSINESS_ERROR"）
    assert result["code"] == "BUSINESS_ERROR"
    assert "密码" in result["msg"] or "password" in result["msg"].lower()
```

**数据流：**

```
client (无 token)
  → raw_request("POST", "/api/user/login", json={...})
  → HTTP POST http://localhost:8080/api/user/login
     Body: {"username": "admin", "password": "wrong_password"}
  → 后端处理：
     UserService.login("admin", "wrong_password")
     → 查询用户：SELECT * FROM user WHERE username = 'admin'
     → 找到用户，取出 BCrypt 哈希
     → BCryptPasswordEncoder.matches("wrong_password", "$2a$10$xxxxx...")
     → false
     → 抛出 BusionessException("密码错误")
     → GlobarExceptionHandler 捕获 → {"code": "BUSINESS_ERROR", "msg": "密码错误"}
  → HTTP 200（注意不是 401！业务错误也返回 200）
  → result = {"code": "BUSINESS_ERROR", "msg": "密码错误"}
  → assert result["code"] == "BUSINESS_ERROR"    → PASS
  → assert "密码" in result["msg"]               → PASS
```

> **关键点**：如果用 `request()` 而不是 `raw_request()`，会在 `assert result["code"] == "200"` 处直接抛异常，永远到不了后面的断言——这就是 F1 修正的意义。

---

### 3.3 负面用例：`test_login_user_not_found`

```python
def test_login_user_not_found(client):
    result = client.raw_request("POST", "/api/user/login", json={
        "username": "notexist_user",
        "password": "123456",
    }).json()

    assert result["code"] == "BUSINESS_ERROR"
```

**数据流：**

```
client (无 token)
  → raw_request("POST", "/api/user/login", json={...})
  → 后端：
     SELECT * FROM user WHERE username = 'notexist_user'
     → 0 条结果
     → 抛出 BusionessException("用户不存在")
     → {"code": "BUSINESS_ERROR", "msg": "用户不存在"}
  → assert result["code"] == "BUSINESS_ERROR"    → PASS
```

---

### 3.4 已认证接口：`test_get_current_user`

```python
def test_get_current_user(auth_client):
    """
    auth_client：已携带 token 的 UserAPI 实例
    """

    result = auth_client.get_current_user()
    # get_current_user 内部调用：
    #   self.client.request("GET", "/api/user/current")
    #   → raw_request("GET", "/api/user/current")
    #   → 返回 data 部分（因为 request() 内部断言了 code=="200"）
```

**数据流：**

```
auth_client (有 token)
  → client.request("GET", "/api/user/current")
    → raw_request("GET", "/api/user/current")
    → HTTP GET http://localhost:8080/api/user/current
       Headers: {
           Content-Type: application/json",
           token: "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInJvbGUi..."
       }
    → 后端处理：
       JwtAuthticationFilter.doFilterInternal()
       → 从 header 读取 "token"
       → JwtTokenUtil.validateToken("eyJ...") → true
       → 解析出 userId=1
       → filter.getUserService().getUserById(1)  ← 这里依赖 F7 修复后的注入
       → 如果未修复：userService 是 null → NPE → 500
       → 如果已修复：userService 正常注入 → 查询用户信息
       → 写入 Authentication 到 SecurityContext
       → chain.doFilter() 继续
       → UserController.current() 从 SecurityContext 取用户信息
       → Result.ok(userInfo)
    → HTTP 200 + {"code": "200", "data": {id:1, username:"admin", ...}}
    → request() 内部断言 code=="200" → PASS
    → return data = {id:1, username:"admin", ...}
  → result = {id:1, username:"admin", ...}
  → 用例断言 result["username"] == "admin"    → PASS
```

---

## 四、会话管理流程

### 4.1 `test_chat_session.py` — 创建会话

```python
def test_create_session(auth_client, db):
    """
    fixtures:
      auth_client → 已登录的 UserAPI
      db          → DBHelper（连接 mental_health_assistant_test）
    """

    # 1. 调用接口创建会话
    result = auth_client.client.request("POST",
        "/api/psychological-chat/session/start",
        json={"topic": "学习压力"}
    )

    session_id = result["sessionId"]
    # 假设后端返回: {"sessionId": "sessionId_1", ...}
    # session_id = "sessionId_1"
```

**数据流：**

```
auth_client (有 token)
  → HTTP POST /api/psychological-chat/session/start
     Headers: {token: "eyJ...", Content-Type: "application/json"}
     Body: {"topic": "学习压力"}

  → 后端：
     JwtAuthticationFilter → 校验 token → 放行
     PsychologicalChat.start()
     → 生成 sessionId = "sessionId_" + userId → "sessionId_1"
     → INSERT INTO consultation_session (id, user_id, topic, ...)
       → 注意：id 字段存的是 "sessionId_1"（字符串），不是纯数字
     → Result.ok({sessionId: "sessionId_1"})

  → result = {"sessionId": "sessionId_1"}
  → session_id = "sessionId_1"
```

#### 2. DB 断言

```python
    # 2. 验证数据库是否真的插入了记录
    session_id_pure = session_id.replace("sessionId_", "")  # "1"
    row = db.fetch_one(
        "SELECT * FROM consultation_session WHERE id = %s",
        (session_id,  # 注意：这里传的是 "sessionId_1"，因为数据库存的也是这个格式
    )
    )
    assert row is not None
    assert row["topic"] == "学习压力"
```

**数据流：**

```
db (DBHelper, 连接 mental_health_assistant_test)
  → fetch_one("SELECT * FROM consultation_session WHERE id = %s", ("sessionId_1",))
  → MySQL 执行: SELECT * FROM consultation_session WHERE id = 'sessionId_1'
  → 返回: {id: "sessionId_1", user_id: 1, topic: "学习压力", ...}
  → row = {id: "sessionId_1", user_id: 1, topic: "学习压力", ...}
  → assert row is not None    → PASS
  → assert row["topic"] == "学习压力"    → PASS
```

---

## 五、SSE 流式对话流程

### 5.1 `test_chat_stream.py` — 流式对话

```python
def test_stream_response(auth_client, db):
    # 先创建一个会话
    session = auth_client.client.request("POST",
        "/api/psychological-chat/session/start",
        json={"topic": "情绪管理"}
    )
    session_id = session["sessionId"]  # "sessionId_2"

    # 发送流式请求
    resp = auth_client.client.raw_request(
        "POST",
        "/api/psychological-chat/stream",
        json={
            "sessionId": session_id,       # "sessionId_2"
            "message":    "我最近总是失眠",
        },
        headers={"Accept": "text/event-stream"},
        stream=True,
        timeout=30,
    )
```

**数据流：**

```
auth_client (有 token)
  → raw_request("POST", "/api/psychological-chat/stream", json={...}, stream=True)
  → HTTP POST http://localhost:8080/api/psychological-chat/stream
     Headers: {
         token: "eyJ...",
         Content-Type: "application/json",
         Accept: "text/event-stream"
     }
     Body: {
         "sessionId": "sessionId_2",
         "message":    "我最近总是失眠"
     }

  → 后端：
     JwtAuthticationFilter → 放行
     PsychologicalChat.stream()
     → 保存用户消息到 consultation_message 表
     → 调用 AI 服务（Spring AI + StepFun API）
       → 构建 prompt（system prompt + 用户历史消息）
       → WebClient 请求 StepFun API（stream=true）
       → 逐 token 接收 AI 响应
       → 每收到一块，包装为 SSE 事件：
         data: {"code":"200","msg":"操作成功","data":{"message":"我","type":"normal"}}
         data: {"code":"200","msg":"操作成功","data":{"message":"理解","type":"normal"}}
         data: {"code":"200","msg":"操作成功","data":{"message":"你","type":"normal"}}
         ...
       → .delayElements(Duration.ofMillis(50)) 每块之间延迟 50ms
       → 最后发送: data: {}
     → 保存 AI 回复消息到 consultation_message 表
```

**Python 侧接收 SSE 流：**

```python
    from utils.sse_parser import collect_stream_text

    full_text = collect_stream_text(resp)
    # collect_stream_text 内部执行 parse_sse_events(resp)
```

`parse_sse_events` 的逐行处理：

```
响应体（raw bytes，按行分割）:
  data: {"code":"200","msg":"操作成功","data":{"message":"我","type":"normal"}}
  data: {"code":"200","msg":"操作成功","data":{"message":"理解","type":"normal"}}
  data: {"code":"200","msg":"操作成功","data":{"message":"你","type":"normal"}}
  ...
  data: {}

逐行处理：
  line 1: "data: {\"code\":\"200\",\"msg\":\"操作成功\",\"data\":{\"message\":\"我\",\"type\":\"normal\"}}"
    → startswith("data:") → True
    → raw = '{"code":"200","msg":"操作成功","data":{"message":"我","type":"normal"}}'
    → json.loads(raw) → {"code":"200","msg":"操作成功","data":{"message":"我","type":"normal"}}
    → outer.get("data") → {"message":"我","type":"normal"}
    → events.append({"message":"我","type":"normal"})

  line 2: "data: {..."理解"...}"
    → events.append({"message":"理解","type":"normal"})

  ...

  line N: "data: {}"
    → raw = "{}"
    → if not raw or raw == "{}": continue  → 跳过 done 事件
```

`collect_stream_text` 的拼接：

```python
events = [
    {"message": "我",   "type": "normal"},
    {"message": "理解", "type": "normal"},
    {"message": "你",   "type": "normal"},
    ...
]

texts = ["我", "理解", "你", ...]
full_text = "我理解你..."
```

**回到用例断言：**

```python
    assert len(full_text) > 0           # 非空 → PASS
    assert "失眠" in full_text or "睡眠" in full_text  # 话题相关 → PASS
```

---

### 5.2 流式对话后的 DB 断言

```python
    # 验证 AI 回复消息已落库
    msg_count = db.message_count_in_session(session_id)
    # session_id = "sessionId_2"
    # message_count_in_session 内部：
    #   pure_id = "sessionId_2".replace("sessionId_", "") → "2"
    #   SELECT COUNT(*) FROM consultation_message WHERE session_id = '2'

    assert msg_count >= 2  # 至少：1条用户消息 + 1条AI回复
```

**数据流：**

```
db (DBHelper)
  → message_count_in_session("sessionId_2")
  → pure_id = "2"
  → MySQL: SELECT COUNT(*) AS cnt FROM consultation_message WHERE session_id = '2'
  → 假设返回: {cnt: 2}（1条用户消息 + 1条AI消息）
  → msg_count = 2
  → assert 2 >= 2    → PASS
```

---

## 六、负面用例：认证拦截

### 6.1 `test_security.py` — 无 token 访问受保护接口

```python
def test_access_protected_without_token(client):
    """
    client 是没有 token 的 BaseClient
    """

    result = client.raw_request("GET", "/api/user/current")
    # 注意：用 raw_request，不走 request() 的成功断言
```

**数据流：**

```
client (无 token)
  → raw_request("GET", "/api/user/current")
  → HTTP GET http://localhost:8080/api/user/current
     Headers: {Content-Type: application/json"}
     # 没有 token header

  → 后端：
     JwtAuthticationFilter.doFilterInternal()
     → extractToken(request) → 从 header 读 "token"
     → header 中没有 "token" → return null
     → token 为空 → filter 拦截
     → ResponseUtil.error("401", "未登录或登录已过期")
     → chain.doFilter 不执行（controller 不执行）

  → HTTP 401
     Body: {"code": "401", "msg": "未登录或登录已过期"}
  → result = {"code": "401", "msg": "未登录或登录已过期"}
  → assert result["code"] == "401"    → PASS
```

---

### 6.2 `test_security.py` — 有效 token 访问（覆盖 F7 NPE bug）

```python
def test_valid_token_access_protected(auth_client):
    """
    auth_client 有有效 token。
    如果 JwtAuthticationFilter 未修复（userService=null）：
       → NPE → 500
    如果已修复：
       → 正常返回 200 + 用户信息
    """

    result = auth_client.client.request("GET", "/api/user/current")
    # request() 内部会断言 code=="200"，所以如果返回 500 会在这里失败
```

**数据流（已修复场景）：**

```
auth_client (有 token, token="eyJ...")
  → client.request("GET", "/api/user/current")
    → raw_request("GET", "/api/user/current")
    → HTTP GET http://localhost:8080/api/user/current
       Headers: {token: "eyJ...", Content-Type: "application/json"}

    → 后端：
       JwtAuthticationFilter（已修复，userService 已注入）
       → extractToken → "eyJ..."
       → validateToken("eyJ...") → true
       → 解析出 userId=1
       → filter.getUserService().getUserById(1) → 正常查询，返回 User 对象
       → 写入 Authentication 到 SecurityContextHolder
       → chain.doFilter() 继续
       → UserController.current()
       → 从 SecurityContext 取当前用户 → 返回 Result.ok(userInfo)

    → HTTP 200 + {"code": "200", "data": {id:1, username:"admin", ...}}
    → request() 内部：assert "200" == "200" → PASS
    → return data = {id:1, username:"admin", ...}

  → result = {id:1, username:"admin", ...}
  → assert result["username"] == "admin"    → PASS
```

**数据流（未修复场景，F7 bug）：**

```
auth_client (有 token)
  → HTTP GET /api/user/current (带 token)
  → 后端：
     JwtAuthticationFilter（未修复，userService = null）
     → validateToken → true
     → filter.getUserService() → null
     → null.getUserById(1) → NullPointerException!
     → 500 Internal Server Error
     → ResponseUtil.error("500", "服务器内部错误")
  → HTTP 500 + {"code": "500", "msg": "服务器内部错误"}
  → request() 内部：assert "500" == "200" → AssertionError!
  → 用例 FAIL，提示 "接口异常: 服务器内部错误"
```

> 这正是 S20 用例存在的意义：在 CI 中捕获这个 bug，确保 filter 注入修复后不再回归。

---

## 七、完整数据流总览图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          pytest --env=dev                                │
│                          testcases/test_login.py -v                      │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  pytest.ini 加载                                                          │
│  addopts = --html=reports/report.html -v                                  │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  config/conftest.py                                                       │
│                                                                           │
│  ┌─────────────┐                                                          │
│  │ env_config  │ ← 读取 .env.dev                                         │
│  │             │   {BASE_URL, TEST_DB_URL, TEST_USERNAME, TEST_PASSWORD} │
│  └─────────────┘                                                          │
│       │                                                                   │
│       ├── client ──→ BaseClient("http://localhost:8080")                 │
│       │                headers: {Content-Type: application/json}          │
│       │                (无 token)                                          │
│       │                                                                   │
│       ├── db ──────→ DBHelper("mental_health_assistant_test")            │
│       │                conn: <pymysql Connection>                         │
│       │                                                                   │
│       └── auth_client                                                        │
│            ├── BaseClient("http://localhost:8080")  ← 无 token           │
│            ├── ensure_test_user("admin", "123456")                       │
│            │    └── POST /api/user/add                                   │
│            │         Body: {"username":"admin","password":"123456",...}   │
│            │         ← 后端 BCrypt 加密密码 + INSERT INTO user           │
│            │         ← 返回 {"code":"200","data":{...}}                  │
│            │                                                                │
│            ├── POST /api/user/login                                       │
│            │    Body: {"username":"admin","password":"123456"}            │
│            │    ← 后端 BCrypt 校验 + JWT 生成                            │
│            │    ← 返回 {"code":"200","data":{"token":"eyJ...",...}}      │
│            │                                                                │
│            └── UserAPI(BaseClient("...", token="eyJ..."))                │
│                 headers: {Content-Type:..., token: "eyJ..."}             │
│                 (已认证)                                                  │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  testcases/test_login.py                                                  │
│                                                                           │
│  test_login_success(client):                                              │
│    client.raw_request("POST", "/api/user/login", json={                   │
│        "username": "admin", "password": "123456"                          │
│    })                                                                     │
│    → HTTP POST /api/user/login (无 token)                                │
│    ← {"code":"200","data":{"token":"eyJ...","roletype":"1",...}}         │
│    → assert code=="200"  PASS                                            │
│    → assert "token" in data  PASS                                        │
│                                                                           │
│  test_login_wrong_password(client):                                       │
│    client.raw_request("POST", "/api/user/login", json={                   │
│        "username": "admin", "password": "wrong_pwd"                       │
│    })                                                                     │
│    → HTTP POST /api/user/login (无 token)                                │
│    ← {"code":"BUSINESS_ERROR","msg":"密码错误"}                           │
│    → assert code=="BUSINESS_ERROR"  PASS                                 │
│    → assert "密码" in msg  PASS                                          │
│                                                                           │
│  test_login_user_not_found(client):                                       │
│    client.raw_request("POST", "/api/user/login", json={                   │
│        "username": "notexist", "password": "123456"                       │
│    })                                                                     │
│    → HTTP POST /api/user/login                                            │
│    ← {"code":"BUSINESS_ERROR","msg":"用户不存在"}                         │
│    → assert code=="BUSINESS_ERROR"  PASS                                 │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  testcases/test_chat_session.py                                           │
│                                                                           │
│  test_create_session(auth_client, db):                                    │
│    auth_client.client.request("POST", "/session/start",                   │
│        json={"topic": "学习压力"}                                         │
│    )                                                                      │
│    → HTTP POST /session/start (带 token)                                 │
│    ← {"code":"200","data":{"sessionId":"sessionId_2"}}                   │
│    → session_id = "sessionId_2"                                           │
│                                                                           │
│    db.fetch_one("SELECT * FROM consultation_session WHERE id=%s",         │
│        ("sessionId_2",))                                                   │
│    → MySQL: SELECT * FROM consultation_session WHERE id='sessionId_2'    │
│    ← {id:"sessionId_2", user_id:1, topic:"学习压力", ...}                │
│    → assert row is not None  PASS                                        │
│    → assert row["topic"] == "学习压力"  PASS                             │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  testcases/test_chat_stream.py                                            │
│                                                                           │
│  test_stream_response(auth_client, db):                                   │
│    session = auth_client.client.request("POST", "/session/start", ...)   │
│    session_id = "sessionId_3"                                             │
│                                                                           │
│    resp = auth_client.client.raw_request(                                 │
│        "POST", "/stream",                                                 │
│        json={"sessionId":"sessionId_3","message":"我最近总是失眠"},       │
│        headers={"Accept":"text/event-stream"}, stream=True, timeout=30   │
│    )                                                                      │
│    → HTTP POST /stream (带 token, stream=true)                           │
│       Accept: text/event-stream                                          │
│                                                                           │
│    ← SSE 流（text/event-stream）:                                        │
│       data: {"code":"200","msg":"操作成功","data":{"message":"我",...}}  │
│       data: {"code":"200","msg":"操作成功","data":{"message":"理解",...}}│
│       data: {"code":"200","msg":"操作成功","data":{"message":"你",...}}  │
│       ...  (每块延迟 50ms)                                               │
│       data: {}                                                           │
│                                                                           │
│    full_text = collect_stream_text(resp)                                  │
│    → parse_sse_events(resp):                                             │
│       逐行解析，跳过 data:{}                                              │
│       对每行 json.loads() 提取 outer["data"]                             │
│    → events = [{"message":"我"},{"message":"理解"},{"message":"你"},...]  │
│    → full_text = "我理解你最近..."                                         │
│                                                                           │
│    assert len(full_text) > 0          PASS                               │
│    assert "失眠" in full_text         PASS                               │
│                                                                           │
│    db.message_count_in_session("sessionId_3")                             │
│    → MySQL: SELECT COUNT(*) FROM consultation_message WHERE session_id=3│
│    ← {cnt: 2}  (1条用户 + 1条AI)                                         │
│    → assert cnt >= 2  PASS                                               │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  testcases/test_security.py                                               │
│                                                                           │
│  test_access_protected_without_token(client):                             │
│    client.raw_request("GET", "/api/user/current")                        │
│    → HTTP GET /api/user/current (无 token)                               │
│    ← {"code":"401","msg":"未登录或登录已过期"}                            │
│    → assert code=="401"  PASS                                            │
│                                                                           │
│  test_valid_token_access_protected(auth_client):                          │
│    auth_client.client.request("GET", "/api/user/current")                 │
│    → HTTP GET /api/user/current (带有效 token)                           │
│    ← {"code":"200","data":{"username":"admin",...}}                      │
│    → assert code=="200"  PASS                                            │
│    → assert data["username"]=="admin"  PASS                              │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  pytest 汇总结果 → reports/report.html                                    │
│  所有用例 PASS → 绿色通过                                                  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 八、核心数据在内存中的流转总结

```
                     ┌──────────────────────────────────────┐
                     │         config/.env.dev               │
                     │  BASE_URL / TEST_DB_URL / USERNAME    │
                     └──────────────────┬───────────────────┘
                                        │ 读取
                                        ▼
                     ┌──────────────────────────────────────┐
                     │         env_config (dict)             │
                     │  {"BASE_URL": "...", "TEST_USERNAME": │
                     │   "admin", "TEST_PASSWORD": "123456"} │
                     └──────────────────┬───────────────────┘
                                        │ 注入
                   ┌────────────────────┼────────────────────┐
                   │                    │                    │
                   ▼                    ▼                    ▼
          ┌──────────────┐   ┌──────────────────┐   ┌───────────────┐
          │  BaseClient   │   │   DBHelper        │   │  UserAPI      │
          │  (无 token)   │   │  (MySQL 连接)     │   │  (包装 API)   │
          └──────┬───────┘   └──────────────────┘   └───────┬───────┘
                 │                                           │
          client fixture                               auth_client fixture
                 │                                           │
                 │          ┌────────────────┐               │
                 │          │  注册 + 登录    │               │
                 │          │  拿到 token     │               │
                 │          └───────┬────────┘               │
                 │                  │                         │
                 │          ┌───────▼────────┐                │
                 │          │  BaseClient     │                │
                 │          │  (带 token)     │                │
                 │          └───────┬────────┘                │
                 │                  │ 传入                     │
                 │          ┌───────▼────────┐                │
                 │          │  UserAPI        │◄───────────────┘
                 │          │  (有 token)     │
                 │          └────────────────┘
                 │                  │
                 │    所有请求自动携带
                 │    headers["token"] = "eyJ..."
                 │
     ┌───────────┼───────────┐
     │           │           │
     ▼           ▼           ▼
  登录/注册    用户信息    聊天/SSE
  (无token)   (有token)  (有token)
```

---

## 九、一个用例从 0 到结束的完整时间线

```
时间轴  操作
──────────────────────────────────────────────────────────────────────────────
0ms     pytest --env=dev 被执行
1ms     pytest.ini 被加载
2ms     testcases/ 目录扫描，收集 test_login.py
5ms     conftest.py 开始实例化 fixtures
10ms    env_config: 读取 .env.dev → dict
12ms    client:     BaseClient.__init__() → 无 token 的客户端就绪
14ms    db:         DBHelper.__init__() → MySQL TCP 连接建立
16ms    auth_client:
18ms      → BaseClient("http://localhost:8080") 创建
20ms      → UserAPI.register("admin", "123456", "admin@test.local")
22ms      → HTTP POST /api/user/add
             ← 后端：BCrypt 加密 + INSERT（如果是第一次运行）
             ← {"code":"200","data":{...}}
             （如果是第二次运行：{"code":"BUSINESS_ERROR","msg":"用户名已存在"}）
25ms      → UserAPI.login_success("admin", "123456")
27ms      → HTTP POST /api/user/login
             ← 后端：BCrypt 校验 + JWT 生成
             ← {"code":"200","data":{"token":"eyJhbG...","roletype":"1",...}}
29ms      → token = login_data["token"] = "eyJhbG..."
30ms      → BaseClient("http://localhost:8080", token="eyJhbG...")
31ms      → headers = {"Content-Type":"application/json", "token":"eyJhbG..."}
33ms      → UserAPI(BaseClient(...)) → auth_client 就绪
──────────────────────────────────────────────────────────────────────────────
35ms     fixtures 全部就绪，开始执行第一个用例
──────────────────────────────────────────────────────────────────────────────
36ms     test_login_success(client):
40ms      → client.raw_request("POST", "/api/user/login", json={...})
42ms      → HTTP POST 发出（~1-5ms 网络）
47ms      ← 响应返回 {"code":"200","data":{"token":"eyJ...",...}}
48ms      → assert code=="200" → PASS
49ms      → assert "token" in data → PASS
──────────────────────────────────────────────────────────────────────────────
50ms     test_login_wrong_password(client):
53ms      → client.raw_request(...)
           ← {"code":"BUSINESS_ERROR","msg":"密码错误"}
55ms      → assert code=="BUSINESS_ERROR" → PASS
56ms      → assert "密码" in msg → PASS
──────────────────────────────────────────────────────────────────────────────
...      （中间用例类似，省略）
──────────────────────────────────────────────────────────────────────────────
500ms    test_stream_response(auth_client, db):
502ms     → 创建会话 → session_id = "sessionId_3"
510ms     → raw_request("POST", "/stream", json={...}, stream=True, timeout=30)
512ms     → HTTP POST 发出
           ← SSE 流开始到达（每条延迟 ~50ms）
           ← 第1块: {"message":"我"}
           ← 第2块: {"message":"理解"}
           ← 第3块: {"message":"你"}
           ← ...（总共约 10-30 块）
           ← data: {}（done 事件）
──────────────────────────────────────────────────────────────────────────────
（流式消费总耗时取决于 AI 响应长度，约 500ms - 3000ms）
──────────────────────────────────────────────────────────────────────────────
≈2000ms  → collect_stream_text → full_text = "我理解你最近总是失眠的情况..."
≈2001ms  → assert len(full_text) > 0 → PASS
≈2002ms  → assert "失眠" in full_text → PASS
≈2003ms  → db.message_count_in_session("sessionId_3")
           ← {cnt: 2}
≈2004ms  → assert cnt >= 2 → PASS
──────────────────────────────────────────────────────────────────────────────
≈2005ms  所有用例执行完毕，pytest 生成 HTML 报告
≈2010ms  reports/report.html 写入完成
≈2010ms  进程退出，MySQL 连接关闭（DBHelper 未被显式 close，依赖 GC）
```

---

## 十、关键数据对照表

| 时刻 | 变量名 | 所在文件/类 | 值 | 说明 |
|------|--------|------------|---|------|
| 1 | `env_config` | conftest.py | `{"BASE_URL": "http://localhost:8080", "TEST_USERNAME": "admin", ...}` | 环境配置 dict |
| 2 | `client` | conftest.py → BaseClient | `headers = {"Content-Type": "application/json"}` | 无 token，给公开接口用 |
| 3 | `db` | conftest.py → DBHelper | `conn → mental_health_assistant_test` | MySQL 连接 |
| 4 | `user_api` | conftest.py → UserAPI | `client = client（无 token）` | 用于注册/登录 |
| 5 | `register result` | user_api.py | `{"code": "200", "data": {id: 1, username: "admin", ...}}` | 注册成功 |
| 6 | `login_data` | conftest.py | `{"token": "eyJhbG...", "roletype": "1", "userinfo": {...}}` | 登录响应 data 部分 |
| 7 | `token` | conftest.py | `"eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEs..."` | JWT 字符串 |
| 8 | `auth_client.headers` | conftest.py → BaseClient | `{"Content-Type": "...", "token": "eyJ..."}` | 带 token 的请求头 |
| 9 | `session_id` | test_chat_session.py | `"sessionId_2"` | 后端生成的会话 ID |
| 10 | `sse_events` | sse_parser.py | `[{"message": "我"}, {"message": "理解"}, ...]` | 解析后的 SSE 事件 |
| 11 | `full_text` | test_chat_stream.py | `"我理解你最近总是失眠的情况..."` | AI 回复完整文本 |
| 12 | `msg_count` | db_helper.py | `2` | 消息表中的记录数 |

---

## 十一、文件操作汇总

| 文件 | 操作 | 何时 |
|------|------|------|
| `pytest.ini` | 读取配置 | pytest 启动时 |
| `config/.env.dev` | 读取环境变量 | conftest.py `_load_env()` |
| `config/conftest.py` | 定义 4 个 fixture，操作 HTTP 和 MySQL | pytest 收集用例后、执行用例前 |
| `api/base.py` | `__init__` 构造客户端、`raw_request` 发请求 | 每次 API 调用 |
| `api/user_api.py` | `register`/`login`/`ensure_test_user` | conftest.py 初始化阶段 |
| `utils/sse_parser.py` | `parse_sse_events` 解析流 | test_chat_stream.py 执行时 |
| `utils/db_helper.py` | `fetch_one`/`execute` 操作 MySQL | test_chat_session.py / test_chat_stream.py |
| `utils/assert_helpers.py` | `assert_success`/`assert_business_error` | 各 testcase 中 |
| `testcases/test_login.py` | 执行 7 个登录/注册用例 | 测试阶段 |
| `testcases/test_chat_session.py` | 执行会话 CRUD + DB 断言 | 测试阶段 |
| `testcases/test_chat_stream.py` | 执行 SSE 流式 + DB 断言 | 测试阶段 |
| `testcases/test_security.py` | 执行认证拦截用例 | 测试阶段 |
