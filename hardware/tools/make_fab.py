#!/usr/bin/env python3
"""
OpenRower sensor board -- JLCPCB fabrication outputs.

Writes bom/jlcpcb_bom.csv and bom/jlcpcb_cpl.csv from openrower_sensor.kicad_pcb (LCSC numbers
come from the symbols' LCSC field).  Once the board is routed it also writes gerbers + drill
files to fab/ and zips them.  Coordinates use the board's place/drill origin (bottom-left).

JLC places parts with their EasyEDA footprints, whose origin and 0-degree orientation differ
from KiCad's for many packages.  The per-part corrections in tools/jlc_corrections.json (made by
tools/jlc_align.py from the actual EasyEDA footprints) are applied to the CPL:
    rotation = KiCad rotation + theta,   position = KiCad position + R(rotation) * t

Run from hardware/:   python3 tools/make_fab.py
"""
import csv
import json
import math
import os
import shutil
import subprocess

import pcbnew

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PCB = os.path.join(HERE, 'openrower_sensor.kicad_pcb')
BOMDIR = os.path.join(HERE, 'bom')
FABDIR = os.path.join(HERE, 'fab')


def natural(r):
    head = r.rstrip('0123456789')
    return (head, int(r[len(head):] or 0))


CORR = os.path.join(HERE, 'tools', 'jlc_corrections.json')


def jlc_place(fp, lcsc, corr):
    """KiCad footprint position/orientation -> JLC (EasyEDA footprint) position/orientation."""
    if lcsc not in corr:
        raise SystemExit(f'{fp.GetReference()} ({lcsc}) has no JLC correction: run tools/jlc_align.py')
    c = corr[lcsc]
    phi = fp.GetOrientationDegrees() % 360
    a = math.radians(phi)
    tx, ty = c['dx'], c['dy']
    pos = fp.GetPosition()
    qx = pcbnew.ToMM(pos.x) + tx * math.cos(a) + ty * math.sin(a)
    qy = pcbnew.ToMM(pos.y) - tx * math.sin(a) + ty * math.cos(a)
    return qx, qy, (phi + c['theta']) % 360


def main():
    board = pcbnew.LoadBoard(PCB)
    corr = json.load(open(CORR))
    origin = board.GetDesignSettings().GetAuxOrigin()
    rows, groups = [], {}
    for fp in board.GetFootprints():
        attrs = fp.GetAttributes()
        ref = fp.GetReference()
        lcsc = fp.GetFieldText('LCSC') if fp.HasField('LCSC') else ''
        if not (attrs & pcbnew.FP_EXCLUDE_FROM_BOM):
            if not lcsc:
                raise SystemExit(f'{ref} has no LCSC number')
            key = (fp.GetFPID().GetLibItemName().wx_str(), lcsc)
            groups.setdefault(key, []).append((ref, fp.GetValue()))
        if not (attrs & pcbnew.FP_EXCLUDE_FROM_POS_FILES):
            qx, qy, rot = jlc_place(fp, lcsc, corr)
            rows.append((ref, qx - pcbnew.ToMM(origin.x), pcbnew.ToMM(origin.y) - qy,
                         'Top' if fp.GetLayer() == pcbnew.F_Cu else 'Bottom', rot))


    os.makedirs(BOMDIR, exist_ok=True)
    with open(os.path.join(BOMDIR, 'jlcpcb_bom.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['Comment', 'Designator', 'Footprint', 'LCSC Part #'])
        for (fpname, lcsc), items in sorted(groups.items(), key=lambda kv: natural(min(r for r, _ in kv[1]))):
            refs = sorted((r for r, _ in items), key=natural)
            values = sorted({v for _, v in items})
            w.writerow(['/'.join(values), ','.join(refs), fpname, lcsc])
    with open(os.path.join(BOMDIR, 'jlcpcb_cpl.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['Designator', 'Mid X', 'Mid Y', 'Layer', 'Rotation'])
        for ref, x, y, layer, rot in sorted(rows, key=lambda r: natural(r[0])):
            w.writerow([ref, f'{x:.3f}mm', f'{y:.3f}mm', layer, f'{rot:.1f}'])
    print(f'BOM: {len(groups)} lines, CPL: {len(rows)} parts -> {BOMDIR}')

    if not len(board.GetTracks()):
        print('board not routed yet: skipping gerbers/drill')
        return
    shutil.rmtree(FABDIR, ignore_errors=True)
    os.makedirs(FABDIR)
    layers = 'F.Cu,B.Cu,F.Paste,F.Silkscreen,B.Silkscreen,F.Mask,B.Mask,Edge.Cuts'
    subprocess.run(['kicad-cli', 'pcb', 'export', 'gerbers', '--use-drill-file-origin', '-l', layers,
                    '-o', FABDIR + '/', PCB], check=True)
    subprocess.run(['kicad-cli', 'pcb', 'export', 'drill', '--drill-origin', 'plot', '--excellon-separate-th',
                    '-o', FABDIR + '/', PCB], check=True)
    shutil.make_archive(os.path.join(HERE, 'openrower_sensor_gerbers'), 'zip', FABDIR)
    print('gerbers + drill -> fab/, openrower_sensor_gerbers.zip')


if __name__ == '__main__':
    main()
