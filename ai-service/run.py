"""PyCharm 启动入口。

右键 Run 'run' 即可启动，不需要记 uvicorn 命令。
等价于：
    uvicorn app.main:app --host 0.0.0.0 --port 8000
"""
# 本地开发启动入口：等价于 uvicorn app.main:app --host 0.0.0.0 --port 8000；
# 端口 8000 对应 Java 侧配置的 AI 服务地址，部署时由 Dockerfile/uvicorn 命令启动。
import uvicorn

if __name__ == "__main__":
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=False)
