"""Minimal KiCad S-expression reader/writer used by the OpenRower hardware generators."""

import re


class Q(str):
    """A quoted string atom."""


_TOKEN = re.compile(r'\(|\)|"(?:[^"\\]|\\.)*"|[^\s()]+')


def parse(text):
    stack = [[]]
    for tok in _TOKEN.findall(text):
        if tok == '(':
            stack.append([])
        elif tok == ')':
            node = stack.pop()
            stack[-1].append(node)
        elif tok.startswith('"'):
            stack[-1].append(Q(tok[1:-1].replace('\\"', '"').replace('\\\\', '\\')))
        else:
            stack[-1].append(tok)
    return stack[0][0]


def _atom(a):
    if isinstance(a, Q):
        return '"' + a.replace('\\', '\\\\').replace('"', '\\"') + '"'
    if isinstance(a, float):
        s = f'{a:.4f}'.rstrip('0').rstrip('.')
        return '0' if s in ('-0', '') else s
    return str(a)


def dump(node, indent=0):
    """Serialize with one nested list per line, like KiCad does."""
    if not isinstance(node, list):
        return _atom(node)
    head = []
    tail = []
    for item in node:
        (tail if isinstance(item, list) else head).append(item) if not tail else tail.append(item)
    out = '(' + ' '.join(_atom(a) for a in head)
    if not tail:
        return out + ')'
    pad = '\t' * (indent + 1)
    for item in tail:
        out += '\n' + pad + (dump(item, indent + 1) if isinstance(item, list) else _atom(item))
    return out + '\n' + '\t' * indent + ')'


def find(node, tag):
    return [c for c in node if isinstance(c, list) and c and c[0] == tag]


def first(node, tag):
    r = find(node, tag)
    return r[0] if r else None


def prop(node, name):
    for p in find(node, 'property'):
        if p[1] == name:
            return p
    return None
