# 自建题库

App 的「配置服务器/孩子ID」支持两种地址，`http://` 和 `https://` 都可以：

| 填写内容 | 方式 | 适合 |
|---|---|---|
| 以 `.json` 结尾的链接，如 `https://raw.githubusercontent.com/你/仓库/main/questions.json` | **题库链接**：App 下载整个文件，在电视上按年级 + 弱项优先选题 | 不想跑后台，题库放 GitHub / NAS 网页 / 网盘直链 |
| 服务器地址，如 `http://192.168.1.10:8000` | **后台**：调用 `/api/next` 拉题、`/api/submit` 回传 | 多台电视共享进度、想在后台统计 |

两种方式在网络不通时都会自动回退：题库链接会先用上次下载成功的缓存，再用内置题库。

## 题库格式

和内置题库 `app/src/main/assets/questions.json` 相同：

```json
{
  "questions": [
    {
      "id": "math_g5_u1_q1",
      "subject": "数学",
      "grade": 5,
      "edition": "人教版",
      "unit": "小数乘法",
      "knowledge": "小数乘小数",
      "stem": "0.25 × 0.4 = ?",
      "options": ["0.1", "1", "0.01", "0.4"],
      "answer": "0.1",
      "explanation": "25×4=100，共三位小数，得 0.1。"
    }
  ]
}
```

- 必填：`grade`（1–9）、`subject`、`stem`、`options`（2–4 个）、`answer`。
- `answer` 必须和某个选项**一字不差**，否则这道题会被跳过。
- `knowledge` 用于弱项优先，同一知识点的题写同一个名字。
- `unit`、`explanation`、`id` 可选。
- 文件编码用 UTF-8。

## 方式一：题库链接（无需后台）

1. 按上面的格式写好 `questions.json`。
2. 放到能直接下载的地方：
   - GitHub：把文件提交到仓库，直接填网页地址 `https://github.com/你/仓库/blob/main/questions.json` 也可以，App 会自动转成原始文件地址。
   - NAS：放进 Web Station / 网页共享目录，填 `http://NAS的IP/questions.json`。
3. 在电视设置页填入链接，孩子ID可不填，点「保存并测试」，会提示本年级可用题数。

修改题库后不用重启任何东西，下一轮出题时自动下载最新版。

## 方式二：自建后台（`server.py`）

只需要 Python 3，不用装依赖：

```bash
cd server
python3 server.py          # 默认端口 8000；python3 server.py 9000 换端口
```

- 题库：优先读 `server/questions.json`，没有则读仓库内置题库。
- 进度：按孩子ID保存在 `server/mastery.json`。
- 电视设置页填 `http://运行它的电脑或NAS的IP:8000` 和孩子ID。

NAS 上可用 Docker 常驻：

```bash
docker run -d --name kidslock-quiz --restart unless-stopped \
  -p 8000:8000 -v /volume1/kidslock/server:/app -w /app \
  python:3-alpine python server.py
```

### 接口说明（自己写后台时参考）

- `GET /api/next?child_id=&count=&grade=&edition=` → `{"questions": [...]}`。**返回几道孩子就答几道**，请按 `count` 控制数量。
- `POST /api/submit`（表单）`child_id=...&results=[{"knowledge":"...","correct":true}]` → 任意 200 响应。可不实现，失败不影响解锁。
- 超时：连接 4 秒，读取 6 秒，超时即回退内置题库。
