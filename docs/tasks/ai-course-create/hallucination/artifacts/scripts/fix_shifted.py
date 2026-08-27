import csv, io, sys

VALID_VERDICTS = {'CORRECT', 'LAUNDERED', 'WRONG_MATCH', 'UNVERIFIABLE'}
FIELDNAMES = ['scoreBand', 'bestScore', 'requestId', 'day', 'location', 'aiPlaceName',
              'matchedPlaceName', 'matchedAddress', 'matchedPlaceUrl', 'verdict', 'note']

def fix_file(path):
    with open(path, encoding='utf-8-sig') as f:
        lines = f.readlines()
    data_start = next(i for i, l in enumerate(lines) if not l.lstrip().startswith('#'))
    header_block = lines[:data_start]
    rows = list(csv.DictReader(io.StringIO(''.join(lines[data_start:]))))

    fixed = 0
    for r in rows:
        if r['verdict'].strip():
            continue
        if r['note'].strip() in VALID_VERDICTS and r.get(None):
            real_verdict = r['note'].strip()
            real_note = ','.join(r[None])  # 밀리면서 콤마로 쪼개졌을 수 있는 원래 note 복원
            r['verdict'] = real_verdict
            r['note'] = real_note
            del r[None]
            fixed += 1
        else:
            print(f'  경고: {path} 에 예상 못한 빈 verdict 행이 있음: {r}', file=sys.stderr)

    out = io.StringIO()
    w = csv.DictWriter(out, fieldnames=FIELDNAMES)
    w.writeheader()
    for r in rows:
        w.writerow({k: r.get(k, '') for k in FIELDNAMES})

    with open(path, 'w', encoding='utf-8-sig', newline='') as f:
        f.writelines(header_block)
        f.write(out.getvalue())

    print(f'{path}: {fixed}건 복구, 총 {len(rows)}행 재저장')
    return rows

for p in ['results/manual-verification-20260811-174326.csv',
          'results/manual-verification-20260812-184600.csv']:
    fix_file(p)
