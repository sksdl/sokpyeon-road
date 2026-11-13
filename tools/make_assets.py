"""data.json(경로 분석 결과)에서 앱이 쓰는 routes.json만 뽑는다. 배경 도로(bg)는 뺀다."""
import json, sys
src, dst = sys.argv[1], sys.argv[2]
d = json.load(open(src))
def r1(v, n=1): return round(v, n)
out = {'model': d['model'], 'child_thr': d['child_thr'], 'sources': d['sources'], 'routes': []}
for r in d['routes']:
    S = r['series']
    out['routes'].append({
        'id': r['id'], 'dest': r['dest'], 'dest_name': r['dest_name'], 'name': r['name'],
        'km': r['km'], 'min': r['min'], 'msdv': r['msdv'], 'eff_end': r['eff_end'],
        'over_sections': r['over_sections'],
        'poly': [[round(x, 5), round(y, 5)] for x, y in r['poly']],
        'poly_s': [int(v) for v in r['poly_s']],
        'sections': r['sections'], 'cues': r['cues'], 'stops': r['stops'],
        'series': {'t': [int(v) for v in S['t']], 's': [int(v) for v in S['s']],
                   'v': [r1(v) for v in S['v']], 'ay': [round(v, 2) for v in S['ay']],
                   'dose': [round(v, 2) for v in S['dose']]},
    })
json.dump(out, open(dst, 'w'), ensure_ascii=False, separators=(',', ':'))
