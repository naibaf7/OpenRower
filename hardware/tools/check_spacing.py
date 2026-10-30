#!/usr/bin/env python3
"""
Placement spacing report for openrower_sensor.kicad_pcb.

Checks, per footprint pair: courtyard gap and copper pad-to-pad gap; per footprint: body
distance to the board edges.  JLCPCB requires component bodies >= 2.5 mm from edges that
carry assembly rails (boards < 70x70 mm get rails added); on this board that is the top and
bottom edge -- the left edge carries the connectors, the right edge the ESP32 antenna.

Run from hardware/:   python3 tools/check_spacing.py
"""
import itertools
import os

import pcbnew

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PCB = os.path.join(HERE, 'openrower_sensor.kicad_pcb')

MIN_COURTYARD_GAP = 0.2     # mm, between courtyards (IPC-7351B nominal courtyards already add 0.25 mm)
MIN_PAD_GAP = 0.3           # mm, copper of different footprints
MIN_RAIL_EDGE = 2.5         # mm, body to top/bottom edge (JLC rails)
MIN_EDGE = 0.5              # mm, body to left/right edge (edge-mounted parts exempt)
EDGE_PARTS = {'J1', 'J2', 'U1'}   # intentionally at the board edge
MM = pcbnew.ToMM


def bbox(rect):
    return (MM(rect.GetLeft()), MM(rect.GetTop()), MM(rect.GetRight()), MM(rect.GetBottom()))


def gap(a, b):
    dx = max(b[0] - a[2], a[0] - b[2], 0)
    dy = max(b[1] - a[3], a[1] - b[3], 0)
    if dx == 0 and dy == 0:
        return -min(a[2] - b[0], b[2] - a[0], a[3] - b[1], b[3] - a[1])  # overlap depth
    return (dx ** 2 + dy ** 2) ** 0.5


def main():
    board = pcbnew.LoadBoard(PCB)
    edge = bbox(board.GetBoardEdgesBoundingBox())
    fps = {fp.GetReference(): fp for fp in board.GetFootprints()}
    court = {r: bbox(fp.GetCourtyard(pcbnew.F_CrtYd).BBox()) for r, fp in fps.items()}
    body = {}
    for r, fp in fps.items():
        fab = [bbox(g.GetBoundingBox()) for g in fp.GraphicalItems() if g.GetLayer() == pcbnew.F_Fab
               and g.GetClass() != 'PCB_TEXT']
        if fab:
            body[r] = (min(b[0] for b in fab), min(b[1] for b in fab), max(b[2] for b in fab), max(b[3] for b in fab))
    pads = {r: [bbox(p.GetBoundingBox()) for p in fp.Pads() if p.IsOnCopperLayer()] for r, fp in fps.items()}

    problems = []
    close = []
    for a, b in itertools.combinations(sorted(fps), 2):
        g = gap(court[a], court[b])
        if g < MIN_COURTYARD_GAP:
            problems.append(f'courtyard gap {a}-{b}: {g:.2f} mm')
        elif g < 0.5:
            close.append((g, a, b))
        pg = min((gap(pa, pb) for pa in pads[a] for pb in pads[b]), default=99)
        if pg < MIN_PAD_GAP:
            problems.append(f'pad gap {a}-{b}: {pg:.2f} mm')
    for r, bb in body.items():
        if r.startswith('H'):
            continue
        top, bot = bb[1] - edge[1], edge[3] - bb[3]
        left, right = bb[0] - edge[0], edge[2] - bb[2]
        if min(top, bot) < MIN_RAIL_EDGE:
            problems.append(f'{r} body {min(top, bot):.2f} mm from top/bottom (rail) edge')
        if r not in EDGE_PARTS and min(left, right) < MIN_EDGE:
            problems.append(f'{r} body {min(left, right):.2f} mm from left/right edge')

    print(f'{len(fps)} footprints, board {edge[2] - edge[0]:.1f} x {edge[3] - edge[1]:.1f} mm')
    print(f'closest courtyard pairs (< 0.5 mm): ' + ', '.join(f'{a}-{b} {g:.2f}' for g, a, b in sorted(close)[:15]))
    print('\n'.join(problems) if problems else 'no spacing problems')
    return len(problems)


if __name__ == '__main__':
    raise SystemExit(main())
