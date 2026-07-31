# AI 心理健康助手

基于 Spring Boot + Vue 3 的全栈 AI 心理健康助手，集成 StepPlan 大模型，提供 AI 心理咨询、情绪记录、知识科普等功能。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Spring Boot 3.x + Spring Security + MyBatis-Plus + Spring AI |
| 前端 | Vue 3 + Vite + Element Plus + Pinia |
| 数据库 | MySQL |
| AI 模型 | StepPlan step-3.5-flash（阶跃星辰） |

## 功能模块

### 用户端
- **AI 心理咨询**：与 AI 助手实时对话，流式回复，支持多会话管理
- **情绪记录**：记录每日心情，追踪情绪变化趋势
- **心理知识**：浏览科普文章，获取心理健康知识

### 管理端
- **数据概览**：用户活跃度、咨询统计等数据分析
- **知识管理**：发布和管理心理科普文章
- **咨询记录**：查看所有用户的 AI 咨询记录
- **情绪日志**：查看用户提交的情绪记录

## 项目结构

```
├── ai-springboot/                 # 后端（Spring Boot）
│   ├── src/main/java/com/example/aispringboot/
│   │   ├── controller/            # 控制器（用户、AI对话）
│   │   ├── service/               # 业务逻辑
│   │   ├── entity/                # 数据实体
│   │   ├── mapper/                # MyBatis-Plus Mapper
│   │   ├── DTO/                   # 请求/响应 DTO
│   │   ├── config/                # 配置（安全、JWT、ChatClient）
│   │   └── util/                  # 工具类（JWT、响应util）
│   └── src/main/resources/
│       └── application.yml        # 应用配置
│
└── 心理健康助手（前端源码）/ai-vue/  # 前端（Vue 3）
    └── src/
        ├── views/                 # 页面组件
        ├── api/                   # API 请求
        ├── router/                # 路由配置
        ├── stores/                # 状态管理
        ├── utils/                 # 工具（axios、请求拦截）
        └── components/            # 公共组件
```

## 环境要求

- JDK 17+
- MySQL 8.0+
- Node.js 16+
- Maven 3.8+

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/wwwvc1/ai-mental-assistant.git
cd ai-mental-assistant
```

### 2. 初始化数据库

在 MySQL 中执行 `mental_health_assistant.sql`：

```bash
mysql -u root -p < mental_health_assistant.sql
```

### 3. 配置后端

编辑 `ai-springboot/src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mental_health_assistant
    username: root
    password: 你的密码

  ai:
    openai:
      api-key: 你的StepPlan API Key
      base-url: https://api.stepfun.ai/step_plan/v1
      chat:
        model: step-3.5-flash
```

### 4. 启动后端

```bash
cd ai-springboot
./mvnw.cmd spring-boot:run    # Windows
# 或
./mvnw spring-boot:run         # Mac/Linux
```

后端运行在 `http://localhost:8080`

### 5. 启动前端

```bash
cd "心理健康助手（前端源码）/ai-vue"
npm install
npm run dev
```

前端运行在 `http://localhost:5173`

### 6. 访问

- 用户端：http://localhost:5173
- 管理端：http://localhost:5173/back/dashboard

## 账号

| 角色 | 用户名 | 说明 |
|------|--------|------|
| 管理员 | admin | 管理后台、查看所有数据 |
| 普通用户 | 注册账号 | 使用 AI 咨询、情绪记录 |

## 安全说明

本项目为学习/演示用途，生产环境部署前请：

- 替换默认 JWT 密钥（`application.yml` 中的 `jwt.secret`）
- 配置真实的 AI API Key
- 开启 Spring Security 的接口鉴权
- 启用 HTTPS

## 许可证

MIT
