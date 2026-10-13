#!/usr/bin/env python3
"""Live acceptance walk-through for a RUNNING Cloud Kitchen instance (Python 3 standard library only).

It drives the real web application over HTTP: sign-in and CSRF, role checks, valid and invalid
orders, stock limits under concurrent requests, cancel, worker pause/resume/restart, CSV report,
snapshot on demand, the feed simulator and the final state of every order.

WARNING: it WRITES to the target instance: it places orders, changes the stock of item 12, starts
and stops the simulator, pauses/resumes/restarts the PIZZA worker. Run it only against a throw-away
instance (see docs/ACCEPTANCE_TEST_PLAN.md). Seed data is assumed (PIZZA items 11 to 15).

Usage:  CK_ADMIN_PASSWORD=... CK_STAFF_PASSWORD=... python3 scripts/live_acceptance.py --confirm
Optional environment: CK_BASE (default http://127.0.0.1:8080), CK_ADMIN_USER, CK_STAFF_USER.
Exit code 0 when every check passed, 1 otherwise. Not run by the Maven build.
"""
import json, os, re, sys, threading, time
import urllib.error, urllib.parse, urllib.request, http.cookiejar

BASE = os.environ.get('CK_BASE', 'http://127.0.0.1:8080').rstrip('/')
ADMIN_USER = os.environ.get('CK_ADMIN_USER', 'admin')
STAFF_USER = os.environ.get('CK_STAFF_USER', 'staff')
cr = {'admin': os.environ.get('CK_ADMIN_PASSWORD', ''), 'staff': os.environ.get('CK_STAFF_PASSWORD', '')}
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k): return None
class Client:
    def __init__(self):
        self.jar=http.cookiejar.CookieJar()
        self.op=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar),NoRedirect())
    def req(self,method,path,data=None,follow=True):
        body=urllib.parse.urlencode(data,doseq=True).encode() if data is not None else None
        r=urllib.request.Request(BASE+path,data=body,method=method)
        hops=0
        while True:
            try:
                resp=self.op.open(r,timeout=30); code=resp.status; txt=resp.read().decode('utf-8','replace'); hdr=dict(resp.headers)
            except urllib.error.HTTPError as e:
                code=e.code; txt=e.read().decode('utf-8','replace'); hdr=dict(e.headers)
            if follow and code in (301,302,303) and hops<5:
                r=urllib.request.Request(BASE+hdr['Location'].replace(BASE,''),method='GET'); hops+=1; continue
            return code,txt,hdr
    def get(self,p,**k): return self.req('GET',p,**k)
    def post(self,p,d,**k): return self.req('POST',p,d,**k)
    def csrf(self,page='/dashboard'):
        c,t,_=self.get(page); m=re.search(r'name="_csrf"\s+value="([^"]+)"',t) or re.search(r'value="([^"]+)"\s+name="_csrf"',t)
        return m.group(1) if m else None
    def sid(self):
        return {c.name:c.value for c in self.jar}.get('JSESSIONID')
def login(user,pw):
    c=Client(); code,t,h=c.get('/login'); tok=re.search(r'name="_csrf"\s+value="([^"]+)"',t)
    d={'username':user,'password':pw}
    if tok: d['_csrf']=tok.group(1)
    before=c.sid(); code,t,h=c.post('/login',d,follow=False)
    return c,code,h,before


results = []


def check(name, ok, detail=''):
    results.append((name, bool(ok)))
    print(('PASS ' if ok else 'FAIL ') + name + ((' :: ' + str(detail)) if detail != '' else ''), flush=True)


def text(t):
    return re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', t))


def stock(c):
    code, t, _ = c.get('/admin/stock')
    out = {}
    for form in re.findall(r'<form.*?</form>', t, re.S):
        i = re.search(r'name="itemId" value="(\d+)"', form)
        q = re.search(r'name="stockQty"[^>]*value="(-?\d+)"', form)
        if i and q:
            out[int(i.group(1))] = int(q.group(1))
    return out


def set_stock(c, item, qty, reorder=5):
    tok = c.csrf('/admin/stock')
    return c.post('/admin/stock', {'_csrf': tok, 'itemId': item, 'stockQty': qty, 'reorderLevel': reorder}, follow=False)


def order_ids(c):
    ids = set()
    page = 1
    while True:
        code, t, _ = c.get('/orders?page=%d' % page)
        found = set(int(x) for x in re.findall(r'/orders/view\?id=(\d+)', t))
        if not found or found <= ids:
            break
        ids |= found
        if 'page %d of' % page not in t or re.search(r'page %d of %d' % (page, page), t):
            break
        page += 1
    return ids


def status(c, oid):
    code, t, _ = c.get('/orders/view?id=%d' % oid)
    m = re.search(r'Order %d (\w+) Summary' % oid, text(t))
    return m.group(1) if m else ('HTTP%d' % code)


def wait_status(c, oid, want, timeout=40):
    end = time.time() + timeout
    s = None
    while time.time() < end:
        s = status(c, oid)
        if s in want:
            return s
        time.sleep(0.5)
    return s


def place(c, brand, lines, name='Acceptance Tester', dist='3.5', pay='UPI', tok=None):
    items = {'PIZZA': [11, 12, 13, 14, 15]}[brand]
    qty = [str(lines.get(i, 0)) for i in items]
    tok = tok or c.csrf('/orders/new?brandCode=' + brand)
    d = {'_csrf': tok, 'brandCode': brand, 'itemId': [str(i) for i in items], 'quantity': qty,
         'customerName': name, 'deliveryDistanceKm': dist, 'paymentMode': pay}
    code, t, h = c.post('/orders/new', d, follow=False)
    m = re.search(r'id=(\d+)', h.get('Location', '') or '')
    return code, (int(m.group(1)) if m else None), t


def history(c, oid):
    """Sequence of 'to' states from the status-history table of the order page."""
    code, t, _ = c.get('/orders/view?id=%d' % oid)
    seg = text(t).split('Status history', 1)[-1]
    states = 'PLACED|VALIDATED|ROUTED|PREPARING|PACKED|OUT_FOR_DELIVERY|DELIVERED|CANCELLED|FAILED'
    return [m.group(2) for m in re.finditer(r'UTC (-|%s) (%s) ' % (states, states), seg)]


def main():
    # ---- A. startup, authentication, authorization
    code, body, hd = Client().get('/health')
    check('A1 /health returns OK', code == 200 and body.strip() == 'OK')
    anon = Client()
    for p in ('/dashboard', '/orders', '/inventory', '/admin/workers', '/admin/stock', '/admin/users', '/admin/simulator'):
        code, _, h = anon.get(p, follow=False)
        check('A2 anonymous %s redirected to sign-in' % p, code == 302 and '/login' in h.get('Location', ''), (code, h.get('Location')))
    code, _, h = anon.post('/orders/new', {'brandCode': 'PIZZA'}, follow=False)
    check('A3 anonymous POST /orders/new refused', code in (302, 401, 403), code)
    c, code, h, _ = login(ADMIN_USER, 'definitely-wrong')
    c2, code2, h2, _ = login('nosuchuser', 'definitely-wrong')
    msgs = [text(x.get('/login')[1]) for x in (c, c2)]
    check('A4 wrong password and unknown user are refused with the same generic message and no session',
          h.get('Location') == '/login' and h2.get('Location') == '/login' and c.get('/dashboard', follow=False)[0] == 302
          and c2.get('/dashboard', follow=False)[0] == 302 and all('Invalid credentials or account temporarily locked' in m for m in msgs), (h.get('Location'), h2.get('Location')))
    ta = text(c.get('/login')[1]);
    admin, code, h, before = login(ADMIN_USER, cr['admin'])
    check('A5 admin sign-in redirects to dashboard and rotates the session id', code == 302 and '/dashboard' in h.get('Location', '') and before != admin.sid(), (code, h.get('Location')))
    staff, code, h, _ = login(STAFF_USER, cr['staff'])
    check('A6 staff sign-in works', code == 302)
    st0 = stock(admin)
    for p in ('/admin/workers', '/admin/stock', '/admin/users', '/admin/simulator', '/admin/menu', '/admin/reports', '/admin/compensations'):
        code, _, _ = staff.get(p, follow=False)
        check('A7 staff GET %s forbidden' % p, code == 403, code)
    tokS = staff.csrf('/orders/new?brandCode=PIZZA')
    code, _, _ = staff.post('/admin/stock', {'_csrf': tokS, 'itemId': 11, 'stockQty': 1, 'reorderLevel': 1}, follow=False)
    check('A8 staff POST /admin/stock forbidden (valid CSRF token)', code == 403, code)
    check('A9 stock unchanged after forbidden POST', stock(admin) == st0)
    code, _, _ = staff.post('/admin/workers', {'_csrf': tokS, 'stationCode': 'PIZZA', 'generation': 1, 'action': 'pause'}, follow=False)
    check('A10 staff POST /admin/workers forbidden', code == 403, code)
    code, _, _ = admin.post('/orders/new', {'brandCode': 'PIZZA', 'itemId': 11, 'quantity': 1, 'customerName': 'x', 'deliveryDistanceKm': '1', 'paymentMode': 'UPI'}, follow=False)
    check('A11 POST without CSRF token forbidden', code == 403, code)
    code, _, _ = admin.post('/orders/new', {'_csrf': 'forged', 'brandCode': 'PIZZA', 'itemId': 11, 'quantity': 1, 'customerName': 'x', 'deliveryDistanceKm': '1', 'paymentMode': 'UPI'}, follow=False)
    check('A12 POST with forged CSRF token forbidden', code == 403, code)
    code, t, _ = admin.get('/nonexistent/path')
    check('A13 unknown path: generic 404 without stack trace', code == 404 and 'Exception' not in t and 'at com.' not in t, code)

    # ---- B. successful order lifecycle
    before_ids = order_ids(admin)
    s_before = stock(admin)
    code, oid, _ = place(admin, 'PIZZA', {11: 2})
    check('B1 valid order accepted (redirect to order page)', code == 302 and oid is not None, (code, oid))
    fin = wait_status(admin, oid, ('DELIVERED', 'FAILED'), 40)
    check('B2 order reaches DELIVERED through the worker', fin == 'DELIVERED', fin)
    hist = history(admin, oid)
    check('B3 status history is the full legal sequence', hist == ['PLACED', 'VALIDATED', 'ROUTED', 'PREPARING', 'PACKED', 'OUT_FOR_DELIVERY', 'DELIVERED'], hist)
    s_after = stock(admin)
    check('B4 stock decreased by exactly the ordered quantity', s_after[11] == s_before[11] - 2, (s_before[11], s_after[11]))
    code, t, _ = admin.get('/orders/view?id=%d' % oid)
    check('B5 order page shows no raw stack/secret', 'Exception' not in t and cr['admin'] not in t)

    # ---- C. validation failures
    ids0 = order_ids(admin); s0 = stock(admin)
    bad = [
        ('C1 all quantities zero', dict(lines={}), {}),
        ('C2 empty customer name', dict(lines={11: 1}, name=''), {}),
        ('C3 negative distance', dict(lines={11: 1}, dist='-3'), {}),
        ('C4 non-numeric distance', dict(lines={11: 1}, dist='abc'), {}),
        ('C5 absurd distance', dict(lines={11: 1}, dist='99999'), {}),
        ('C6 quantity above maximum', dict(lines={11: 100}), {}),
        ('C7 unknown payment mode', dict(lines={11: 1}, pay='BITCOIN'), {}),
    ]
    for name, kw, _ in bad:
        code, oid2, t = place(admin, 'PIZZA', **kw)
        check(name + ' rejected, no order created', not (code == 302 and oid2), (code, oid2))
    check('C8 no order created and stock unchanged by invalid input', order_ids(admin) == ids0 and stock(admin) == s0)

    # ---- D. insufficient stock and concurrent stock integrity
    set_stock(admin, 12, 3)
    ids0 = order_ids(admin)
    code, oid3, t = place(admin, 'PIZZA', {12: 5})
    check('D1 order exceeding stock refused', not (code == 302 and oid3), (code, oid3))
    check('D2 stock untouched after refusal; no order stored', stock(admin)[12] == 3 and order_ids(admin) == ids0)
    set_stock(admin, 12, 10)
    tok = admin.csrf('/orders/new?brandCode=PIZZA')
    outcomes = []
    lock = threading.Lock()

    def worker():
        code, o, _ = place(admin, 'PIZZA', {12: 1}, tok=tok)
        with lock:
            outcomes.append((code, o))
    ths = [threading.Thread(target=worker) for _ in range(25)]
    [x.start() for x in ths]; [x.join() for x in ths]
    acc = [o for (cde, o) in outcomes if cde == 302 and o]
    check('D3 25 concurrent orders for stock 10: exactly 10 accepted', len(acc) == 10, len(acc))
    check('D4 stock is exactly 0, never negative', stock(admin)[12] == 0, stock(admin)[12])
    finals = [wait_status(admin, o, ('DELIVERED', 'FAILED', 'CANCELLED'), 60) for o in acc]
    check('D5 all accepted concurrent orders reach DELIVERED', all(f == 'DELIVERED' for f in finals), finals)
    check('D6 stock still 0 after processing', stock(admin)[12] == 0)
    set_stock(admin, 12, 100)

    # ---- E. cancel and workers pause/resume/restart
    def worker_state():
        code, t, _ = admin.get('/admin/workers')
        tx = text(t)
        m = re.search(r'PIZZA (\w+) (\d+) (\d+)', tx)
        gen = re.search(r'name="stationCode" value="PIZZA"\s*>\s*<input type="hidden" name="generation" value="(\d+)"', t)
        return m.group(1) if m else None, int(gen.group(1)) if gen else (int(m.group(2)) if m else None)
    st, gen = worker_state()
    tokw = admin.csrf('/admin/workers')
    code, _, _ = admin.post('/admin/workers', {'_csrf': tokw, 'stationCode': 'PIZZA', 'generation': gen, 'action': 'pause'}, follow=False)
    check('E1 admin can pause the PIZZA worker', code == 302, code)
    time.sleep(2)
    s1 = stock(admin)[11]
    code, oidp, _ = place(admin, 'PIZZA', {11: 3})
    time.sleep(3)
    sp = status(admin, oidp)
    check('E2 order accepted while paused is not processed', sp in ('ROUTED', 'VALIDATED'), sp)
    tokc = admin.csrf('/orders/view?id=%d' % oidp)
    code, _, _ = admin.post('/orders/cancel', {'_csrf': tokc, 'id': oidp}, follow=False)
    check('E3 cancel accepted', code == 302, code)
    check('E4 cancelled order CANCELLED and stock restored', wait_status(admin, oidp, ('CANCELLED',), 10) == 'CANCELLED' and stock(admin)[11] == s1, (status(admin, oidp), stock(admin)[11], s1))
    code, _, _ = admin.post('/orders/cancel', {'_csrf': tokc, 'id': oidp}, follow=False)
    check('E5 second cancel of a terminal order does not change it', status(admin, oidp) == 'CANCELLED')
    tokw = admin.csrf('/admin/workers')
    code, _, _ = admin.post('/admin/workers', {'_csrf': tokw, 'stationCode': 'PIZZA', 'generation': gen, 'action': 'resume'}, follow=False)
    code, oidr, _ = place(admin, 'PIZZA', {11: 1})
    check('E6 after resume new order is delivered', wait_status(admin, oidr, ('DELIVERED',), 40) == 'DELIVERED')
    st, gen = worker_state()
    tokw = admin.csrf('/admin/workers')
    code, _, _ = admin.post('/admin/workers', {'_csrf': tokw, 'stationCode': 'PIZZA', 'generation': gen, 'action': 'restart'}, follow=False)
    time.sleep(2)
    st2, gen2 = worker_state()
    check('E7 restart replaces the worker with a new generation', gen2 == gen + 1, (gen, gen2))
    code, t, _ = admin.post('/admin/workers', {'_csrf': admin.csrf('/admin/workers'), 'stationCode': 'PIZZA', 'generation': gen, 'action': 'restart'})
    st3, gen3 = worker_state()
    check('E8 restart with a stale generation is ignored', gen3 == gen2, (gen2, gen3))
    code, oidr2, _ = place(admin, 'PIZZA', {11: 1})
    check('E9 restarted worker processes orders', wait_status(admin, oidr2, ('DELIVERED',), 40) == 'DELIVERED')
    code, t, _ = admin.post('/admin/workers', {'_csrf': admin.csrf('/admin/workers'), 'stationCode': 'NOPE', 'generation': 1, 'action': 'restart'})
    check('E10 unknown station rejected without a stack trace', code in (400, 200) and 'Exception' not in t, code)

    # ---- F. reports and snapshot
    code, t, h = admin.get('/admin/reports/daily.csv?date=' + time.strftime('%Y-%m-%d', time.gmtime()))
    check('F1 daily CSV downloads, has header and rows, no customer name', code == 200 and 'Acceptance Tester' not in t and t.count('\n') >= 3, (code, t.count('\n')))
    code, _, _ = admin.post('/admin/snapshot', {'_csrf': admin.csrf('/admin/workers')}, follow=False)
    check('F2 snapshot on demand', code == 302, code)

    # ---- G. simulator
    code, _, _ = admin.post('/admin/simulator', {'_csrf': admin.csrf('/admin/simulator'), 'action': 'start', 'ratePerMinute': 60}, follow=False)
    check('G1 simulator start', code == 302, code)
    time.sleep(25)
    code, _, _ = admin.post('/admin/simulator', {'_csrf': admin.csrf('/admin/simulator'), 'action': 'stop'}, follow=False)
    check('G2 simulator stop', code == 302, code)
    time.sleep(1)
    ids = order_ids(admin)
    check('G3 simulator produced orders', len(ids) > 15, len(ids))
    end = time.time() + 90
    nonterm = None
    while time.time() < end:
        sts = [status(admin, i) for i in sorted(ids)]
        nonterm = [s for s in sts if s not in ('DELIVERED', 'CANCELLED', 'FAILED')]
        if not nonterm:
            break
        time.sleep(2)
    check('G4 every order reaches a terminal state', not nonterm, nonterm)
    check('G5 no negative stock', all(v >= 0 for v in stock(admin).values()), min(stock(admin).values()))
    fails = [i for i in ids if status(admin, i) == 'FAILED']
    check('G6 no FAILED orders at the default capacity', not fails, fails)
    print('SUMMARY %d passed, %d failed' % (sum(1 for _, o in results if o), sum(1 for _, o in results if not o)))
    return 0 if all(o for _, o in results) else 1


if __name__ == '__main__':
    if '--confirm' not in sys.argv or not cr['admin'] or not cr['staff']:
        print(__doc__)
        print('Refusing to run: pass --confirm and set CK_ADMIN_PASSWORD and CK_STAFF_PASSWORD.')
        sys.exit(2)
    sys.exit(main())
