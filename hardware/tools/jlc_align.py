#!/usr/bin/env python3
"""
Derive JLCPCB placement corrections for every LCSC part on the board.

JLC places parts using the part's EasyEDA footprint (its own origin and 0-degree orientation),
which often differs from the KiCad footprint.  For each LCSC number this script fetches the
EasyEDA footprint, matches its pads to the KiCad footprint's pads (by pin number, else by
geometry; 2-pin polarized parts by the EasyEDA symbol's K/A pin names) and solves

        p_kicad = R(theta) * p_easyeda + t          (footprint-local, y down, CCW rotation)

The result goes to tools/jlc_corrections.json, which tools/make_fab.py applies to the CPL:
        JLC rotation = KiCad rotation + theta,   JLC position = KiCad position + R(phi) * t

Run from hardware/ (needs network):   python3 tools/jlc_align.py
"""
import json
import math
import os
import sys
import subprocess

import pcbnew

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PCB = os.path.join(HERE, 'openrower_sensor.kicad_pcb')
OUT = os.path.join(HERE, 'tools', 'jlc_corrections.json')
UNIT = 0.254     # EasyEDA unit = 10 mil
HDRS = {'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36',
        'Accept': 'application/json, text/javascript, */*; q=0.01', 'Referer': 'https://easyeda.com/'}

# KiCad pad name -> datasheet / EasyEDA pad number, where the KiCad footprint names pads differently
PAD_ALIAS = {
    # KiCad pad name at local position -> datasheet pin, for footprints that name pads differently
    'Jack_2.5mm_SHOUHAN_PJ-242': {(-2.9, -3.7): '3', (2.9, -3.7): '2', (-2.9, 0.0): '6', (2.9, 0.0): '4',
                                  (-2.9, 3.7): '7', (2.9, 3.7): '5'},
    'SW_Push_1P1T_XKB_TS-1187A': None,          # pads 1,1,2,2 vs 1..4: geometry (0/180 equivalent)
}
POLAR = {'LED_0603_1608Metric', 'D_SOD-123'}   # KiCad pad 1 = cathode (D_SOD-123F here is a bidirectional TVS)


def rot(theta, x, y):
    a = math.radians(theta)
    return (x * math.cos(a) + y * math.sin(a), -x * math.sin(a) + y * math.cos(a))


CACHE = os.path.join(HERE, 'tools', 'easyeda_cache')


def easyeda(lcsc):
    path = os.path.join(CACHE, f'{lcsc}.json')
    if not os.path.exists(path):        # EasyEDA rejects urllib; curl with browser headers works
        os.makedirs(CACHE, exist_ok=True)
        url = f'https://easyeda.com/api/products/{lcsc}/components?version=6.4.19.5'
        cmd = ['curl', '-s', '-m', '30', '-o', path, url] + sum((['-H', f'{k}: {v}'] for k, v in HDRS.items()), [])
        subprocess.run(cmd, check=True)
    d = json.load(open(path))['result']
    pk = d['packageDetail']['dataStr']
    ox, oy = float(pk['head']['x']), float(pk['head']['y'])
    pads = []
    for s in pk['shape']:
        if s.startswith('PAD~'):
            f = s.split('~')
            pads.append((f[8], (float(f[2]) - ox) * UNIT, (float(f[3]) - oy) * UNIT))
    pin_names = {}
    for s in d['dataStr']['shape']:          # schematic symbol pins: number -> name
        if s.startswith('P~'):
            parts = s.split('^^')
            head = parts[0].split('~')
            num = head[3] if len(head) > 3 else ''
            name = ''
            for p in parts[1:]:
                q = p.split('~')
                if len(q) > 4 and q[0] in ('0', '1') and q[4] and not q[4].replace('.', '').isdigit():
                    name = q[4]
                    break
            pin_names[num] = name
    return d['packageDetail']['title'], pads, pin_names


def kicad_pads(fp):
    fp = pcbnew.FOOTPRINT(fp)               # copy, unrotated at the origin
    fp.SetOrientationDegrees(0)
    fp.SetPosition(pcbnew.VECTOR2I(0, 0))
    return [(p.GetNumber(), pcbnew.ToMM(p.GetPosition().x), pcbnew.ToMM(p.GetPosition().y))
            for p in fp.Pads() if p.GetAttribute() != pcbnew.PAD_ATTRIB_NPTH and p.IsOnLayer(pcbnew.F_Cu)]


def solve(kp, ep, by_number):
    best = None
    for theta in (0, 90, 180, 270):
        er = [(n, *rot(theta, x, y)) for n, x, y in ep]
        if by_number:
            # only pin numbers that occur exactly once on both sides (skips multi-square exposed
            # pads and corner pads that the two libraries number differently)
            kc_ = {}
            for k in kp:
                kc_[k[0]] = kc_.get(k[0], 0) + 1
            ec_ = {}
            for e in er:
                ec_[e[0]] = ec_.get(e[0], 0) + 1
            uniq = {n for n in kc_ if kc_[n] == 1 and ec_.get(n) == 1}
            pairs = [(k, e) for k in kp for e in er if k[0] == e[0] and k[0] in uniq]
            if len(pairs) < 2:
                return None
        else:   # geometry: align centroids, then nearest neighbours
            kc = (sum(p[1] for p in kp) / len(kp), sum(p[2] for p in kp) / len(kp))
            ec = (sum(p[1] for p in er) / len(er), sum(p[2] for p in er) / len(er))
            sh = (kc[0] - ec[0], kc[1] - ec[1])
            pairs = [(k, min(er, key=lambda e: math.hypot(e[1] + sh[0] - k[1], e[2] + sh[1] - k[2]))) for k in kp]
        mx = sum(k[1] - e[1] for k, e in pairs) / len(pairs)
        my = sum(k[2] - e[2] for k, e in pairs) / len(pairs)
        err = math.sqrt(sum((k[1] - e[1] - mx) ** 2 + (k[2] - e[2] - my) ** 2 for k, e in pairs) / len(pairs))
        # offset from the pad-pattern bounding boxes: robust to pads of different length
        tx = (min(k[1] for k in kp) + max(k[1] for k in kp)) / 2 - (min(e[1] for e in er) + max(e[1] for e in er)) / 2
        ty = (min(k[2] for k in kp) + max(k[2] for k in kp)) / 2 - (min(e[2] for e in er) + max(e[2] for e in er)) / 2
        if err < 0.02:     # pads coincide exactly: their mean offset is exact
            tx, ty = mx, my
        cand = (round(err, 4), theta, tx, ty, pairs)
        if best is None or cand[0] < best[0] - 1e-6:
            best = cand
    return best


def main():
    board = pcbnew.LoadBoard(PCB)
    seen, out = {}, {}
    for fp in board.GetFootprints():
        lcsc = fp.GetFieldText('LCSC') if fp.HasField('LCSC') else ''
        if not lcsc or lcsc in seen:
            continue
        seen[lcsc] = fp
    for lcsc, fp in sorted(seen.items()):
        name = fp.GetFPID().GetLibItemName().wx_str()
        title, ep, pins = easyeda(lcsc)
        kp = kicad_pads(fp)
        alias = PAD_ALIAS.get(name, 1)
        if isinstance(alias, dict):
            kp = [(alias.get((round(x, 2), round(y, 2)), n), x, y) for n, x, y in kp]
        by_number = alias is not None and sum(any(e[0] == k[0] for e in ep) for k in kp) >= 2
        res = solve(kp, ep, by_number)
        if res is None:
            res = solve(kp, ep, False)
            by_number = False
        err, theta, tx, ty, pairs = res
        note = ''
        if name in POLAR:
            # EasyEDA pad whose symbol pin is the cathode must land on KiCad pad 1
            cath = [n for n, nm in pins.items() if nm.upper() in ('K', 'C', '-', 'CATHODE')]
            if cath:
                k1 = next(p for p in kp if p[0] == '1')
                kc = [e for n, *e in [(e[0], e[1], e[2]) for e in ep] if n == cath[0]]
                for th in (theta, (theta + 180) % 360):
                    ex, ey = rot(th, *[(x, y) for n, x, y in ep if n == cath[0]][0])
                    others = [rot(th, x, y) for n, x, y in ep]
                    cx = sum(o[0] for o in others) / len(others)
                    cy = sum(o[1] for o in others) / len(others)
                    kcx = sum(p[1] for p in kp) / len(kp)
                    kcy = sum(p[2] for p in kp) / len(kp)
                    if math.hypot(ex - cx + kcx - k1[1], ey - cy + kcy - k1[2]) < 0.3:
                        if th != theta:
                            theta = th
                            tx, ty = kcx - cx, kcy - cy
                        note = f'polarity via symbol pin {cath[0]}={pins[cath[0]]}'
                        break
                else:
                    note = 'POLARITY UNRESOLVED'
            else:
                note = 'no K/A pin names: check polarity in preview'
        out[lcsc] = {'footprint': name, 'easyeda': title, 'theta': theta, 'dx': round(tx, 4), 'dy': round(ty, 4),
                     'rms_mm': err, 'matched_by': 'number' if by_number else 'geometry', 'note': note}
        print(f'{lcsc:10} {name:42} theta={theta:3}  t=({tx:+.3f},{ty:+.3f})  rms={err:.3f} {"num" if by_number else "geo"} {note}')
    json.dump(out, open(OUT, 'w'), indent=1)
    print('->', OUT)


if __name__ == '__main__':
    sys.exit(main())
