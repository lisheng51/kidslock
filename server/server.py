# 儿童锁屏 · 最小题库后台（仅用 Python 3 标准库，无需安装依赖）
# 用法：python3 server.py [端口]       默认端口 8000
# 题库：优先读同目录 questions.json；没有则读仓库内置题库 app/src/main/assets/questions.json
# 掌握度：按孩子ID保存在同目录 mastery.json，弱项优先出题
import json, random, os, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

DIR = os.path.dirname(os.path.abspath(__file__))
BANK = os.path.join(DIR, "questions.json")
if not os.path.exists(BANK):
    BANK = os.path.join(DIR, "..", "app", "src", "main", "assets", "questions.json")
QUESTIONS = json.load(open(BANK, encoding="utf-8"))["questions"]
MASTERY_FILE = os.path.join(DIR, "mastery.json")
mastery = json.load(open(MASTERY_FILE, encoding="utf-8")) if os.path.exists(MASTERY_FILE) else {}

class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        if u.path != "/api/next":
            return self._send(404, {"error": "not found"})
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        try:
            child, count = q.get("child_id", ""), max(1, int(q.get("count", 3)))
            grade, edition = int(q.get("grade", 0)), q.get("edition", "")
        except ValueError:
            return self._send(400, {"error": "bad params"})
        pool = [x for x in QUESTIONS if x.get("grade") == grade
                and (not edition or x.get("edition", edition) == edition)] or QUESTIONS
        m = mastery.get(child, {})
        # 弱项优先：掌握度低的排前面，同分随机
        pool = sorted(pool, key=lambda x: (m.get(x.get("knowledge", ""), 0.5), random.random()))
        self._send(200, {"questions": pool[:count]})

    def do_POST(self):
        if urlparse(self.path).path != "/api/submit":
            return self._send(404, {"error": "not found"})
        n = int(self.headers.get("Content-Length", 0))
        form = {k: v[0] for k, v in parse_qs(self.rfile.read(n).decode("utf-8")).items()}
        m = mastery.setdefault(form.get("child_id", ""), {})
        for r in json.loads(form.get("results", "[]")):
            k = r.get("knowledge", "")
            v = m.get(k, 0.5)
            m[k] = min(1.0, v + 0.15) if r.get("correct") else max(0.0, v - 0.2)
        json.dump(mastery, open(MASTERY_FILE, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        self._send(200, {"ok": True})

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    print("题库：%s（%d 题）" % (os.path.abspath(BANK), len(QUESTIONS)), flush=True)
    print("题库后台已启动：http://0.0.0.0:%d  （电视里填 http://本机IP:%d）" % (port, port), flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()
