#!/usr/bin/env python3
"""
OpenRower sensor board -- PCB placement generator (KiCad 10, pcbnew API).

Builds openrower_sensor.kicad_pcb from the schematic's netlist (exported with kicad-cli),
placing every footprint at the position in PLACEMENT below.  Footprints are linked to
their schematic symbols (same UUID paths KiCad uses), so "Update PCB from Schematic"
reports no changes afterwards.

Run from hardware/:   python3 tools/gen_pcb.py [--force]
Refuses to touch a board that already contains tracks/vias/zones (i.e. after routing)
unless --force is given -- regenerating would throw the routing away.

    python3 tools/gen_pcb.py --silk
only replaces the board's silkscreen texts (SILK_TEXT, BACK_TEXT) and keeps the routing.
"""

import os
import subprocess
import sys
import tempfile

import pcbnew

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sexpr import find, first, parse  # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCH = os.path.join(HERE, 'openrower_sensor.kicad_sch')
PCB = os.path.join(HERE, 'openrower_sensor.kicad_pcb')
FPLIBS = {'CustomFootprints': os.path.join(HERE, 'CustomFootprints.pretty')}
SYSFP = '/usr/share/kicad/footprints'

# Board: 55 x 45 mm, origin at (100, 100).  Left edge: USB-C (J1) and sensor jack (J2).
# Right edge: ESP32 antenna (keep-out X 149.4..154.8, nothing else within ~4 mm).
X0, Y0, W, H = 100.0, 100.0, 55.0, 45.0

PLACEMENT = {
    # ref: (x, y, rotation_deg).  Courtyard gaps >= 0.3 mm; bodies >= 2.5 mm from the top and
    # bottom edges (JLC assembly rails go there -- left edge = connectors, right edge = antenna).
    # mechanics / connectors
    'H1': (103.5, 103.5, 0), 'H2': (151.5, 103.5, 0), 'H3': (103.5, 141.5, 0), 'H4': (151.5, 141.5, 0),
    'J2': (105.4, 112.0, 90),          # 2.5 mm jack: body face on the left edge, nose overhangs 1.5 mm
    'J1': (103.65, 131.5, 270),        # USB-C: receptacle face flush with the left edge
    'U1': (146.0, 122.5, 270),         # ESP32-C3-MINI-1: antenna on the right edge
    'SW1': (141.2, 106.2, 0),          # RESET, above EN
    'SW2': (141.2, 139.4, 0),          # BOOT, below GPIO9

    # RING: TVS, cap, blocking diodes, sense resistor (right of the jack's ring pad)
    'D2': (113.4, 105.2, 0), 'C8': (109.0, 104.8, 0), 'C13': (117.4, 105.2, 0),
    'D6': (113.6, 108.9, 0), 'D7': (113.6, 111.8, 0),
    'R27': (112.4, 114.8, 90), 'C15': (116.0, 114.4, 90),

    # hall supply switches
    'U5': (120.0, 108.8, 0), 'R23': (118.6, 111.9, 0),
    'U6': (120.0, 114.6, 0), 'R25': (118.6, 117.6, 0),
    'C12': (123.4, 108.4, 90), 'R24': (123.4, 110.8, 90), 'C14': (123.4, 113.4, 90), 'R26': (123.4, 115.8, 90),

    # TIP: TVS, pull-up, comparator input resistors
    'D1': (104.9, 119.6, 0), 'D3': (110.6, 119.8, 0), 'R10': (114.4, 119.8, 0),
    'R11': (117.6, 120.4, 0), 'R16': (117.6, 123.2, 0), 'C9': (121.4, 123.2, 0),

    # comparator U3 and its networks
    'U3': (129.0, 118.8, 0),
    'D4': (122.0, 119.2, 0), 'R14': (126.6, 114.4, 0), 'R12': (125.8, 123.6, 90), 'R13': (127.2, 123.6, 90),
    'D5': (123.4, 126.8, 0), 'R17': (127.6, 126.8, 90), 'R18': (129.0, 126.8, 90),
    'R19': (130.8, 123.8, 90), 'R20': (132.2, 123.8, 90),
    'R21': (134.2, 120.2, 90), 'R22': (134.2, 117.6, 90),
    'C10': (131.6, 114.4, 0), 'C11': (134.0, 114.4, 0),

    # ESP32 support (west and top of the module)
    'R28': (136.2, 117.6, 90), 'C16': (136.2, 115.2, 90), 'R5': (135.6, 125.7, 0),
    'C5': (146.6, 113.4, 90), 'C6': (144.9, 113.4, 90), 'R6': (143.4, 113.4, 90),
    'R4': (141.9, 113.4, 90), 'C7': (140.4, 113.4, 90), 'R15': (138.9, 113.4, 90),

    # status LEDs (below the module; >= 3 mm from the antenna keep-out)
    'R7': (139.8, 131.6, 0), 'LED1': (139.8, 133.6, 0),
    'R8': (143.2, 131.6, 0), 'LED2': (143.2, 133.6, 0),
    'R9': (146.6, 131.6, 0), 'LED3': (146.6, 133.6, 0),

    # USB ESD + CC, power chain (bottom-left)
    'U4': (113.0, 131.5, 0), 'R1': (110.0, 128.4, 90), 'R2': (110.0, 134.6, 270),
    'F1': (112.0, 139.4, 0), 'C1': (116.2, 139.4, 90), 'C2': (117.8, 139.4, 90),
    'R3': (119.2, 136.4, 90), 'U2': (121.2, 139.6, 0),
    'C3': (124.9, 139.4, 90), 'C4': (126.5, 139.4, 90),
}

FAB_REFS = {'D1', 'U1', 'U5', 'U6', 'D2', 'D3', 'D4', 'D5', 'D6', 'D7', 'F1', 'H1', 'H2', 'H3', 'H4', 'LED1', 'LED2', 'LED3', 'SW1', 'SW2'}

SILK_TEXT = [
    ('SENSOR', (103.2, 117.2), 0), ('USB', (103.6, 124.8), 0),
    ('PWR', (139.8, 135.4), 0), ('BLE', (143.2, 135.4), 0), ('DATA', (146.8, 135.4), 0),
    ('RST', (136.3, 106.2), 90), ('BOOT', (136.3, 139.4), 90),
    ('OpenRower sensor r2', (124.0, 133.4), 0),
]

# Back side (no parts there): licence notice with the Source Location, which CERN-OHL-S-2.0
# section 4 asks products to display (see hardware/LICENSE).
BACK_TEXT = ('OpenRower sensor board rev 2\ngithub.com/naibaf7/OpenRower\nCERN-OHL-S-2.0\n'
             '(c) 2026 Fabian Tschopp', (127.5, 122.5))


def export_netlist():
    tmp = tempfile.NamedTemporaryFile(suffix='.net', delete=False).name
    subprocess.run(['kicad-cli', 'sch', 'export', 'netlist', '--format', 'kicadsexpr', '-o', tmp, SCH],
                   check=True, capture_output=True)
    return parse(open(tmp).read())


def load_fp(fpid):
    lib, name = fpid.split(':')
    path = FPLIBS.get(lib, os.path.join(SYSFP, lib + '.pretty'))
    fp = pcbnew.FootprintLoad(path, name)
    if fp is None:
        sys.exit(f'footprint {fpid} not found')
    return fp


CONTENT_TAGS = {'footprint', 'segment', 'arc', 'via', 'zone', 'net', 'group', 'generated', 'dimension',
                'image', 'table', 'target'}


def empty_template():
    """Copy of the current board with only its setup (stackup, layers, plot settings) kept.
    Clearing at the s-expression level avoids pcbnew's Drawings() iterator, which is broken
    in this SWIG build."""
    tree = parse(open(PCB).read())
    if not force_ok(tree):
        sys.exit('board already has tracks/zones (routed?) -- refusing to regenerate; use --force')
    keep = [n for n in tree if not (isinstance(n, list) and n and
                                    (n[0] in CONTENT_TAGS or str(n[0]).startswith('gr_')))]
    from sexpr import dump
    tmp = tempfile.NamedTemporaryFile(suffix='.kicad_pcb', delete=False).name
    with open(tmp, 'w') as f:
        f.write(dump(keep) + '\n')
    return tmp


def force_ok(tree):
    routed = any(isinstance(n, list) and n and n[0] in ('segment', 'via', 'zone', 'arc') for n in tree)
    return not routed or '--force' in sys.argv


def add_silk_text(board):
    for text, (x, y), rot in SILK_TEXT:
        t = pcbnew.PCB_TEXT(board)
        t.SetText(text)
        t.SetPosition(pcbnew.VECTOR2I_MM(x, y))
        t.SetLayer(pcbnew.F_SilkS)
        t.SetTextSize(pcbnew.VECTOR2I_MM(0.8, 0.8))
        t.SetTextThickness(pcbnew.FromMM(0.15))
        t.SetTextAngleDegrees(rot)
        board.Add(t)
    text, (x, y) = BACK_TEXT
    t = pcbnew.PCB_TEXT(board)
    t.SetText(text)
    t.SetPosition(pcbnew.VECTOR2I_MM(x, y))
    t.SetLayer(pcbnew.B_SilkS)
    t.SetMirrored(True)                  # reads correctly when the board is turned over
    t.SetTextSize(pcbnew.VECTOR2I_MM(1.0, 1.0))
    t.SetTextThickness(pcbnew.FromMM(0.15))
    board.Add(t)


def save(board):
    pro = PCB.replace('.kicad_pcb', '.kicad_pro')
    keep = open(pro).read()          # SaveBoard rewrites the project file; keep ours
    pcbnew.SaveBoard(PCB, board)
    with open(pro, 'w') as f:
        f.write(keep)


def replace_silk_text():
    board = pcbnew.LoadBoard(PCB)
    old = [d for d in board.GetDrawings()
           if d.GetClass() == 'PCB_TEXT' and d.GetLayer() in (pcbnew.F_SilkS, pcbnew.B_SilkS)]
    for d in old:
        board.Remove(d)
    add_silk_text(board)
    save(board)
    print(f'wrote {PCB}: replaced {len(old)} silkscreen texts')


def main():
    if '--silk' in sys.argv:
        return replace_silk_text()
    board = pcbnew.LoadBoard(empty_template())

    net = export_netlist()
    pinnet = {}
    for n in find(first(net, 'nets'), 'net'):
        name = first(n, 'name')[1]
        for nd in find(n, 'node'):
            pinnet[(first(nd, 'ref')[1], first(nd, 'pin')[1])] = name

    netinfo = {}

    def get_net(name):
        if name not in netinfo:
            ni = pcbnew.NETINFO_ITEM(board, name)
            board.Add(ni)
            netinfo[name] = ni
        return netinfo[name]

    placed = set()
    for comp in find(first(net, 'components'), 'comp'):
        ref = first(comp, 'ref')[1]
        if ref not in PLACEMENT:
            sys.exit(f'{ref} has no PLACEMENT entry')
        fpid = first(comp, 'footprint')[1]
        fp = load_fp(fpid)
        fp.SetFPID(pcbnew.LIB_ID(*fpid.split(':')))
        fp.SetReference(ref)
        ds = first(comp, 'datasheet')
        desc = first(comp, 'description')
        fp.SetField('Datasheet', ds[1] if ds else '')
        fp.SetField('Description', desc[1] if desc else '')
        fp.GetField('Datasheet').SetVisible(False)
        fp.GetField('Description').SetVisible(False)
        fp.SetValue(first(comp, 'value')[1])
        tstamps = first(comp, 'tstamps')
        fp.SetPath(pcbnew.KIID_PATH('/' + tstamps[1]))
        fp.SetSheetname('/')
        fp.SetSheetfile(os.path.basename(SCH))
        for f in find(comp, 'property'):
            key, val = first(f, 'name')[1], (first(f, 'value') or [None, ''])[1]
            if key in ('LCSC', 'MPN') and val:
                fp.SetField(key, val)
                fp.GetField(key).SetVisible(False)
        x, y, rot = PLACEMENT[ref]
        if ref.startswith(('R', 'C')) or ref in FAB_REFS:
            # dense 0402/0603 area: reference goes to the assembly drawing (F.Fab), not silk
            fp.Reference().SetLayer(pcbnew.F_Fab)
            fp.Reference().SetTextSize(pcbnew.VECTOR2I_MM(0.5, 0.5))
            fp.Reference().SetTextThickness(pcbnew.FromMM(0.08))
            fp.Reference().SetPosition(pcbnew.VECTOR2I(0, 0))   # relative to footprint origin before placement
        fp.SetPosition(pcbnew.VECTOR2I_MM(x, y))
        fp.SetOrientationDegrees(rot)
        board.Add(fp)
        for pad in fp.Pads():
            name = pinnet.get((ref, pad.GetNumber()))
            if name:
                pad.SetNet(get_net(name))
        placed.add(ref)
    extra = set(PLACEMENT) - placed
    if extra:
        sys.exit(f'PLACEMENT has refs not in the schematic: {sorted(extra)}')

    # board outline
    pts = [(X0, Y0), (X0 + W, Y0), (X0 + W, Y0 + H), (X0, Y0 + H)]
    for a, b in zip(pts, pts[1:] + pts[:1]):
        seg = pcbnew.PCB_SHAPE(board)
        seg.SetShape(pcbnew.SHAPE_T_SEGMENT)
        seg.SetStart(pcbnew.VECTOR2I_MM(*a))
        seg.SetEnd(pcbnew.VECTOR2I_MM(*b))
        seg.SetLayer(pcbnew.Edge_Cuts)
        seg.SetWidth(pcbnew.FromMM(0.05))
        board.Add(seg)

    add_silk_text(board)

    # place/drill origin at the bottom-left corner: CPL and gerbers share it (tools/make_fab.py)
    board.GetDesignSettings().SetAuxOrigin(pcbnew.VECTOR2I_MM(X0, Y0 + H))

    board.SetFileName(PCB)
    save(board)
    print(f'wrote {PCB}: {len(placed)} footprints, {len(netinfo)} nets')


if __name__ == '__main__':
    main()
