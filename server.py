
import sqlite3, json, time
from aiohttp import web, WSMsgType

DB = "chat.db"

def db():
    con = sqlite3.connect(DB)
    con.execute("CREATE TABLE IF NOT EXISTS users(login TEXT PRIMARY KEY, password TEXT)")
    con.execute("""CREATE TABLE IF NOT EXISTS messages(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        sender TEXT, receiver TEXT DEFAULT '',
        text TEXT, ts REAL, read INTEGER DEFAULT 0)""")
    con.commit()
    return con

def unread_counts(con, user):
    rows = con.execute(
        "SELECT sender, COUNT(*) FROM messages WHERE receiver=? AND read=0 GROUP BY sender",
        (user,)).fetchall()
    return {r[0]: r[1] for r in rows}

CONNS = {}  # login -> websocket

async def broadcast_online():
    users = sorted(CONNS.keys())
    data = json.dumps({"type": "online", "users": users})
    for ws in list(CONNS.values()):
        try: await ws.send_str(data)
        except Exception: pass

async def login_h(request):
    data = await request.json()
    login = str(data.get("login", "")).strip()
    password = str(data.get("password", ""))
    if not login.isdigit() or len(login) < 3:
        return web.json_response({"ok": False, "error": "Логин — только цифры, минимум 3"}, status=400)
    if len(password) < 4:
        return web.json_response({"ok": False, "error": "Пароль — минимум 4 символа"}, status=400)
    con = db()
    row = con.execute("SELECT password FROM users WHERE login=?", (login,)).fetchone()
    if row is None:
        con.execute("INSERT INTO users(login, password) VALUES(?,?)", (login, password))
        con.commit()
    elif row[0] != password:
        con.close()
        return web.json_response({"ok": False, "error": "Неверный пароль для этого логина"}, status=403)
    con.close()
    return web.json_response({"ok": True})

async def history_h(request):
    user = request.query.get("user", "")
    peer = request.query.get("peer", "")
    con = db()
    if peer:
        rows = con.execute("""SELECT sender, receiver, text, ts FROM messages
            WHERE (sender=? AND receiver=?) OR (sender=? AND receiver=?)
            ORDER BY id""", (user, peer, peer, user)).fetchall()
        con.execute("UPDATE messages SET read=1 WHERE receiver=? AND sender=?", (user, peer))
    else:
        rows = con.execute("""SELECT sender, receiver, text, ts FROM messages
            WHERE receiver='' ORDER BY id LIMIT 300""").fetchall()
    con.commit()
    msgs = [{"from": r[0], "to": r[1], "text": r[2], "ts": r[3]} for r in rows]
    con.close()
    return web.json_response({"messages": msgs, "unread": unread_counts(db(), user)})

async def ws_h(request):
    user = request.query.get("user", "")
    if not user:
        return web.Response(status=400)
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    if user in CONNS:
        try: await CONNS[user].close()
        except Exception: pass
    CONNS[user] = ws
    await broadcast_online()
    con = db()
    rows = con.execute("""SELECT sender, receiver, text, ts FROM messages
        WHERE receiver='' ORDER BY id DESC LIMIT 100""").fetchall()[::-1]
    await ws.send_str(json.dumps({"type": "history", "messages": [
        {"from": r[0], "to": r[1], "text": r[2], "ts": r[3]} for r in rows]}))
    await ws.send_str(json.dumps({"type": "unread", "counts": unread_counts(con, user)}))
    con.close()

    try:
        async for m in ws:
            if m.type != WSMsgType.TEXT: continue
            try: data = json.loads(m.data)
            except Exception: continue
            mtype = data.get("type")

            if mtype == "msg":
                to = str(data.get("to", ""))
                text = str(data.get("text", ""))[:2000]
                if not text: continue
                con = db()
                con.execute("INSERT INTO messages(sender, receiver, text, ts) VALUES(?,?,?,?)",
                            (user, to, text, time.time()))
                con.commit()
                con.close()
                payload = json.dumps({"type": "msg", "from": user, "to": to, "text": text, "ts": time.time()})
                if to:
                    rcpt = CONNS.get(to)
                    if rcpt is not None:
                        try: await rcpt.send_str(payload)
                        except Exception: pass
                        try: await rcpt.send_str(json.dumps({"type": "unread", "counts": unread_counts(db(), to)}))
                        except Exception: pass
                try: await ws.send_str(payload)
                except Exception: pass

            elif mtype == "read":
                peer = str(data.get("to", ""))
                if peer:
                    con = db()
                    con.execute("UPDATE messages SET read=1 WHERE receiver=? AND sender=?", (user, peer))
                    con.commit()
                    await ws.send_str(json.dumps({"type": "unread", "counts": unread_counts(con, user)}))
                    con.close()
    finally:
        if CONNS.get(user) is ws:
            del CONNS[user]
            await broadcast_online()
    return ws

app = web.Application()
app.router.add_post("/login", login_h)
app.router.add_get("/history", history_h)
app.router.add_get("/ws", ws_h)

if __name__ == "__main__":
    print("Аська-сервер запущен: http://0.0.0.0:8000")
    web.run_app(app, host="0.0.0.0", port=8000)
