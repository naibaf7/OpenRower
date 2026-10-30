#!/usr/bin/env python3
"""
OpenRower sensor board -- autorouter (2 layers, KiCad 10 pcbnew API).

Grid A* router with exact copper geometry: for every grid point it keeps the distance to the
nearest copper of the two nearest *different* nets, so a candidate track/via is checked
against the real pad/track shapes (not a raster).  Features:
  * per-net widths (power 0.4 mm, signal 0.25 mm), 0.2 mm clearance, 0.5 mm copper-to-edge,
    0.25 mm hole clearance / hole-to-hole, antenna keep-out from the module footprint
  * top layer preferred; bottom layer and vias cost extra so the bottom stays a GND plane
  * turn penalties (no acute angles), mild penalty for hugging other nets
  * rip-up and reroute when a connection is blocked
  * GND: vias in the ESP32 ground pad, a via next to every GND pad, edge/field stitching
    vias, and solid GND pours on both layers

Run from hardware/ after tools/gen_pcb.py:   python3 tools/route.py
Writes openrower_sensor.kicad_pcb in place (routing is replaced, footprints untouched).
"""

import heapq
import math
import os
import sys
import time

import numpy as np
import pcbnew

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PCB = os.path.join(HERE, 'openrower_sensor.kicad_pcb')

G = 0.05                       # grid pitch, mm
X0, Y0, X1, Y1 = 100.0, 100.0, 155.0, 145.0
NX = int(round((X1 - X0) / G)) + 1
NY = int(round((Y1 - Y0) / G)) + 1
CLR = 0.2                      # copper-copper clearance
EDGE_CLR = 0.5                 # copper to board edge
HOLE_CLR = 0.25                # copper to NPTH hole, hole to hole
VIA_D, VIA_DRILL = 0.6, 0.3
EPS = 0.005
W_SIG, W_PWR, W_GND = 0.25, 0.4, 0.3
POWER = {'/VBUS', '+5V', '+3V3', '/HALL_3V3_OUT', '/HALL_5V_OUT', '/JACK_RING'}
FIRST = ['/USB_DP', '/USB_DM', '/VBUS', '+5V', '/JACK_RING', '/HALL_3V3_OUT', '/HALL_5V_OUT', '+3V3']
LAYER_COST = (1.0, 1.6)        # per-step cost factor F.Cu, B.Cu
VIA_COST = 40.0                # in grid steps (2 mm)
TURN_COST = {1: 3.0, 2: 8.0}   # 45 deg, 90 deg (in grid steps); sharper turns forbidden
HUG_COST = 0.6                 # extra per step when closer than threshold + 0.1 mm to foreign copper
RMAX = 0.7                     # distance fields are exact up to this radius
NOROUTE_BODY = {'J1': (0,), 'J2': (0,), 'SW1': (0,), 'SW2': (0,)}
LAYERS = (pcbnew.F_Cu, pcbnew.B_Cu)

# Hand-designed USB-C breakout (0.5 mm pitch, pin order DM DP DM DP): VBUS pads drop to vias,
# B6 (DP) steps into the unused A8 lane, A7 (DM) takes a via in the gap that opens. Clearances
# >= 0.25 mm, checked by hand and by KiCad DRC.  The router continues from these stubs.
# Each chain is one fixed piece of copper; 'via_only' chains may only be joined from the bottom
# side of their via.  Lanes run straight out of the 0.5 mm pitch pads so later routes cannot
# crowd a neighbour's exit.
PREROUTES = {
    '/VBUS': [{'items': [('seg', (107.695, 129.05, 109.05, 129.05, W_PWR, 0)), ('via', (109.05, 129.05))]},
              {'items': [('seg', (107.695, 133.95, 109.05, 133.95, W_PWR, 0)), ('via', (109.05, 133.95))]}],
    '/USB_DM': [{'items': [('seg', (107.695, 131.75, 109.75, 131.75, W_SIG, 0)),
                           ('seg', (109.75, 131.75, 110.0, 132.0, W_SIG, 0)), ('via', (110.0, 132.0))],
                 'via_only': True},
                {'items': [('seg', (107.695, 130.75, 109.4, 130.75, W_SIG, 0))]}],
    '/USB_DP': [{'items': [('seg', (107.695, 132.25, 108.7, 132.25, W_SIG, 0)),
                           ('seg', (108.7, 132.25, 109.2, 132.75, W_SIG, 0)),
                           ('seg', (109.2, 132.75, 109.9, 132.75, W_SIG, 0))]},
                {'items': [('seg', (107.695, 131.25, 109.4, 131.25, W_SIG, 0))]}],
    '/CC1': [{'items': [('seg', (107.695, 130.25, 108.9, 130.25, W_SIG, 0))]}],
    '/CC2': [{'items': [('seg', (107.695, 133.25, 108.9, 133.25, W_SIG, 0))]}],
}
# ESP32 module escape stubs (0.8 mm pitch): every used signal pin gets a 0.9 mm straight lane
# outward, so neighbouring routes cannot pass in front of a pin before it is routed.
_U1_WEST = {'/RING_SENSE': 117.7, '/LED_DATA': 120.9, '/HALL_3V3_EN': 122.5, '/HALL_5V_EN': 123.3,
            '/COIL_OUT': 124.1, '/LED_BLE': 124.9, '/STRAP_GPIO8': 125.7, '/BOOT': 126.5}
_U1_TOP = {'/STRAP_GPIO2': 144.1, '/SENSOR_OUT': 143.3, '/ESP_EN': 141.7, '+3V3': 145.7}
_U1_BOTTOM = {'/USB_DM': 140.1, '/USB_DP': 140.9}
for _n, _y in _U1_WEST.items():
    PREROUTES.setdefault(_n, []).append({'items': [('seg', (138.4, _y, 137.5, _y, W_SIG, 0))]})
for _n, _x in _U1_TOP.items():
    PREROUTES.setdefault(_n, []).append({'items': [('seg', (_x, 116.6, _x, 115.7, W_PWR if _n == '+3V3' else W_SIG, 0))]})
for _n, _x in _U1_BOTTOM.items():
    PREROUTES.setdefault(_n, []).append({'items': [('seg', (_x, 128.4, _x, 129.3, W_SIG, 0))]})
MM = pcbnew.ToMM

XS = X0 + np.arange(NX) * G
YS = Y0 + np.arange(NY) * G


def gi(v, o):
    return int(round((v - o) / G))


# ── geometry: exact distance from grid points to shapes ───────────────────────
class Shape:
    """kind: 'rect' (cx,cy,hx,hy,ang), 'circle' (cx,cy,r), 'seg' (x1,y1,x2,y2,r)."""

    def __init__(self, kind, params, net, layers, smd=False):
        self.kind, self.p, self.net, self.layers, self.smd = kind, params, net, layers, smd

    def bbox(self):
        p = self.p
        if self.kind == 'rect':
            cx, cy, hx, hy, a = p
            r = math.hypot(hx, hy)
            return cx - r, cy - r, cx + r, cy + r
        if self.kind == 'circle':
            cx, cy, r = p
            return cx - r, cy - r, cx + r, cy + r
        x1, y1, x2, y2, r = p
        return min(x1, x2) - r, min(y1, y2) - r, max(x1, x2) + r, max(y1, y2) + r

    def window(self, pad=RMAX):
        x0, y0, x1, y1 = self.bbox()
        i0, i1 = max(gi(x0 - pad, X0), 0), min(gi(x1 + pad, X0) + 1, NX)
        j0, j1 = max(gi(y0 - pad, Y0), 0), min(gi(y1 + pad, Y0) + 1, NY)
        return j0, j1, i0, i1

    def dist(self, X, Y):
        p = self.p
        if self.kind == 'rect':
            cx, cy, hx, hy, a = p
            c, s = math.cos(a), math.sin(a)
            u = (X - cx) * c + (Y - cy) * s
            v = -(X - cx) * s + (Y - cy) * c
            return np.hypot(np.maximum(np.abs(u) - hx, 0), np.maximum(np.abs(v) - hy, 0))
        if self.kind == 'circle':
            cx, cy, r = p
            return np.maximum(np.hypot(X - cx, Y - cy) - r, 0)
        x1, y1, x2, y2, r = p
        dx, dy = x2 - x1, y2 - y1
        L2 = dx * dx + dy * dy
        t = np.zeros_like(X) if L2 == 0 else np.clip(((X - x1) * dx + (Y - y1) * dy) / L2, 0, 1)
        return np.maximum(np.hypot(X - (x1 + t * dx), Y - (y1 + t * dy)) - r, 0)


def pad_shapes(pad, net):
    pos = pad.GetPosition()
    cx, cy = MM(pos.x), MM(pos.y)
    sz = pad.GetSize(pcbnew.F_Cu)
    sx, sy = MM(sz.x), MM(sz.y)
    ang = math.radians(pad.GetOrientationDegrees())
    attr = pad.GetAttribute()
    if attr == pcbnew.PAD_ATTRIB_NPTH:
        r = MM(pad.GetDrillSize().x) / 2 + (HOLE_CLR - CLR)
        return [Shape('circle', (cx, cy, r), -1, (0, 1))]
    layers = tuple(i for i, L in enumerate(LAYERS) if pad.IsOnLayer(L))
    smd = attr == pcbnew.PAD_ATTRIB_SMD
    shp = pad.GetShape(pcbnew.F_Cu)
    if shp == pcbnew.PAD_SHAPE_CIRCLE:
        return [Shape('circle', (cx, cy, sx / 2), net, layers, smd)]
    if shp == pcbnew.PAD_SHAPE_OVAL:
        r = min(sx, sy) / 2
        a = (max(sx, sy) - min(sx, sy)) / 2
        ux, uy = (math.cos(ang), -math.sin(ang)) if sx > sy else (math.sin(ang), math.cos(ang))
        return [Shape('seg', (cx - a * ux, cy - a * uy, cx + a * ux, cy + a * uy, r), net, layers, smd)]
    return [Shape('rect', (cx, cy, sx / 2, sy / 2, ang), net, layers, smd)]


# ── distance fields: nearest + second-nearest-other-net distance per layer ───
class Field:
    def __init__(self):
        self.d1 = np.full((2, NY, NX), np.inf, np.float32)
        self.n1 = np.full((2, NY, NX), -9, np.int32)
        self.d2 = np.full((2, NY, NX), np.inf, np.float32)

    def add(self, sh):
        j0, j1, i0, i1 = sh.window()
        if j0 >= j1 or i0 >= i1:
            return
        X, Y = np.meshgrid(XS[i0:i1], YS[j0:j1])
        d = sh.dist(X, Y).astype(np.float32)
        for L in sh.layers:
            d1 = self.d1[L, j0:j1, i0:i1]
            n1 = self.n1[L, j0:j1, i0:i1]
            d2 = self.d2[L, j0:j1, i0:i1]
            same = n1 == sh.net
            np.minimum(d1, np.where(same, d, d1), out=d1)
            other_closer = (~same) & (d < d1)
            d2[...] = np.where(other_closer, d1, np.where((~same) & (d < d2), d, d2))
            d1[...] = np.where(other_closer, d, d1)
            n1[...] = np.where(other_closer, sh.net, n1)

    def foreign(self, net):
        return np.where(self.n1 == net, self.d2, self.d1)


class MinField:
    """Plain nearest distance (no net logic): SMD pads, drills."""

    def __init__(self):
        self.d = np.full((NY, NX), np.inf, np.float32)

    def add(self, sh):
        j0, j1, i0, i1 = sh.window()
        X, Y = np.meshgrid(XS[i0:i1], YS[j0:j1])
        np.minimum(self.d[j0:j1, i0:i1], sh.dist(X, Y).astype(np.float32), out=self.d[j0:j1, i0:i1])


# ── board model ───────────────────────────────────────────────────────────────
class Board:
    def __init__(self, board):
        self.board = board
        self.netcode = {}
        self.pads = {}            # net -> list of (ref.pad, [Shape])
        self.static = Field()
        self.smd = MinField()
        self.drill_static = MinField()
        for fp in board.GetFootprints():
            for pad in fp.Pads():
                name = pad.GetNetname()
                net = self.code(name) if name else -2
                shapes = pad_shapes(pad, net)
                for sh in shapes:
                    self.static.add(sh)
                    if sh.smd and 0 in sh.layers:
                        self.smd.add(sh)
                if pad.GetAttribute() in (pcbnew.PAD_ATTRIB_PTH, pcbnew.PAD_ATTRIB_NPTH):
                    p = pad.GetPosition()
                    self.drill_static.add(Shape('circle', (MM(p.x), MM(p.y), MM(pad.GetDrillSize().x) / 2), -1, (0,)))
                if name and not name.startswith('unconnected'):
                    self.pads.setdefault(name, []).append((f'{fp.GetReference()}.{pad.GetNumber()}', shapes))
        X, Y = np.meshgrid(XS, YS)
        self.edge = np.minimum.reduce([X - X0, X1 - X, Y - Y0, Y1 - Y]).astype(np.float32)
        self.keepout = np.zeros((NY, NX), bool)
        for fp in board.GetFootprints():
            for z in fp.Zones():
                if z.GetIsRuleArea() and z.GetDoNotAllowTracks():
                    o = z.Outline().Outline(0)
                    pts = [(MM(o.CPoint(k).x), MM(o.CPoint(k).y)) for k in range(o.PointCount())]
                    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
                    self.keepout |= (X >= min(xs) - EPS) & (X <= max(xs) + EPS) & (Y >= min(ys) - EPS) & (Y <= max(ys) + EPS)
        # no-route areas: under the ESP32 module interior (both layers, keeps the GND plane whole)
        # and under the bodies of the USB-C shell, the jack and the tact switches (top layer)
        self.noroute = np.zeros((2, NY, NX), bool)
        inside_any_pad = np.zeros((NY, NX), bool)
        for shapes in [s for _, s in sum(self.pads.values(), [])]:
            for (L, j, i) in pad_cells(shapes):
                inside_any_pad[j, i] = True
        for fp in board.GetFootprints():
            ref = fp.GetReference()
            if ref not in NOROUTE_BODY:
                continue
            fab = [g.GetBoundingBox() for g in fp.GraphicalItems() if g.GetLayer() == pcbnew.F_Fab and g.GetClass() != 'PCB_TEXT']
            x0 = min(MM(b.GetLeft()) for b in fab) + 0.2
            x1 = max(MM(b.GetRight()) for b in fab) - 0.2
            y0 = min(MM(b.GetTop()) for b in fab) + 0.2
            y1 = max(MM(b.GetBottom()) for b in fab) - 0.2
            box = (X >= x0) & (X <= x1) & (Y >= y0) & (Y <= y1) & ~inside_any_pad
            for L in NOROUTE_BODY[ref]:
                self.noroute[L] |= box
        for L in (0, 1):
            self.noroute[L] |= (X >= 139.2) & (X <= 148.9) & (Y >= 117.4) & (Y <= 127.6)   # U1 interior
        self.tracks = []          # (net_name, kind, data): ('seg', (x1,y1,x2,y2,w,L)) / ('via', (x,y))
        self.fixed = []
        for name, chains in PREROUTES.items():
            for ch in chains:
                for kind, data in ch['items']:
                    self.fixed.append((name, kind, data))
        self.tracks = list(self.fixed)
        self.rebuild()

    def code(self, name):
        if name not in self.netcode:
            self.netcode[name] = len(self.netcode)
        return self.netcode[name]

    def rebuild(self):
        self.dyn = Field()
        self.drill = MinField()
        self.drill.d[...] = self.drill_static.d
        for name, kind, data in self.tracks:
            self._add_dyn(name, kind, data)

    def _add_dyn(self, name, kind, data):
        net = self.code(name)
        if kind == 'seg':
            x1, y1, x2, y2, w, L = data
            self.dyn.add(Shape('seg', (x1, y1, x2, y2, w / 2), net, (L,)))
        else:
            x, y = data
            self.dyn.add(Shape('circle', (x, y, VIA_D / 2), net, (0, 1)))
            self.drill.add(Shape('circle', (x, y, VIA_DRILL / 2), -1, (0,)))

    def add_track(self, name, kind, data):
        self.tracks.append((name, kind, data))
        self._add_dyn(name, kind, data)

    def rip(self, names):
        self.tracks = [t for t in self.tracks if t[0] not in names or t in self.fixed]
        self.rebuild()

    def foreign(self, name):
        net = self.code(name)
        return np.minimum(self.static.foreign(net), self.dyn.foreign(net))


# ── A* router ─────────────────────────────────────────────────────────────────
DIRS = [(1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1)]
SQ2 = math.sqrt(2)


class NetRouter:
    def __init__(self, B, name, width, soft=False):
        self.B, self.name, self.w = B, name, width
        fd = B.foreign(name)
        thr = CLR + width / 2 + EPS
        edge_ok = B.edge >= EDGE_CLR + width / 2
        self.free = [(fd[L] >= thr) & edge_ok & ~B.keepout & (~B.noroute[L] if name != 'GND' else True) for L in (0, 1)]
        self.hug = [(fd[L] < thr + 0.1) for L in (0, 1)]
        vthr = CLR + VIA_D / 2 + EPS
        self.via_ok = ((fd[0] >= vthr) & (fd[1] >= vthr) & (B.edge >= EDGE_CLR + VIA_D / 2) & ~B.keepout & ~B.noroute[0] & ~B.noroute[1]
                       & (B.drill.d >= HOLE_CLR + VIA_DRILL / 2) & (B.smd.d >= VIA_D / 2 + 0.05))
        self.soft = None
        if soft:   # allow crossing other nets' tracks (not pads) at a high price, to find blockers
            sfd = B.static.foreign(B.code(name))
            self.soft = [(sfd[L] >= thr) & edge_ok & ~B.keepout & ~B.noroute[L] & ~self.free[L] for L in (0, 1)]
            svia = ((sfd[0] >= vthr) & (sfd[1] >= vthr) & (B.edge >= EDGE_CLR + VIA_D / 2) & ~B.keepout
                    & (B.drill_static.d >= HOLE_CLR + VIA_DRILL / 2) & (B.smd.d >= VIA_D / 2 + 0.05))
            self.via_soft = svia & ~self.via_ok
        self.flat = [self.free[L].ravel().tolist() for L in (0, 1)]
        self.hugf = [self.hug[L].ravel().tolist() for L in (0, 1)]
        self.viaf = self.via_ok.ravel().tolist()
        if soft:
            self.softf = [self.soft[L].ravel().tolist() for L in (0, 1)]
            self.viasf = self.via_soft.ravel().tolist()

    def search(self, sources, targets, tcenter, max_exp=3_000_000):
        """sources/targets: sets of (L, j, i). Returns list of (L, j, i) or None."""
        if tcenter is None:          # many scattered targets: plain Dijkstra
            tx = ty = None
        else:
            tx, ty = gi(tcenter[0], X0), gi(tcenter[1], Y0)
        soft = self.soft is not None
        heap, g = [], {}
        for (L, j, i) in sources:
            if self.flat[L][j * NX + i] or (soft and self.softf[L][j * NX + i]):
                k = (L, j, i, 8)
                g[k] = 0.0
                heapq.heappush(heap, (self._h(i, j, tx, ty), 0.0, L, j, i, 8))
        prev = {}
        exp = 0
        while heap:
            f, gc, L, j, i, d = heapq.heappop(heap)
            if g.get((L, j, i, d), 1e18) < gc:
                continue
            if (L, j, i) in targets:
                path = [(L, j, i)]
                k = (L, j, i, d)
                while k in prev:
                    k = prev[k]
                    path.append(k[:3])
                return path[::-1]
            exp += 1
            if exp > max_exp:
                return None
            lc = LAYER_COST[L]
            for nd, (di, dj) in enumerate(DIRS):
                if d != 8:
                    turn = min((nd - d) % 8, (d - nd) % 8)
                    if turn > 2:
                        continue
                    tcost = TURN_COST.get(turn, 0.0)
                else:
                    tcost = 0.0
                ni, nj = i + di, j + dj
                if not (0 <= ni < NX and 0 <= nj < NY):
                    continue
                idx = nj * NX + ni
                pen = 0.0
                if not self.flat[L][idx]:
                    if soft and self.softf[L][idx]:
                        pen = 5.0
                    else:
                        continue
                if di and dj:   # diagonal: both orthogonal neighbours must be free too (no corner cutting)
                    if not (self.flat[L][j * NX + ni] or (soft and self.softf[L][j * NX + ni])) or \
                       not (self.flat[L][nj * NX + i] or (soft and self.softf[L][nj * NX + i])):
                        continue
                step = (SQ2 if di and dj else 1.0) * lc
                if self.hugf[L][idx]:
                    step += HUG_COST
                ng = gc + step + tcost + pen
                k = (L, nj, ni, nd)
                if ng < g.get(k, 1e18):
                    g[k] = ng
                    prev[k] = (L, j, i, d)
                    heapq.heappush(heap, (ng + self._h(ni, nj, tx, ty), ng, L, nj, ni, nd))
            idx = j * NX + i
            ok = self.viaf[idx] or (soft and self.viasf[idx])
            if ok:
                OL = 1 - L
                pen = 0.0 if self.viaf[idx] else 5.0
                k = (OL, j, i, 8)
                ng = gc + VIA_COST + pen
                if ng < g.get(k, 1e18):
                    g[k] = ng
                    prev[k] = (L, j, i, d)
                    heapq.heappush(heap, (ng + self._h(i, j, tx, ty), ng, OL, j, i, 8))
        return None

    @staticmethod
    def _h(i, j, tx, ty):
        if tx is None:
            return 0.0
        dx, dy = abs(i - tx), abs(j - ty)
        return (dx + dy) + (SQ2 - 2) * min(dx, dy)


def pad_cells(shapes, layers=None):
    """Grid cells whose centre lies inside the pad copper."""
    cells = set()
    for sh in shapes:
        if sh.net < 0:
            continue
        j0, j1, i0, i1 = sh.window(pad=0.0)
        X, Y = np.meshgrid(XS[i0:i1], YS[j0:j1])
        inside = sh.dist(X, Y) <= 1e-6
        for L in sh.layers:
            if layers is None or L in layers:
                for jj, ii in zip(*np.nonzero(inside)):
                    cells.add((L, j0 + jj, i0 + ii))
    return cells


def path_to_tracks(path, width):
    """Compress a cell path into straight segments and vias."""
    out = []
    pts = [(L, X0 + i * G, Y0 + j * G) for (L, j, i) in path]
    start = pts[0]
    prevdir = None
    for a, b in zip(pts, pts[1:]):
        if a[0] != b[0]:
            if start[1:] != a[1:]:
                out.append(('seg', (start[1], start[2], a[1], a[2], width, a[0])))
            out.append(('via', (a[1], a[2])))
            start, prevdir = b, None
            continue
        d = (round((b[1] - a[1]) / G), round((b[2] - a[2]) / G))
        if prevdir is not None and d != prevdir:
            out.append(('seg', (start[1], start[2], a[1], a[2], width, a[0])))
            start = a
        prevdir = d
    last = pts[-1]
    if start[1:] != last[1:]:
        out.append(('seg', (start[1], start[2], last[1], last[2], width, last[0])))
    return out


def track_cells(tr):
    cells = set()
    for kind, data in tr:
        if kind == 'via':
            x, y = data
            for L in (0, 1):
                cells.add((L, gi(y, Y0), gi(x, X0)))
        else:
            x1, y1, x2, y2, w, L = data
            n = max(abs(gi(x2, X0) - gi(x1, X0)), abs(gi(y2, Y0) - gi(y1, Y0)))
            for k in range(n + 1):
                t = k / n if n else 0
                cells.add((L, gi(y1 + t * (y2 - y1), Y0), gi(x1 + t * (x2 - x1), X0)))
    return cells


# ── net routing with rip-up ───────────────────────────────────────────────────
def width_of(name):
    return W_PWR if name in POWER else W_SIG


def terminals(B, name):
    """Connection terminals of a net: each pad, merged with fixed (pre-routed) copper touching it.
    Returns list of (label, target_cells, all_cells, centre).  For VIA_ONLY nets the fixed group
    may only be reached from the bottom side of its via (USB-C breakout)."""
    groups = []
    for ref, shapes in B.pads[name]:
        x0, y0, x1, y1 = shapes[0].bbox()
        c = pad_cells(shapes)
        groups.append([ref, c, set(c), ((x0 + x1) / 2, (y0 + y1) / 2)])
    for ch in PREROUTES.get(name, []):
        fixed = ch['items']
        cells = track_cells(fixed)
        pts = [(d[0], d[1]) if k == 'via' else (d[2], d[3]) for k, d in fixed]
        g = ['fixed', set(cells), set(cells), pts[-1]]
        for x in [x for x in groups if x[2] & cells]:
            g[0] += '+' + x[0]
            g[1] |= x[1]
            g[2] |= x[2]
            groups.remove(x)
        if ch.get('via_only'):
            g[1] = {(1, gi(d[1], Y0), gi(d[0], X0)) for k, d in fixed if k == 'via'}
        groups.append(g)
    return [tuple(x) for x in groups]


def route_net(B, name, width, log):
    """Grow a tree over the net's terminals. Returns list of unrouted terminals."""
    terms = terminals(B, name)
    pads = [(lbl, tgt) for lbl, tgt, _, _ in terms]
    allcells = [a for _, _, a, _ in terms]
    centers = [c for _, _, _, c in terms]
    # start with the pad closest to the net's centroid
    cx = sum(c[0] for c in centers) / len(centers)
    cy = sum(c[1] for c in centers) / len(centers)
    order = sorted(range(len(pads)), key=lambda k: ('fixed' in pads[k][0],
                                                     (centers[k][0] - cx) ** 2 + (centers[k][1] - cy) ** 2))
    tree = set(allcells[order[0]])
    tree_pts = [centers[order[0]]]
    remaining = order[1:]
    failed = []
    routers = {}

    def router(w):
        if w not in routers:
            routers[w] = NetRouter(B, name, w)
        return routers[w]

    while remaining:
        k = min(remaining, key=lambda k: min((centers[k][0] - p[0]) ** 2 + (centers[k][1] - p[1]) ** 2 for p in tree_pts))
        remaining.remove(k)
        ref, tgt = pads[k]
        if tgt & tree:
            tree |= allcells[k]
            tree_pts.append(centers[k])
            continue
        path = None
        for w in ([width] if width == W_SIG else [width, W_SIG]):
            path = router(w).search(tree, tgt, centers[k])
            if path:
                break
        if not path:
            failed.append((ref, centers[k]))
            log.append(f'  FAIL {name} -> {ref}')
            continue
        tr = path_to_tracks(path, w)
        for kind, data in tr:
            B.add_track(name, kind, data)
        tree |= allcells[k] | track_cells(tr)
        tree_pts.append(centers[k])
    return failed


def find_blockers(B, name, width):
    """Route the net's failing connection with soft obstacles and report which nets it crosses."""
    terms = terminals(B, name)
    R = NetRouter(B, name, width, soft=True)
    blockers = set()
    terms = sorted(terms, key=lambda x: 'fixed' in x[0])
    tree = set(terms[0][2])
    for ref, cells, allc, centre in terms[1:]:
        path = R.search(tree, cells, centre, max_exp=6_000_000)
        if not path:
            continue
        own = B.code(name)
        for (L, j, i) in path:
            if not R.flat[L][j * NX + i]:
                n = B.dyn.n1[L, j, i]
                if n == own:
                    continue
                for nm, c in B.netcode.items():
                    if c == n:
                        blockers.add(nm)
        tree |= set(path)
    return blockers


def route_all(B, log):
    names = [n for n in B.pads if n != 'GND' and len(terminals(B, n)) > 1]

    def span(n):
        xs = [s[0].bbox()[0] for _, s in B.pads[n]]
        ys = [s[0].bbox()[1] for _, s in B.pads[n]]
        return (max(xs) - min(xs)) + (max(ys) - min(ys))

    order = [n for n in FIRST if n in names] + sorted((n for n in names if n not in FIRST), key=span)
    queue = list(order)
    ripped_count = {}
    while queue:
        name = queue.pop(0)
        t = time.time()
        failed = route_net(B, name, width_of(name), log)
        log.append(f'{name:16} pads={len(B.pads[name]):2} {"OK" if not failed else "FAILED"} ({time.time() - t:.1f}s)')
        print(log[-1], flush=True)
        if failed:
            B.rip({name})
            blockers = find_blockers(B, name, width_of(name)) - set(queue) - {'GND'}
            blockers = {b for b in blockers if ripped_count.get(b, 0) < 3}
            if not blockers:
                log.append(f'  no rippable blockers for {name}; leaving it unrouted')
                route_net(B, name, width_of(name), log)   # keep partial routing
                continue
            log.append(f'  rip-up {sorted(blockers)} for {name}')
            print(log[-1], flush=True)
            for b in blockers:
                ripped_count[b] = ripped_count.get(b, 0) + 1
            B.rip(blockers)
            queue = [name] + sorted(blockers, key=lambda n: FIRST.index(n) if n in FIRST else 99) + queue


# ── cleanup: trim dangling track ends ────────────────────────────────────────
def _on_seg(px, py, d, tol=0.01):
    x1, y1, x2, y2 = d[0], d[1], d[2], d[3]
    dx, dy = x2 - x1, y2 - y1
    L2 = dx * dx + dy * dy
    tt = 0 if L2 == 0 else max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / L2))
    return math.hypot(px - (x1 + tt * dx), py - (y1 + tt * dy)) <= tol, tt


def cleanup_dangling(B, log):
    """Shorten or drop segments whose end touches nothing (pad, via, other segment)."""
    pad_shapes = {n: [s for _, sh in B.pads[n] for s in sh] for n in B.pads}
    changed, trimmed = True, 0
    while changed:
        changed = False
        for idx, (name, kind, d) in enumerate(B.tracks):
            if kind != 'seg':
                continue
            L = int(d[5])
            for end in (0, 1):
                px, py = (d[0], d[1]) if end == 0 else (d[2], d[3])
                if _end_connected(B, idx, name, L, px, py, pad_shapes.get(name, [])):
                    continue
                # attachment points of other copper along this segment, measured from the live end
                best = None
                for j, (n2, k2, d2) in enumerate(B.tracks):
                    if j == idx or n2 != name:
                        continue
                    pts = [(d2[0], d2[1])] if k2 == 'via' else ([(d2[0], d2[1]), (d2[2], d2[3])] if int(d2[5]) == L else [])
                    for qx, qy in pts:
                        on, tt = _on_seg(qx, qy, d)
                        if on:
                            frac = tt if end == 0 else 1 - tt
                            if frac > 1e-6 and (best is None or frac < best[0]):
                                best = (frac, qx, qy)
                if best is None:
                    B.tracks[idx] = (name, 'gone', d)
                else:
                    nd = (best[1], best[2], d[2], d[3], d[4], d[5]) if end == 0 else (d[0], d[1], best[1], best[2], d[4], d[5])
                    B.tracks[idx] = (name, 'seg', nd)
                    d = nd
                trimmed += 1
                changed = True
                break
        B.tracks = [x for x in B.tracks if x[1] != 'gone']
    B.fixed = [f for f in B.fixed if f in B.tracks]
    B.rebuild()
    log.append(f'dangling ends trimmed: {trimmed}')


def _end_connected(B, idx, name, L, px, py, shapes):
    for sh in shapes:
        if L in sh.layers and float(sh.dist(np.array([px]), np.array([py]))[0]) <= 1e-6:
            return True
    for j, (n2, k2, d2) in enumerate(B.tracks):
        if j == idx or n2 != name or k2 == 'gone':
            continue
        if k2 == 'via':
            if math.hypot(d2[0] - px, d2[1] - py) <= 0.01:
                return True
        elif int(d2[5]) == L and _on_seg(px, py, d2)[0]:
            return True
    return False


# ── GND: module pad vias, per-pad vias, stitching ─────────────────────────────
def legal_via(B, x, y, name='GND', ignore_smd=False):
    i, j = gi(x, X0), gi(y, Y0)
    if not (0 <= i < NX and 0 <= j < NY):
        return False
    net = B.code(name)
    vthr = CLR + VIA_D / 2 + EPS
    for L in (0, 1):
        fd = min(B.static.d1[L, j, i] if B.static.n1[L, j, i] != net else B.static.d2[L, j, i],
                 B.dyn.d1[L, j, i] if B.dyn.n1[L, j, i] != net else B.dyn.d2[L, j, i])
        if fd < vthr:
            return False
    if B.edge[j, i] < EDGE_CLR + VIA_D / 2 or B.keepout[j, i]:
        return False
    if not ignore_smd and (B.noroute[0, j, i] or B.noroute[1, j, i]):
        return False
    if B.drill.d[j, i] < HOLE_CLR + VIA_DRILL / 2:
        return False
    if not ignore_smd and B.smd.d[j, i] < VIA_D / 2 + 0.05:
        return False
    return True


def reserve_pad_vias(B, log):
    """Vias inside the ESP32's own ground pads (exposed pad array and the crowded corner pad 51).
    Placed before signal routing so no signal claims the copper underneath."""
    n_added = 0
    # ESP32 exposed pad: vias at the inner corners of the 3x3 ground pad array
    epad = [s for ref, shapes in B.pads['GND'] if ref == 'U1.49' for s in shapes if s.kind == 'rect']
    xs = sorted({round(s.p[0], 3) for s in epad})
    ys = sorted({round(s.p[1], 3) for s in epad})
    for a, b in zip(xs, xs[1:]):
        for c, d in zip(ys, ys[1:]):
            x, y = round(((a + b) / 2) / G) * G, round(((c + d) / 2) / G) * G
            if legal_via(B, x, y, ignore_smd=True):
                B.add_track('GND', 'via', (x, y))
                n_added += 1
    # module corner pad next to the USB pins: the corner is the most congested spot on the board,
    # so it gets a via inside its own 0.7 mm pad (hidden under the module, like the EPAD vias)
    for ref, shapes in B.pads['GND']:
        if ref == 'U1.51':
            x, y = shapes[0].p[0], shapes[0].p[1]
            if legal_via(B, x, y, ignore_smd=True):
                B.add_track('GND', 'via', (x, y))
                n_added += 1
    log.append(f'pad-internal GND vias: {n_added}')


def gnd_fanout(B, log):
    n_added = 0
    B.gnd_open = []
    # every other GND pad: short top-layer stub to a new via spot, an existing GND via, or the
    # module's exposed pad -- whichever is nearest
    R = NetRouter(B, 'GND', W_GND)
    epad_cells = set()
    for ref, shapes in B.pads['GND']:
        if ref == 'U1.49':
            epad_cells |= pad_cells(shapes, layers=(0,))
    for ref, shapes in B.pads['GND']:
        if ref == 'U1.49' or ref.startswith('H'):
            continue
        if ref.startswith('U1.') and ref.split('.')[1] not in ('1', '2', '11', '14', '50', '52', '53'):
            continue
        if any(1 in s.layers for s in shapes):      # through-hole GND pad already reaches B.Cu
            continue
        src = pad_cells(shapes, layers=(0,))
        x0, y0, x1, y1 = shapes[0].bbox()
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        spots = {}
        for dj in range(-50, 51):
            for di in range(-50, 51):
                x, y = round((cx + di * G) / G) * G, round((cy + dj * G) / G) * G
                if math.hypot(x - cx, y - cy) <= 2.5 and legal_via(B, x, y):
                    spots[(0, gi(y, Y0), gi(x, X0))] = (x, y)
        existing = set()
        for name, kind, data in B.tracks:
            if name == 'GND' and kind == 'via' and math.hypot(data[0] - cx, data[1] - cy) <= 3.0:
                existing.add((0, gi(data[1], Y0), gi(data[0], X0)))
        near_epad = {c for c in epad_cells if math.hypot(X0 + c[2] * G - cx, Y0 + c[1] * G - cy) <= 3.0}
        targets = set(spots) | existing | near_epad
        if not targets:
            log.append(f'  GND {ref}: no target yet')
            B.gnd_open.append((ref, shapes))
            continue
        path = R.search(src, targets, None, max_exp=200000)
        if not path:
            log.append(f'  GND {ref}: no route to a via yet')
            B.gnd_open.append((ref, shapes))
            continue
        for kind, data in path_to_tracks(path, W_GND):
            B.add_track('GND', kind, data)
        end = path[-1]
        if end in spots and end not in existing:
            if legal_via(B, *spots[end]):
                B.add_track('GND', 'via', spots[end])
                n_added += 1
    log.append(f'GND fanout vias: {n_added}')


def legal_via_static(B, x, y):
    """Via spot legal against pads, holes, edge and keep-outs only (signal tracks may be ripped)."""
    i, j = gi(x, X0), gi(y, Y0)
    if not (0 <= i < NX and 0 <= j < NY):
        return False
    net = B.code('GND')
    vthr = CLR + VIA_D / 2 + EPS
    for L in (0, 1):
        fd = B.static.d1[L, j, i] if B.static.n1[L, j, i] != net else B.static.d2[L, j, i]
        if fd < vthr:
            return False
    return not (B.edge[j, i] < EDGE_CLR + VIA_D / 2 or B.keepout[j, i] or B.noroute[0, j, i] or B.noroute[1, j, i]
                or B.drill.d[j, i] < HOLE_CLR + VIA_DRILL / 2 or B.smd.d[j, i] < VIA_D / 2 + 0.05)


def gnd_fix(B, log):
    for ref, shapes in list(B.gnd_open):
        src = pad_cells(shapes, layers=(0,))
        x0, y0, x1, y1 = shapes[0].bbox()
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        spots = {}
        for dj in range(-40, 41):
            for di in range(-40, 41):
                x, y = round((cx + di * G) / G) * G, round((cy + dj * G) / G) * G
                if math.hypot(x - cx, y - cy) <= 2.0 and legal_via_static(B, x, y):
                    spots[(0, gi(y, Y0), gi(x, X0))] = (x, y)
        if not spots:
            log.append(f'  GND {ref}: no via spot even after rip-up')
            continue
        R = NetRouter(B, 'GND', W_GND, soft=True)
        path = R.search(src, set(spots), None, max_exp=400000)
        if not path:
            log.append(f'  GND {ref}: unreachable')
            continue
        own = B.code('GND')
        vx, vy = spots[path[-1]]
        blockers = set()

        def violator(L, j, i, need):
            if B.dyn.n1[L, j, i] not in (own, -9) and B.dyn.d1[L, j, i] < need + EPS:
                return B.dyn.n1[L, j, i]
            return None
        checks = [(L, j, i, CLR + W_GND / 2) for (L, j, i) in path] + \
                 [(L, gi(vy, Y0), gi(vx, X0), CLR + VIA_D / 2) for L in (0, 1)]
        for L, j, i, need in checks:
            n = violator(L, j, i, need)
            if n is not None:
                blockers |= {nm for nm, c in B.netcode.items() if c == n}
        blockers.discard('GND')
        log.append(f'  GND {ref}: rip {sorted(blockers)} for its via at ({vx:.2f}, {vy:.2f})')
        print(log[-1], flush=True)
        B.rip(blockers)
        Rg = NetRouter(B, 'GND', W_GND)
        if not legal_via(B, vx, vy) or not all(Rg.flat[L][j * NX + i] for (L, j, i) in path):
            log.append(f'  GND {ref}: spot still blocked after rip-up, skipped')
            for nm in sorted(blockers, key=lambda n: FIRST.index(n) if n in FIRST else 99):
                route_net(B, nm, width_of(nm), log)
            continue
        for kind, data in path_to_tracks(path, W_GND):
            B.add_track('GND', kind, data)
        B.add_track('GND', 'via', (vx, vy))
        for nm in sorted(blockers, key=lambda n: FIRST.index(n) if n in FIRST else 99):
            failed = route_net(B, nm, width_of(nm), log)
            log.append(f'  reroute {nm}: {"OK" if not failed else "FAILED"}')
            print(log[-1], flush=True)


def stitching(B, log, pitch=3.0):
    n = 0
    fgnd = B.foreign('GND')
    y = Y0 + 1.5
    while y < Y1 - 1.0:
        x = X0 + 1.5
        while x < X1 - 1.0:
            gx, gy = round(x / G) * G, round(y / G) * G
            # keep a little extra room so stitching vias never crowd signal tracks
            if legal_via(B, gx, gy) and min(fgnd[L, gi(gy, Y0), gi(gx, X0)] for L in (0, 1)) > 0.65:
                B.add_track('GND', 'via', (gx, gy))
                n += 1
            x += pitch
        y += pitch
    log.append(f'stitching vias: {n}')


# ── write back to KiCad ───────────────────────────────────────────────────────
def write_board(B):
    board = B.board
    for t in list(board.GetTracks()):
        board.Remove(t)
    for z in list(board.Zones()):
        board.Remove(z)
    nets = {n: board.FindNet(n) for n in B.netcode}
    for name, kind, data in B.tracks:
        data = tuple(float(v) for v in data)
        if kind == 'seg':
            x1, y1, x2, y2, w, L = data
            L = int(L)
            t = pcbnew.PCB_TRACK(board)
            t.SetStart(pcbnew.VECTOR2I_MM(x1, y1))
            t.SetEnd(pcbnew.VECTOR2I_MM(x2, y2))
            t.SetWidth(pcbnew.FromMM(w))
            t.SetLayer(LAYERS[L])
        else:
            x, y = data
            t = pcbnew.PCB_VIA(board)
            t.SetPosition(pcbnew.VECTOR2I_MM(x, y))
            t.SetViaType(pcbnew.VIATYPE_THROUGH)
            t.SetLayerPair(pcbnew.F_Cu, pcbnew.B_Cu)
            t.SetWidth(pcbnew.FromMM(VIA_D))
            t.SetDrill(pcbnew.FromMM(VIA_DRILL))
        t.SetNet(nets[name])
        board.Add(t)
    gnd = nets['GND']
    for L in LAYERS:
        z = pcbnew.ZONE(board)
        z.SetLayer(L)
        z.SetNet(gnd)
        ol = z.Outline()
        if not hasattr(ol, 'NewOutline'):     # SWIG occasionally hands back an untyped pointer
            ol = pcbnew.SHAPE_POLY_SET()
            ol.thisown = 0                    # the zone takes ownership
            z.SetOutline(ol)
        ol.NewOutline()
        for x, y in [(X0, Y0), (X1, Y0), (X1, Y1), (X0, Y1)]:
            ol.Append(pcbnew.FromMM(x), pcbnew.FromMM(y))
        z.SetLocalClearance(pcbnew.FromMM(0.25))
        z.SetMinThickness(pcbnew.FromMM(0.25))
        z.SetThermalReliefGap(pcbnew.FromMM(0.3))
        z.SetThermalReliefSpokeWidth(pcbnew.FromMM(0.35))
        z.SetPadConnection(pcbnew.ZONE_CONNECTION_THERMAL)
        z.SetIslandRemovalMode(pcbnew.ISLAND_REMOVAL_MODE_ALWAYS)
        z.SetAssignedPriority(0)
        board.Add(z)
    for fp in board.GetFootprints():
        if fp.GetReference() in ('J1', 'U1'):
            for pad in fp.Pads():
                if pad.GetNetname() == 'GND':
                    pad.SetLocalZoneConnection(pcbnew.ZONE_CONNECTION_FULL)
    filler = pcbnew.ZONE_FILLER(board)
    filler.Fill(board.Zones())
    # a GND via must reach copper on both layers (zone fill or a GND track ending on it)
    zone_of = {z.GetLayer(): z for z in board.Zones()}
    gnd_ends = {(round(d[0], 3), round(d[1], 3), int(d[5])) for n, k, d in B.tracks if n == 'GND' and k == 'seg'} | \
               {(round(d[2], 3), round(d[3], 3), int(d[5])) for n, k, d in B.tracks if n == 'GND' and k == 'seg'}
    dead = []
    for tr in board.GetTracks():
        if tr.GetClass() != 'PCB_VIA' or tr.GetNetname() != 'GND':
            continue
        pos = tr.GetPosition()
        key = (round(MM(pos.x), 3), round(MM(pos.y), 3))
        ok = all(zone_of[L].HitTestFilledArea(L, pos, 0) or (key + (k,)) in gnd_ends for k, L in enumerate(LAYERS))
        if not ok:
            dead.append(tr)
    for tr in dead:
        board.Remove(tr)
    if dead:
        filler.Fill(board.Zones())
    print(f'removed {len(dead)} GND vias connected on one layer only', flush=True)
    pro = PCB.replace('.kicad_pcb', '.kicad_pro')
    keep = open(pro).read()          # SaveBoard rewrites the project file; keep ours
    pcbnew.SaveBoard(PCB, board)
    with open(pro, 'w') as f:
        f.write(keep)


def main():
    t0 = time.time()
    board = pcbnew.LoadBoard(PCB)
    B = Board(board)
    log = []
    print(f'grid {NX}x{NY}, {len(B.pads)} nets, setup {time.time() - t0:.1f}s', flush=True)
    reserve_pad_vias(B, log)
    route_all(B, log)
    gnd_fanout(B, log)
    gnd_fix(B, log)             # GND pads walled in by signals: rip those signals, add the via, reroute
    cleanup_dangling(B, log)
    stitching(B, log)
    write_board(B)
    segs = sum(1 for t in B.tracks if t[1] == 'seg')
    vias = sum(1 for t in B.tracks if t[1] == 'via')
    log.append(f'done: {segs} segments, {vias} vias, {time.time() - t0:.0f}s')
    print('\n'.join(l for l in log if l.startswith(('  ', 'GND', 'pad-internal', 'stitch', 'dangling', 'done'))))
    with open(os.path.join(HERE, 'tools', 'route.log'), 'w') as f:
        f.write('\n'.join(log) + '\n')


if __name__ == '__main__':
    main()
