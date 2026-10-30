#!/usr/bin/env python3
"""
OpenRower sensor board -- schematic generator (KiCad 10).

The circuit is defined in PARTS below: one entry per symbol with its pin -> net map.
Every pin gets a short wire stub plus a net label (or a power symbol), so the whole
netlist is explicit and reviewable in this file.  Symbol UUIDs are derived from the
reference designator, so regenerating keeps the PCB linked.

Run from hardware/:   python3 tools/gen_schematic.py
Overwrites openrower_sensor.kicad_sch.  Hand edits made in eeschema are lost on re-run.
"""

import copy
import math
import os
import re
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sexpr import Q, dump, find, first, parse, prop  # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(HERE, 'openrower_sensor.kicad_sch')
SYSLIB = '/usr/share/kicad/symbols'
PRJLIB = os.path.join(HERE, 'OpenRower.kicad_sym')
PROJECT = 'openrower_sensor'
ROOT_UUID = '9666c3e2-3312-4c6d-a0cc-5d61efcc6820'
NS = uuid.UUID('3b0f0d2e-5c43-4f3b-9d0e-6f70656e726f')  # fixed namespace for stable UUIDs

STUB = 2.54

# ── footprints / parts ─────────────────────────────────────────────────────────
R0402 = 'Resistor_SMD:R_0402_1005Metric'
R0603 = 'Resistor_SMD:R_0603_1608Metric'
C0402 = 'Capacitor_SMD:C_0402_1005Metric'
C0603 = 'Capacitor_SMD:C_0603_1608Metric'
LED0603 = 'LED_SMD:LED_0603_1608Metric'
SOD123 = 'Diode_SMD:D_SOD-123'
SOD123F = 'Diode_SMD:D_SOD-123F'
SOT23 = 'Package_TO_SOT_SMD:SOT-23'
SOT235 = 'Package_TO_SOT_SMD:SOT-23-5'
SOT236 = 'Package_TO_SOT_SMD:SOT-23-6'

# value -> (LCSC, MPN) for JLCPCB assembly; all verified in the JLC parts library 2026-10-17
RES = {
    '100R': ('C25076', '0402WGF1000TCE'), '330R': ('C25104', '0402WGF3300TCE'),
    '1k': ('C11702', '0402WGF1001TCE'), '5.1k': ('C25905', '0402WGF5101TCE'),
    '10k': ('C25744', '0402WGF1002TCE'), '15k': ('C25756', '0402WGF1502TCE'),
    '22k': ('C25768', '0402WGF2202TCE'), '100k': ('C25741', '0402WGF1003TCE'),
    '1M': ('C26083', '0402WGF1004TCE'),
}
RES0603 = {'10k': ('C25804', '0603WAF1002T5E')}
CAP = {
    '10nF': ('C15195', 'CL05B103KB5NNNC', C0402),   # 50 V X7R
    '100nF': ('C1525', 'CL05B104KO5NNNC', C0402),   # 16 V X7R
    '1uF': ('C52923', 'CL05A105KA5NQNC', C0402),    # 25 V X5R
    '1uF/50V': ('C15849', 'CL10A105KB8NNNC', C0603),
    '10uF': ('C19702', 'CL10A106KP8NNNC', C0603),   # 10 V X5R
}

PARTS = []   # filled by the helpers below


def part(ref, lib_id, value, fp, pos, pins, rot=0, unit=1, lcsc='', mpn='', in_bom=True):
    PARTS.append(dict(ref=ref, lib_id=lib_id, value=value, fp=fp, pos=pos, rot=rot, unit=unit,
                      pins=pins, lcsc=lcsc, mpn=mpn, in_bom=in_bom))


def R(ref, value, n1, n2, pos, horiz=False, fp=R0402):
    lcsc, mpn = (RES0603 if fp == R0603 else RES)[value]
    part(ref, 'Device:R', value, fp, pos, {'1': n1, '2': n2}, rot=90 if horiz else 0, lcsc=lcsc, mpn=mpn)


def C(ref, value, n1, n2, pos, horiz=False):
    lcsc, mpn, fp = CAP[value]
    part(ref, 'Device:C', value.split('/')[0], fp, pos, {'1': n1, '2': n2}, rot=90 if horiz else 0,
         lcsc=lcsc, mpn=mpn)


def D_schottky(ref, anode, cathode, pos, rot=0):
    # Device:D_Schottky: pin 1 = K (left), pin 2 = A (right); D_SOD-123 pad 1 = cathode
    part(ref, 'Device:D_Schottky', 'B5819W', SOD123, pos, {'1': cathode, '2': anode}, rot=rot,
         lcsc='C8598', mpn='B5819W SL')


def D_tvs(ref, net, pos):
    part(ref, 'Device:D_TVS', 'SMF24CA', SOD123F, pos, {'1': net, '2': 'GND'}, rot=270,
         lcsc='C123811', mpn='SMF24CA')


def clamp(ref, net, pos):
    # Diode:BAT54S: 1 = A (diode 1 anode) -> GND, 2 = K (diode 2 cathode) -> +3V3, 3 = common
    part(ref, 'Diode:BAT54S', 'BAT54S', SOT23, pos, {'1': 'GND', '2': '+3V3', '3': net}, rot=90,
         lcsc='C47546', mpn='BAT54S,215')


def LED(ref, value, anode, pos, lcsc, mpn):
    # Device:LED pin 1 = K, pin 2 = A; rot 90 puts the anode on top
    part(ref, 'Device:LED', value, LED0603, pos, {'1': 'GND', '2': anode}, rot=90, lcsc=lcsc, mpn=mpn)


# ── 1. USB-C input, protection, 3.3 V LDO ──────────────────────────────────────
part('J1', 'Connector:USB_C_Receptacle_USB2.0_16P', 'USB-C', 'CustomFootprints:USB_C_Receptacle_HRO_TYPE-C-31-M-12_Edge',
     (38.1, 96.52),
     {'A1': 'GND', 'A12': 'GND', 'B1': 'GND', 'B12': 'GND', 'SH': 'GND',
      'A4': 'VBUS', 'A9': 'VBUS', 'B4': 'VBUS', 'B9': 'VBUS',
      'A5': 'CC1', 'B5': 'CC2', 'A6': 'USB_DP', 'B6': 'USB_DP', 'A7': 'USB_DM', 'B7': 'USB_DM',
      'A8': None, 'B8': None},
     lcsc='C165948', mpn='TYPE-C-31-M-12')
part('F1', 'Device:Fuse', '500mA PPTC', 'Fuse:Fuse_1206_3216Metric', (38.1, 53.34), {'1': 'VBUS', '2': '+5V'},
     rot=90, lcsc='C70077', mpn='nSMD050-33V')
R('R1', '5.1k', 'CC1', 'GND', (76.2, 111.76))
R('R2', '5.1k', 'CC2', 'GND', (88.9, 111.76))
part('U4', 'Power_Protection:USBLC6-2SC6', 'USBLC6-2SC6', SOT236, (124.46, 111.76),
     {'1': 'USB_DM', '6': 'USB_DM', '3': 'USB_DP', '4': 'USB_DP', '5': '+5V', '2': 'GND'},
     lcsc='C7519', mpn='USBLC6-2SC6')
C('C1', '10uF', '+5V', 'GND', (68.58, 60.96))
C('C2', '100nF', '+5V', 'GND', (81.28, 60.96))
R('R3', '10k', '+5V', 'LDO_EN', (93.98, 60.96))
part('U2', 'Regulator_Linear:AP2112K-3.3', 'AP2112K-3.3', SOT235, (127.0, 63.5),
     {'1': '+5V', '2': 'GND', '3': 'LDO_EN', '4': None, '5': '+3V3'},
     lcsc='C51118', mpn='AP2112K-3.3TRG1')
C('C3', '10uF', '+3V3', 'GND', (154.94, 60.96))
C('C4', '100nF', '+3V3', 'GND', (167.64, 60.96))

# ── 2. ESP32-C3 module ─────────────────────────────────────────────────────────
esp = {str(n): 'GND' for n in [1, 2, 11, 14] + list(range(36, 54))}
esp.update({str(n): None for n in [4, 7, 9, 10, 15, 17, 24, 25, 28, 29, 32, 33, 34, 35, 13, 30, 31]})
esp.update({'3': '+3V3', '8': 'ESP_EN', '5': 'STRAP_GPIO2', '6': 'SENSOR_OUT', '12': 'RING_SENSE',
            '16': 'LED_DATA', '18': 'HALL_3V3_EN', '19': 'HALL_5V_EN', '20': 'COIL_OUT',
            '21': 'LED_BLE', '22': 'STRAP_GPIO8', '23': 'BOOT', '26': 'USB_DM', '27': 'USB_DP'})
part('U1', 'OpenRower:ESP32-C3-MINI-1', 'ESP32-C3-MINI-1-H4X', 'CustomFootprints:ESP32-C3-MINI-1',
     (248.92, 88.9), esp, lcsc='C41349510', mpn='ESP32-C3-MINI-1-H4X')
C('C5', '10uF', '+3V3', 'GND', (195.58, 53.34))
C('C6', '100nF', '+3V3', 'GND', (208.28, 53.34))
R('R4', '10k', '+3V3', 'ESP_EN', (195.58, 99.06))
C('C7', '1uF', 'ESP_EN', 'GND', (208.28, 99.06))
part('SW1', 'Switch:SW_Push', 'RESET', 'Button_Switch_SMD:SW_Push_1P1T_XKB_TS-1187A', (195.58, 129.54),
     {'1': 'GND', '2': 'ESP_EN'}, rot=90, lcsc='C318884', mpn='TS-1187A-B-A-B')
part('SW2', 'Switch:SW_Push', 'BOOT', 'Button_Switch_SMD:SW_Push_1P1T_XKB_TS-1187A', (213.36, 129.54),
     {'1': 'GND', '2': 'BOOT'}, rot=90, lcsc='C318884', mpn='TS-1187A-B-A-B')

# ── 3. strapping, LEDs, mounting holes ─────────────────────────────────────────
R('R5', '10k', '+3V3', 'STRAP_GPIO8', (320.04, 55.88))
R('R6', '10k', '+3V3', 'STRAP_GPIO2', (335.28, 55.88))
R('R7', '1k', '+3V3', 'LED1_A', (355.6, 55.88))
LED('LED1', 'Green', 'LED1_A', (355.6, 81.28), 'C965804', 'XL-1608UGC-04')
R('R8', '100R', 'LED_BLE', 'LED2_A', (370.84, 55.88))
LED('LED2', 'Blue', 'LED2_A', (370.84, 81.28), 'C965807', 'XL-1608UBC-04')
R('R9', '330R', 'LED_DATA', 'LED3_A', (386.08, 55.88))
LED('LED3', 'Red', 'LED3_A', (386.08, 81.28), 'C2286', 'KT-0603R')
for i, x in enumerate([320.04, 335.28, 350.52, 365.76]):
    part(f'H{i + 1}', 'Mechanical:MountingHole', 'M3', 'MountingHole:MountingHole_3.2mm_M3', (x, 109.22), {},
         in_bom=False)

# ── 4. sensor jack and input protection ────────────────────────────────────────
part('J2', 'Connector_Audio:AudioJack3_SwitchTR', 'PJ-242', 'CustomFootprints:Jack_2.5mm_SHOUHAN_PJ-242',
     (33.02, 170.18), {'T': 'JACK_TIP', 'R': 'JACK_RING', 'S': 'GND', 'TN': None, 'RN': None},
     lcsc='C431536', mpn='PJ-242')
D_tvs('D1', 'JACK_TIP', (68.58, 170.18))
D_tvs('D2', 'JACK_RING', (83.82, 170.18))
C('C8', '10nF', 'JACK_RING', 'GND', (99.06, 170.18))
R('R10', '10k', '+3V3', 'TIP_PU', (124.46, 157.48))
D_schottky('D3', 'TIP_PU', 'JACK_TIP', (124.46, 190.5), rot=90)   # cathode down -> JACK_TIP

# ── 5. hall supply: two current-limited, reverse-blocking switches -> RING ──────
part('U5', 'OpenRower:SY6280AAC', 'SY6280AAC', SOT235, (60.96, 223.52),
     {'5': '+3V3', '4': 'HALL_3V3_EN', '3': 'ISET_3V3', '1': 'HALL_3V3_OUT', '2': 'GND'},
     lcsc='C55136', mpn='SY6280AAC')
C('C12', '1uF', '+3V3', 'GND', (25.4, 215.9))
R('R24', '100k', 'HALL_3V3_EN', 'GND', (38.1, 233.68))
R('R23', '15k', 'ISET_3V3', 'GND', (86.36, 233.68))
C('C13', '1uF', 'HALL_3V3_OUT', 'GND', (99.06, 215.9))
D_schottky('D6', 'HALL_3V3_OUT', 'JACK_RING', (124.46, 220.98), rot=180)  # A left, K right

part('U6', 'OpenRower:SY6280AAC', 'SY6280AAC', SOT235, (60.96, 260.35),
     {'5': '+5V', '4': 'HALL_5V_EN', '3': 'ISET_5V', '1': 'HALL_5V_OUT', '2': 'GND'},
     lcsc='C55136', mpn='SY6280AAC')
C('C14', '1uF', '+5V', 'GND', (25.4, 252.73))
R('R26', '100k', 'HALL_5V_EN', 'GND', (38.1, 270.51))
R('R25', '15k', 'ISET_5V', 'GND', (86.36, 270.51))
C('C15', '1uF', 'HALL_5V_OUT', 'GND', (99.06, 252.73))
D_schottky('D7', 'HALL_5V_OUT', 'JACK_RING', (124.46, 257.81), rot=180)

R('R27', '100k', 'JACK_RING', 'RING_SENSE', (157.48, 223.52))
R('R28', '10k', 'RING_SENSE', 'GND', (157.48, 261.62))
C('C16', '10nF', 'RING_SENSE', 'GND', (170.18, 261.62))

# ── 6. comparators (LM393 on +5 V, open-collector outputs pulled to +3V3) ──────
# A: DC path, threshold 1.65 V +-78 mV -> hall sensors (and Concept2 D/E tacho)
R('R11', '100k', 'JACK_TIP', 'TIP_DC', (190.5, 175.26), horiz=True)
clamp('D4', 'TIP_DC', (220.98, 190.5))
R('R12', '100k', '+3V3', 'VREF_A', (236.22, 162.56))
R('R13', '100k', 'VREF_A', 'GND', (236.22, 195.58))
R('R14', '1M', 'VREF_A', 'SENSOR_OUT', (271.78, 157.48), horiz=True)
R('R15', '10k', '+3V3', 'SENSOR_OUT', (299.72, 160.02))
part('U3', 'Comparator:LM393', 'LM393DR', 'Package_SO:SOIC-8_3.9x4.9mm_P1.27mm', (271.78, 177.8),
     {'3': 'VREF_A', '2': 'TIP_DC', '1': 'SENSOR_OUT'}, unit=1, lcsc='C67470', mpn='LM393DR')

# B: AC-coupled zero-crossing, ~36 mV hysteresis -> Concept2 coil (C) and tacho (D/E)
R('R16', '10k', 'JACK_TIP', 'COIL_IN', (190.5, 241.3), horiz=True, fp=R0603)
C('C9', '1uF/50V', 'COIL_IN', 'COIL_AC', (223.52, 241.3), horiz=True)
R('R17', '100k', '+3V3', 'COIL_AC', (236.22, 220.98))
R('R18', '100k', 'COIL_AC', 'GND', (236.22, 264.16))
clamp('D5', 'COIL_AC', (220.98, 264.16))
R('R19', '22k', '+3V3', 'VREF_B', (254.0, 220.98))
R('R20', '22k', 'VREF_B', 'GND', (254.0, 264.16))
R('R21', '1M', 'VREF_B', 'COIL_OUT', (271.78, 226.06), horiz=True)
R('R22', '10k', '+3V3', 'COIL_OUT', (299.72, 228.6))
part('U3', 'Comparator:LM393', 'LM393DR', 'Package_SO:SOIC-8_3.9x4.9mm_P1.27mm', (271.78, 246.38),
     {'5': 'VREF_B', '6': 'COIL_AC', '7': 'COIL_OUT'}, unit=2, lcsc='C67470', mpn='LM393DR')
part('U3', 'Comparator:LM393', 'LM393DR', 'Package_SO:SOIC-8_3.9x4.9mm_P1.27mm', (332.74, 157.48),
     {'8': '+5V', '4': 'GND'}, unit=3, lcsc='C67470', mpn='LM393DR')
C('C10', '100nF', '+5V', 'GND', (345.44, 157.48))
C('C11', '10nF', '+5V', 'GND', (358.14, 157.48))

# power flags (+5V comes from a passive fuse, GND and VBUS from the connector)
FLAGS = [('VBUS', (142.24, 86.36)), ('+5V', (154.94, 86.36)), ('GND', (167.64, 86.36))]

ROUTES = {('J2', 'S'): [(2.54, 0), (0, -2.54), (2.54, 0)]}

FIELDS = {'D4': 'left', 'D5': 'left', 'J2': 'tb', 'SW1': 'left', 'SW2': 'right', ('U3', 3): 'left'}

NOTES = [
    'Sensor input modes (selected in the app; firmware drives HALL_*_EN):',
    '  Hall 3.3 V : U5 on  -> RING ~3.0 V (after D6). Read SENSOR_OUT (GPIO3).',
    '  Hall 5 V   : U6 on  -> RING ~4.7 V (after D7). Read SENSOR_OUT (GPIO3).',
    '  Concept2   : both off (RING unpowered). Read COIL_OUT (GPIO6).',
    '',
    'Jack wiring:',
    '  Concept2 C   : mono plug, TIP = coil (~100 Ohm), RING shorted to SLEEVE.',
    '  Concept2 D/E : TIP = tacho 0-18 V, RING = generator DC ~18 V.',
    '  Hall sensor  : TIP = output, RING = VCC from U5/U6, SLEEVE = GND.',
    '',
    'Comparator A (DC): threshold 1.65 V, hysteresis ~156 mV. Hall sensors',
    '  (open-collector via R10/D3 pull-up, or push-pull 3.3/5 V). Output HIGH',
    '  while TIP is low (magnet present). Also toggles on the D/E tacho.',
    'Comparator B (AC-coupled, fc ~2.7 Hz): zero-crossing with ~36 mV',
    '  hysteresis (~43 mV at TIP). Concept2 C coil and D/E tacho.',
    '  Output HIGH during the negative half-cycle.',
    '',
    'Protection: TIP/RING tolerate 0..27 V continuous (SMF24CA) in every mode.',
    '  Nothing clamps into +5V/VBUS; D6/D7 (40 V) block the D/E generator.',
    '  U5/U6 limit ~0.45 A (0.23 A into a short, thermal retry).',
    'RING_SENSE (GPIO0, x0.0909): ~0 V while a supply is on = short or mono',
    '  plug -> switch off. > 6 V with both off = Concept2 D/E generator.',
]

TEXTS = [
    ((20.32, 27.94), 3.0, 'OpenRower sensor board  -  rev 2 (2026-10-17)'),
    ((20.32, 38.1), 2.0, '1. USB-C input, ESD, 3.3 V LDO'),
    ((187.96, 38.1), 2.0, '2. ESP32-C3-MINI-1-H4X (BLE + native USB CDC)'),
    ((312.42, 38.1), 2.0, '3. Strapping pull-ups, status LEDs, mounting holes'),
    ((20.32, 147.32), 2.0, '4. Sensor jack + input protection'),
    ((20.32, 200.66), 2.0, '5. Hall supply on RING (current-limited, reverse-blocking)'),
    ((152.4, 147.32), 2.0, '6. Comparators: LM393 on +5 V, inputs <= 3.6 V, outputs -> 3.3 V'),
] + [((312.42, 177.8 + 2.8 * i), 1.27, line) for i, line in enumerate(NOTES) if line]


# ── symbol library handling ───────────────────────────────────────────────────
_libcache = {}


def _lib(libname):
    path = PRJLIB if libname == 'OpenRower' else f'{SYSLIB}/{libname}.kicad_sym'
    if path not in _libcache:
        _libcache[path] = {s[1]: s for s in find(parse(open(path).read()), 'symbol')}
    return _libcache[path]


def flatten(syms, name):
    s = copy.deepcopy(syms[name])
    ext = first(s, 'extends')
    if not ext:
        return s
    parent = flatten(syms, ext[1])
    child_props = {p[1]: p for p in find(s, 'property')}
    out = ['symbol', Q(name)]
    for item in parent[2:]:
        if isinstance(item, list) and item[0] == 'property':
            out.append(child_props.pop(item[1], item))
        elif isinstance(item, list) and item[0] == 'symbol':
            sub = copy.deepcopy(item)
            sub[1] = Q(name + sub[1][len(ext[1]):])
            out.append(sub)
        else:
            out.append(item)
    idx = next(i for i, it in enumerate(out) if isinstance(it, list) and it[0] == 'symbol')
    out[idx:idx] = list(child_props.values())
    return out


def lib_symbol(lib_id):
    libname, name = lib_id.split(':')
    return flatten(_lib(libname), name)


def symbol_pins(sym, unit):
    pins = {}
    for sub in find(sym, 'symbol'):
        m = re.match(r'.*_(\d+)_(\d+)$', sub[1])
        u, style = int(m.group(1)), int(m.group(2))
        if u not in (0, unit) or style not in (0, 1):
            continue
        for p in find(sub, 'pin'):
            at = first(p, 'at')
            num = first(p, 'number')[1]
            pins[num] = (float(at[1]), float(at[2]), float(at[3]))
    return pins


def xform(pos, rot, x, y):
    r = math.radians(rot)
    xr = x * math.cos(r) - y * math.sin(r)
    yr = x * math.sin(r) + y * math.cos(r)
    return (round(pos[0] + xr, 3), round(pos[1] - yr, 3))


def step(pt, ang, dist):
    a = math.radians(ang)
    return (round(pt[0] + dist * math.cos(a), 3), round(pt[1] - dist * math.sin(a), 3))


# ── s-expression builders ─────────────────────────────────────────────────────
def uid(*key):
    return ['uuid', Q(str(uuid.uuid5(NS, ':'.join(str(k) for k in key))))]


def effects(size=1.27, justify=None, hide=False):
    e = ['effects', ['font', ['size', size, size]]]
    if justify:
        e.append(['justify'] + justify.split())
    return e


def sym_prop(name, value, at, hide=False, justify=None, ang=0):
    p = ['property', Q(name), Q(value), ['at', at[0], at[1], ang]]
    if hide:
        p.append(['hide', 'yes'])
    p += [['show_name', 'no'], ['do_not_autoplace', 'no'], effects(justify=justify)]
    return p


def wire(a, b):
    return ['wire', ['pts', ['xy', a[0], a[1]], ['xy', b[0], b[1]]],
            ['stroke', ['width', 0], ['type', 'default']], uid('wire', a, b)]


def label(net, at, ang):
    just = 'left bottom' if ang in (0, 90) else 'right bottom'
    return ['label', Q(net), ['at', at[0], at[1], ang], ['fields_autoplaced', 'yes'],
            effects(justify=just), uid('label', net, at)]


def instance(ref, unit, lib_id, value, pos, rot, props, pins, in_bom=True, key=None):
    key = key or (ref, unit)
    node = ['symbol', ['lib_id', Q(lib_id)], ['at', pos[0], pos[1], rot], ['unit', unit],
            ['body_style', 1], ['exclude_from_sim', 'no'], ['in_bom', 'yes' if in_bom else 'no'],
            ['on_board', 'yes'], ['in_pos_files', 'yes' if in_bom else 'no'], ['dnp', 'no'],
            uid('sym', *key)]
    node += props
    node += [['pin', Q(n), uid('pin', *key, n)] for n in sorted(pins)]
    node.append(['instances', ['project', Q(PROJECT),
                               ['path', Q('/' + ROOT_UUID), ['reference', Q(ref)], ['unit', unit]]]])
    return node


POWER_LIB = {'GND': 'power:GND', '+3V3': 'power:+3V3', '+5V': 'power:+5V'}
_pwr_count = [0]


def power_symbol(net, at, out_ang):
    _pwr_count[0] += 1
    ref = f'#PWR{_pwr_count[0]:03d}'
    graphic = 270 if net == 'GND' else 90
    rot = (out_ang - graphic) % 360
    vpos = step(at, out_ang, 5.08 if net == 'GND' else 4.45)
    props = [sym_prop('Reference', ref, at, hide=True),
             sym_prop('Value', net, vpos),
             sym_prop('Footprint', '', at, hide=True),
             sym_prop('Datasheet', '', at, hide=True),
             sym_prop('Description', '', at, hide=True)]
    return instance(ref, 1, POWER_LIB[net], net, at, rot, props, ['1'], key=('pwr', net, at))


def main():
    used_libs = {}
    items = []
    for p in PARTS:
        sym = lib_symbol(p['lib_id'])
        used_libs[p['lib_id']] = sym
        pins = symbol_pins(sym, p['unit'])
        missing = set(p['pins']) - set(pins)
        if missing:
            sys.exit(f"{p['ref']}: pins {missing} not in {p['lib_id']} unit {p['unit']}")
        unmapped = set(pins) - set(p['pins'])
        if unmapped and p['lib_id'] != 'Mechanical:MountingHole':
            sys.exit(f"{p['ref']}: pins {sorted(unmapped)} have no net (use None for no-connect)")

        # reference / value text placement
        xs = [xform(p['pos'], p['rot'], x, y)[0] for x, y, _ in pins.values()] or [p['pos'][0]]
        ys = [xform(p['pos'], p['rot'], x, y)[1] for x, y, _ in pins.values()] or [p['pos'][1]]
        big = (max(xs) - min(xs) > 12) or (max(ys) - min(ys) > 12)
        horiz2 = len(pins) == 2 and max(xs) - min(xs) > max(ys) - min(ys)
        mode = FIELDS.get((p['ref'], p['unit'])) or FIELDS.get(p['ref']) or ('tb' if big or horiz2 else 'right')
        x0, y0 = p['pos']
        if mode == 'tb':
            top = min(ys) - (2.54 if horiz2 else 8.89)
            bot = max(ys) + (2.54 if horiz2 else 10.16)
            ref_at, val_at, just = (x0, round(top, 2)), (x0, round(bot, 2)), None
        else:
            off = 2.54 if p['rot'] in (0, 180) else 4.45
            if mode == 'left':
                off, just = -off - 1.27, 'right'
            else:
                just = 'left'
            ref_at, val_at = (round(x0 + off, 2), round(y0 - 1.27, 2)), (round(x0 + off, 2), round(y0 + 1.27, 2))
        ds = prop(sym, 'Datasheet')
        desc = prop(sym, 'Description')
        fang = (-p['rot']) % 360 if p['rot'] in (90, 270) else 0   # keep field text horizontal
        props = [sym_prop('Reference', p['ref'], ref_at, justify=just, ang=fang),
                 sym_prop('Value', p['value'], val_at, justify=just, ang=fang),
                 sym_prop('Footprint', p['fp'], p['pos'], hide=True),
                 sym_prop('Datasheet', ds[2] if ds else '', p['pos'], hide=True),
                 sym_prop('Description', desc[2] if desc else '', p['pos'], hide=True)]
        if p['lcsc']:
            props += [sym_prop('LCSC', p['lcsc'], p['pos'], hide=True),
                      sym_prop('MPN', p['mpn'], p['pos'], hide=True)]
        items.append(instance(p['ref'], p['unit'], p['lib_id'], p['value'], p['pos'], p['rot'],
                              props, list(pins), in_bom=p['in_bom']))

        done = set()
        for num, net in p['pins'].items():
            x, y, a = pins[num]
            pt = xform(p['pos'], p['rot'], x, y)
            if pt in done:
                continue           # stacked pins (e.g. USB VBUS) share one stub
            done.add(pt)
            if net is None:
                items.append(['no_connect', ['at', pt[0], pt[1]], uid('nc', p['ref'], num)])
                continue
            out = (a + 180 + p['rot']) % 360
            route = ROUTES.get((p['ref'], num))
            if route:
                end = pt
                for dx, dy in route:
                    nxt = (round(end[0] + dx, 3), round(end[1] + dy, 3))
                    items.append(wire(end, nxt))
                    end = nxt
                out = int(math.degrees(math.atan2(-dy, dx))) % 360
            else:
                end = step(pt, out, STUB)
                items.append(wire(pt, end))
            if net in POWER_LIB:
                items.append(power_symbol(net, end, out))
            else:
                items.append(label(net, end, int(out)))

    for net, at in FLAGS:
        b = (at[0], at[1] + 5.08)
        items.append(wire(at, b))
        _pwr_count[0] += 1
        items.append(instance(f'#FLG{_pwr_count[0]:03d}', 1, 'power:PWR_FLAG', 'PWR_FLAG', at, 0,
                              [sym_prop('Reference', f'#FLG{_pwr_count[0]:03d}', at, hide=True),
                               sym_prop('Value', 'PWR_FLAG', (at[0], at[1] - 3.81)),
                               sym_prop('Footprint', '', at, hide=True),
                               sym_prop('Datasheet', '', at, hide=True),
                               sym_prop('Description', '', at, hide=True)],
                              ['1'], key=('flag', net)))
        if net in POWER_LIB:
            items.append(power_symbol(net, b, 270))
        else:
            items.append(label(net, b, 270))

    for net in ('GND', '+3V3', '+5V'):
        used_libs[POWER_LIB[net]] = lib_symbol(POWER_LIB[net])
    used_libs['power:PWR_FLAG'] = lib_symbol('power:PWR_FLAG')

    for (x, y), size, text in TEXTS:
        items.append(['text', Q(text), ['exclude_from_sim', 'no'], ['at', x, y, 0],
                      effects(size=size, justify='left bottom'), uid('text', text)])

    lib_symbols = ['lib_symbols']
    for lib_id in sorted(used_libs):
        s = copy.deepcopy(used_libs[lib_id])
        s[1] = Q(lib_id)
        lib_symbols.append(s)

    sch = ['kicad_sch', ['version', '20260306'], ['generator', Q('eeschema')],
           ['generator_version', Q('10.0')], ['uuid', Q(ROOT_UUID)], ['paper', Q('A3')],
           ['title_block', ['title', Q('OpenRower sensor board')], ['date', Q('2026-10-17')],
            ['rev', Q('2')], ['comment', 1, Q('Generated by tools/gen_schematic.py - edit the generator, not this file')]],
           lib_symbols] + items + [
           ['sheet_instances', ['path', Q('/'), ['page', Q('1')]]], ['embedded_fonts', 'no']]
    with open(OUT, 'w') as f:
        f.write(dump(sch) + '\n')
    print(f'wrote {OUT}: {len(PARTS)} symbol units')


if __name__ == '__main__':
    main()
